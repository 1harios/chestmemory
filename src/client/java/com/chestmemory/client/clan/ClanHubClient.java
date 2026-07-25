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
			return Result.err("HTTP " + resp.statusCode());
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
	}

	private static String enc(String code) {
		return URLEncoder.encode(code, StandardCharsets.UTF_8);
	}

	private Result<ClanSession> parse(HttpResponse<String> resp) {
		int code = resp.statusCode();
		String body = resp.body() == null ? "" : resp.body();
		if (code >= 200 && code < 300) {
			try {
				ClanSession s = GSON.fromJson(body, ClanSession.class);
				if (s == null || s.code == null || s.code.isBlank()) {
					return Result.err("bad response");
				}
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
		return Result.err(msg);
	}

	public static final class Result<T> {
		public final boolean ok;
		public final @Nullable T value;
		public final @Nullable String error;

		private Result(boolean ok, @Nullable T value, @Nullable String error) {
			this.ok = ok;
			this.value = value;
			this.error = error;
		}

		public static <T> Result<T> ok(T v) {
			return new Result<>(true, v, null);
		}

		public static <T> Result<T> err(String e) {
			return new Result<>(false, null, e);
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
