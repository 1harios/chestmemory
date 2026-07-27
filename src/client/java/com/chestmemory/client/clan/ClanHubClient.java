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
import java.util.Map;

/**
 * Thin HTTP client for the clan gather hub.
 */
public final class ClanHubClient {
	private static final Gson GSON = new GsonBuilder().create();
	private static final HttpClient HTTP = HttpClient.newBuilder()
		.connectTimeout(Duration.ofSeconds(8))
		.build();

	/** One client per configuration — building a new one per call was pure allocation churn. */
	private static volatile @Nullable ClanHubClient cached;

	/**
	 * Identity hint sent with every request as plain headers.
	 * <p>
	 * The verified Mojang session is the real identity, but offline-mode launchers can
	 * never complete that handshake — and without any identity the hub could not refresh
	 * the member's heartbeat on polls, so their claims were silently released after the
	 * timeout even though they were online the whole time. The hint closes that hole
	 * while REQUIRE_AUTH is off; a verified session always wins over it on the hub.
	 */
	private static volatile String hintUuid = "";
	private static volatile String hintName = "";

	private final String baseUrl;
	private final String token;

	public ClanHubClient(String baseUrl, String token) {
		String u = baseUrl == null ? "" : baseUrl.trim();
		while (u.endsWith("/")) {
			u = u.substring(0, u.length() - 1);
		}
		this.baseUrl = u;
		this.token = token == null ? "" : token.trim();
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
		return Result.err(msg, code);
	}

	public Result<String> health() {
		try {
			HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + "/v1/health"))
				.timeout(Duration.ofSeconds(6))
				.GET();
			auth(b);
			HttpResponse<String> resp = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
				return Result.ok(resp.body());
			}
			return Result.err("HTTP " + resp.statusCode(), resp.statusCode());
		} catch (Exception e) {
			return Result.err(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
		}
	}

	private Result<ClanSession> getReq(String path) {
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
		// Heartbeat hint for hubs running without strict auth (offline-mode servers):
		// lets a plain poll refresh lastSeen, so claims stop timing out mid-game.
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
					// since-poll: nothing moved on the hub. Not an error, not a snapshot.
					return Result.err("unchanged", 304);
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
		return Result.err(msg, code);
	}

	public static final class Result<T> {
		public final boolean ok;
		public final @Nullable T value;
		public final @Nullable String error;
		/** HTTP status, or 0 when the request never produced a response (network error). */
		public final int status;

		private Result(boolean ok, @Nullable T value, @Nullable String error, int status) {
			this.ok = ok;
			this.value = value;
			this.error = error;
			this.status = status;
		}

		public static <T> Result<T> ok(T v) {
			return new Result<>(true, v, null, 200);
		}

		public static <T> Result<T> err(String e) {
			return new Result<>(false, null, e, 0);
		}

		public static <T> Result<T> err(String e, int status) {
			return new Result<>(false, null, e, status);
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
