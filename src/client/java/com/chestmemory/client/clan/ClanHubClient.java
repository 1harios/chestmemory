package com.chestmemory.client.clan;

import com.chestmemory.ChestMemoryMod;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import org.jspecify.annotations.Nullable;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;

/**
 * Thin HTTP client for the clan gather hub.
 */
public final class ClanHubClient {
	private static final Gson GSON = new GsonBuilder().create();
	private static final HttpClient HTTP = HttpClient.newBuilder()
		.connectTimeout(Duration.ofSeconds(8))
		.build();

	/**
	 * Error every request reports when the configured hub URL was refused (see
	 * {@link #isAllowedHubUrl}). A constant so the manager can recognise it and show
	 * the localized explanation instead of a raw string.
	 */
	public static final String ERR_INSECURE_URL = "insecure hub url (https required)";

	/** One client per configuration — building a new one per call was pure allocation churn. */
	private static volatile @Nullable ClanHubClient cached;

	/**
	 * Compatibility hint sent as plain headers, NOT an identity mechanism.
	 * <p>
	 * Identity is the verified Mojang session ({@code X-Clan-Session}); since the hub
	 * started requiring it by default, these headers carry no authority at all. The one
	 * thing they still do: while an operator has explicitly switched verification off
	 * (REQUIRE_AUTH=0, the mod-upgrade window), the hub honours them for non-host
	 * heartbeats, so an offline-mode member's poll refreshes lastSeen and their claims
	 * do not time out mid-game. Host actions ignore them unconditionally.
	 */
	private static volatile String hintUuid = "";
	private static volatile String hintName = "";

	private final String baseUrl;
	private final String token;
	/** URL configured but refused ({@link #isAllowedHubUrl}) — every request short-circuits. */
	private final boolean rejectedUrl;

	public ClanHubClient(String baseUrl, String token) {
		String u = baseUrl == null ? "" : baseUrl.trim();
		while (u.endsWith("/")) {
			u = u.substring(0, u.length() - 1);
		}
		this.baseUrl = u;
		this.token = token == null ? "" : token.trim();
		this.rejectedUrl = !u.isEmpty() && !isAllowedHubUrl(u);
	}

	/**
	 * True when the client will talk to this hub URL: {@code https://} anywhere, or
	 * {@code http://} strictly on the local machine (localhost / 127.0.0.1 / [::1])
	 * so a development hub still works.
	 * <p>
	 * Everything a request carries — the invite token, the Mojang-derived session
	 * token, uuids, the whole session — would otherwise cross the network in cleartext,
	 * and anyone who can edit the mod config could redirect all of it to their own
	 * host and replay the session token against the real hub. Empty is "not
	 * configured", which is a different state and not judged here.
	 */
	public static boolean isAllowedHubUrl(@Nullable String url) {
		String u = url == null ? "" : url.trim();
		if (u.isEmpty()) {
			return false;
		}
		try {
			URI uri = URI.create(u);
			String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
			if ("https".equals(scheme)) {
				return true;
			}
			if (!"http".equals(scheme)) {
				return false;
			}
			String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
			if (host.startsWith("[") && host.endsWith("]")) {
				host = host.substring(1, host.length() - 1);
			}
			return host.equals("localhost") || host.equals("127.0.0.1") || host.equals("::1");
		} catch (Exception e) {
			return false;
		}
	}

	/** Shared instance for this configuration; rebuilt only when url/token change. */
	public static ClanHubClient of(String baseUrl, String token) {
		ClanHubClient c = cached;
		String u = baseUrl == null ? "" : baseUrl.trim();
		String t = token == null ? "" : token.trim();
		if (c != null && c.baseUrl.equals(stripSlashes(u)) && c.token.equals(t)) {
			return c;
		}
		c = new ClanHubClient(u, t);
		cached = c;
		return c;
	}

	private static String stripSlashes(String u) {
		while (u.endsWith("/")) {
			u = u.substring(0, u.length() - 1);
		}
		return u;
	}

	/** Who this client acts as — sent as heartbeat headers on every request. */
	public static void setIdentityHint(@Nullable String uuid, @Nullable String name) {
		hintUuid = uuid == null ? "" : uuid;
		hintName = name == null ? "" : name;
	}

	public boolean isConfigured() {
		return !baseUrl.isEmpty();
	}

	public String baseUrl() {
		return baseUrl;
	}

	public Result<ClanSession> create(JsonObject body) {
		return post("/v1/sessions", body);
	}

	public Result<ClanSession> join(String code, JsonObject body) {
		return post("/v1/sessions/" + enc(code) + "/join", body);
	}

	public Result<ClanSession> get(String code) {
		return getReq("/v1/sessions/" + enc(code));
	}

	/**
	 * Poll with the last seen revision: the hub answers a tiny {@code unchanged} stub
	 * (status 304 in the result) when nothing moved, so the steady-state poll costs a
	 * heartbeat instead of a full snapshot parse + diff every three seconds.
	 */
	public Result<ClanSession> getSince(String code, int revision) {
		return getReq("/v1/sessions/" + enc(code) + "?since=" + revision);
	}

	/** Report several items' warehouse totals in one request: {"amounts": {item: n}}. */
	public Result<ClanSession> deliverBatch(String code, JsonObject body) {
		return post("/v1/sessions/" + enc(code) + "/deliver", body);
	}

	public Result<ClanSession> claim(String code, JsonObject body) {
		return post("/v1/sessions/" + enc(code) + "/claim", body);
	}

	public Result<ClanSession> deliver(String code, JsonObject body) {
		return post("/v1/sessions/" + enc(code) + "/deliver", body);
	}

	public Result<ClanSession> leave(String code, JsonObject body) {
		return post("/v1/sessions/" + enc(code) + "/leave", body);
	}

	public Result<ClanSession> close(String code, JsonObject body) {
		return post("/v1/sessions/" + enc(code) + "/close", body);
	}

	/** Push / replace shared warehouse (staging) chest keys. */
	public Result<ClanSession> staging(String code, JsonObject body) {
		return post("/v1/sessions/" + enc(code) + "/staging", body);
	}

	/** Rename the gather (host only): every member's panel picks the new name up. */
	public Result<ClanSession> update(String code, JsonObject body) {
		return post("/v1/sessions/" + enc(code) + "/update", body);
	}

	/** Remove a member (host only). The hub releases their claims with them. */
	public Result<ClanSession> kick(String code, JsonObject body) {
		return post("/v1/sessions/" + enc(code) + "/kick", body);
	}

	/** Clear every claim (host only) — the reset for a stalled evening. */
	public Result<ClanSession> releaseClaims(String code, JsonObject body) {
		return post("/v1/sessions/" + enc(code) + "/release_claims", body);
	}

	/**
	 * Strike a material off the gather, or put it back (host only).
	 * <p>
	 * Body is {@code {"itemId": id, "excluded": bool}}, or {@code {"items": {id: bool}}}
	 * for a bulk edit. The material keeps its delivered history either way — exclusion is
	 * a mark, not a deletion.
	 */
	public Result<ClanSession> exclude(String code, JsonObject body) {
		return post("/v1/sessions/" + enc(code) + "/exclude", body);
	}

	/** Ask for a nonce to sign via Mojang joinServer. Returns the nonce. */
	public Result<String> authChallenge() {
		Result<JsonObject> res = rawGet("/v1/auth/challenge");
		if (!res.ok || res.value == null) {
			return Result.err(res.error != null ? res.error : "no challenge", res.status);
		}
		String nonce = res.value.has("nonce") ? res.value.get("nonce").getAsString() : "";
		return nonce.isBlank() ? Result.err("empty nonce") : Result.ok(nonce);
	}

	/** Exchange a signed nonce for a session token. Returns the token. */
	public Result<String> authVerify(JsonObject body) {
		Result<JsonObject> res = rawPost("/v1/auth/verify", body);
		if (!res.ok || res.value == null) {
			return Result.err(res.error != null ? res.error : "verify failed", res.status);
		}
		String token = res.value.has("token") ? res.value.get("token").getAsString() : "";
		return token.isBlank() ? Result.err("empty token") : Result.ok(token);
	}

	/** Raw JSON GET, used by the auth handshake (no ClanSession parsing). */
	private Result<JsonObject> rawGet(String path) {
		if (rejectedUrl) {
			return Result.err(ERR_INSECURE_URL);
		}
		try {
			HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + path))
				.timeout(Duration.ofSeconds(10))
				.GET();
			auth(b);
			return rawParse(HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)));
		} catch (Exception e) {
			return Result.err(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
		}
	}

	private Result<JsonObject> rawPost(String path, JsonObject body) {
		if (rejectedUrl) {
			return Result.err(ERR_INSECURE_URL);
		}
		try {
			HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + path))
				.timeout(Duration.ofSeconds(12))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body), StandardCharsets.UTF_8));
			auth(b);
			return rawParse(HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)));
		} catch (Exception e) {
			return Result.err(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
		}
	}

	private Result<JsonObject> rawParse(HttpResponse<String> resp) {
		int code = resp.statusCode();
		String body = resp.body() == null ? "" : resp.body();
		if (code >= 200 && code < 300) {
			try {
				JsonObject o = GSON.fromJson(body, JsonObject.class);
				return o == null ? Result.err("bad response", code) : Result.ok(o);
			} catch (Exception e) {
				return Result.err("parse error", code);
			}
		}
		String msg = "HTTP " + code;
		try {
			JsonObject o = GSON.fromJson(body, JsonObject.class);
			if (o != null && o.has("error")) {
				msg = o.get("error").getAsString();
			}
		} catch (Exception ignored) {
		}
		return Result.err(msg, code, retryAfterSeconds(resp));
	}

	/** Seconds the hub asked us to wait (429 Retry-After), or 0 when not rate limited. */
	private static int retryAfterSeconds(HttpResponse<String> resp) {
		if (resp.statusCode() != 429) {
			return 0;
		}
		try {
			return Integer.parseInt(resp.headers().firstValue("Retry-After").orElse("").trim());
		} catch (NumberFormatException e) {
			// Both hub backends send plain seconds; anything else falls back to a default
			// on the manager side.
			return 0;
		}
	}

	public Result<String> health() {
		if (rejectedUrl) {
			return Result.err(ERR_INSECURE_URL);
		}
		try {
			HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + "/v1/health"))
				.timeout(Duration.ofSeconds(6))
				.GET();
			auth(b);
			HttpResponse<String> resp = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
				return Result.ok(resp.body());
			}
			return Result.err("HTTP " + resp.statusCode(), resp.statusCode(), retryAfterSeconds(resp));
		} catch (Exception e) {
			return Result.err(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
		}
	}

	private Result<ClanSession> getReq(String path) {
		if (rejectedUrl) {
			return Result.err(ERR_INSECURE_URL);
		}
		try {
			HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + path))
				.timeout(Duration.ofSeconds(10))
				.GET();
			auth(b);
			return parse(HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)));
		} catch (Exception e) {
			return Result.err(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
		}
	}

	private Result<ClanSession> post(String path, JsonObject body) {
		if (rejectedUrl) {
			return Result.err(ERR_INSECURE_URL);
		}
		try {
			String json = GSON.toJson(body);
			HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + path))
				.timeout(Duration.ofSeconds(12))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
			auth(b);
			return parse(HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)));
		} catch (Exception e) {
			return Result.err(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
		}
	}

	private void auth(HttpRequest.Builder b) {
		if (!token.isEmpty()) {
			b.header("X-Clan-Token", token);
		}
		// Identity, as opposed to the shared invite token above. Absent until the
		// Mojang handshake has run; the hub then knows which player is acting.
		String session = ClanAuth.sessionToken(baseUrl);
		if (session != null) {
			b.header("X-Clan-Session", session);
		}
		// Compatibility only, never identity: the hub verifies who we are via the
		// session header above. These are read solely for non-host heartbeats on a
		// hub whose operator explicitly disabled verification (REQUIRE_AUTH=0), so an
		// offline-mode member's poll still refreshes lastSeen.
		if (!hintUuid.isEmpty()) {
			b.header("X-Clan-Uuid", hintUuid);
			b.header("X-Clan-Name", hintName.isEmpty() ? "?" : hintName);
		}
	}

	private static String enc(String code) {
		return URLEncoder.encode(code, StandardCharsets.UTF_8);
	}

	private Result<ClanSession> parse(HttpResponse<String> resp) {
		int code = resp.statusCode();
		String body = resp.body() == null ? "" : resp.body();
		if (code >= 200 && code < 300) {
			try {
				JsonObject probe = GSON.fromJson(body, JsonObject.class);
				if (probe != null && probe.has("unchanged")
					&& probe.get("unchanged").getAsBoolean()) {
					// since-poll: nothing moved on the hub. Not an error, not a snapshot —
					// but it is still a heartbeat, and the stub carries the hub's clock plus
					// every member's lastSeen. Dropping that payload is what marked players
					// offline while they were standing in front of the chest: the away timer
					// kept running against a lastSeen that no successful poll ever refreshed.
					return Result.unchanged(heartbeat(probe));
				}
				ClanSession s = GSON.fromJson(body, ClanSession.class);
				if (s == null || s.code == null || s.code.isBlank()) {
					return Result.err("bad response");
				}
				s.receivedAt = System.currentTimeMillis();
				return Result.ok(s);
			} catch (Exception e) {
				ChestMemoryMod.LOGGER.warn("Clan hub parse failed: {}", e.toString());
				return Result.err("parse error");
			}
		}
		String msg = body;
		try {
			JsonObject o = GSON.fromJson(body, JsonObject.class);
			if (o != null && o.has("error")) {
				msg = o.get("error").getAsString();
			}
		} catch (Exception ignored) {
		}
		if (msg == null || msg.isBlank()) {
			msg = "HTTP " + code;
		}
		return Result.err(msg, code, retryAfterSeconds(resp));
	}

	/**
	 * What a since-poll stub still tells us, even though it carries no snapshot.
	 *
	 * @param now     the hub's clock when it answered — the reference {@code lastSeen} is
	 *                measured against, so it must come from the hub, never from here
	 * @param lastSeen uuid (lower case) → that member's {@code lastSeen} in hub time
	 */
	public record Heartbeat(long now, Map<String, Long> lastSeen) {
	}

	/** Read the stub's clock and roster freshness. Absent fields simply yield nothing. */
	private static Heartbeat heartbeat(JsonObject stub) {
		long now = stub.has("now") && stub.get("now").isJsonPrimitive()
			? stub.get("now").getAsLong()
			: 0L;
		Map<String, Long> seen = new java.util.HashMap<>();
		if (stub.has("seen") && stub.get("seen").isJsonObject()) {
			for (var e : stub.getAsJsonObject("seen").entrySet()) {
				try {
					seen.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue().getAsLong());
				} catch (Exception ignored) {
					// A malformed row is one stale member row, not a failed poll.
				}
			}
		}
		return new Heartbeat(now, Map.copyOf(seen));
	}

	public static final class Result<T> {
		public final boolean ok;
		public final @Nullable T value;
		public final @Nullable String error;
		/** HTTP status, or 0 when the request never produced a response (network error). */
		public final int status;
		/** Retry-After seconds when {@link #status} is 429, else 0. */
		public final int retryAfterSeconds;
		/** Set only on a since-poll stub ({@link #isUnchanged()}); null otherwise. */
		public final @Nullable Heartbeat heartbeat;

		private Result(
			boolean ok, @Nullable T value, @Nullable String error, int status,
			int retryAfterSeconds, @Nullable Heartbeat heartbeat
		) {
			this.ok = ok;
			this.value = value;
			this.error = error;
			this.status = status;
			this.retryAfterSeconds = retryAfterSeconds;
			this.heartbeat = heartbeat;
		}

		public static <T> Result<T> ok(T v) {
			return new Result<>(true, v, null, 200, 0, null);
		}

		public static <T> Result<T> err(String e) {
			return new Result<>(false, null, e, 0, 0, null);
		}

		public static <T> Result<T> err(String e, int status) {
			return new Result<>(false, null, e, status, 0, null);
		}

		public static <T> Result<T> err(String e, int status, int retryAfterSeconds) {
			return new Result<>(false, null, e, status, retryAfterSeconds, null);
		}

		/** since-poll stub: no snapshot, but a heartbeat worth applying. */
		public static <T> Result<T> unchanged(@Nullable Heartbeat hb) {
			return new Result<>(false, null, "unchanged", 304, 0, hb);
		}

		/** The hub told us to back off (rate limit). */
		public boolean isRateLimited() {
			return status == 429;
		}

		/** The hub authoritatively reported that this session no longer exists. */
		public boolean isNotFound() {
			return status == 404;
		}

		/** since-poll answered "nothing changed" — keep the current snapshot. */
		public boolean isUnchanged() {
			return status == 304;
		}
	}

	/** Build materials map for create body. */
	public static JsonObject materialsJson(Map<String, Integer> needByItem) {
		JsonObject o = new JsonObject();
		for (Map.Entry<String, Integer> e : needByItem.entrySet()) {
			if (e.getKey() == null || e.getValue() == null || e.getValue() <= 0) {
				continue;
			}
			o.addProperty(e.getKey(), e.getValue());
		}
		return o;
	}
}
