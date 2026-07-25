package com.chestmemory.client.clan;

import com.chestmemory.ChestMemoryMod;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Hub address and invite token baked into the build.
 * <p>
 * Members should only ever type a session code. Making each of them paste a URL and a
 * token was busywork that also spread the token by hand — the clan owner builds the jar
 * once with their hub in it and hands that jar out.
 * <p>
 * Read from {@code assets/chestmemory/clan_hub.json}, which is generated at build time
 * from {@code clan_hub_url} / {@code clan_hub_token} in gradle.properties (or
 * {@code -Pclan_hub_url=...} on the command line). When those are empty the file holds
 * blanks and the settings screen keeps the manual fields, so a public build works as
 * before.
 * <p>
 * Note the token in the jar is only an invite — identity comes from the Mojang handshake
 * in {@link ClanAuth}, so someone extracting it still cannot act as another player.
 */
public final class ClanDefaults {
	private static final Identifier FILE = ChestMemoryMod.id("clan_hub.json");
	private static final Gson GSON = new Gson();

	private static boolean loaded;
	private static String url = "";
	private static String token = "";

	private ClanDefaults() {
	}

	/** Baked hub URL, or empty when this build has none. */
	public static String url() {
		ensureLoaded();
		return url;
	}

	/** Baked invite token, or empty. */
	public static String token() {
		ensureLoaded();
		return token;
	}

	/** True when this build ships a hub address, so the UI can hide the manual fields. */
	public static boolean hasBakedHub() {
		return !url().isEmpty();
	}

	private static synchronized void ensureLoaded() {
		if (loaded) {
			return;
		}
		loaded = true;
		Minecraft mc = Minecraft.getInstance();
		if (mc == null || mc.getResourceManager() == null) {
			return;
		}
		try {
			var resource = mc.getResourceManager().getResource(FILE);
			if (resource.isEmpty()) {
				return;
			}
			try (InputStream in = resource.get().open();
				 InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
				JsonObject o = GSON.fromJson(reader, JsonObject.class);
				if (o == null) {
					return;
				}
				url = trimUrl(readString(o, "url"));
				token = readString(o, "token");
			}
			if (!url.isEmpty()) {
				ChestMemoryMod.LOGGER.info("Clan hub baked into build: {}", url);
			}
		} catch (Exception e) {
			// A malformed or absent file just means "no baked hub" — never fatal.
			ChestMemoryMod.LOGGER.debug("No baked clan hub: {}", e.toString());
		}
	}

	private static String readString(JsonObject o, String key) {
		if (!o.has(key) || o.get(key).isJsonNull()) {
			return "";
		}
		String v = o.get(key).getAsString().trim();
		// The template ships placeholders; treat them as "not configured".
		return v.startsWith("${") ? "" : v;
	}

	private static String trimUrl(String raw) {
		String u = raw;
		while (u.endsWith("/")) {
			u = u.substring(0, u.length() - 1);
		}
		return u;
	}

	/** Effective hub URL: the player's own setting wins, otherwise the baked one. */
	public static String effectiveUrl(String configured) {
		return !configured.isEmpty() ? configured : url();
	}

	public static String effectiveToken(String configured) {
		return !configured.isEmpty() ? configured : token();
	}

	/** Only for tests / reload. */
	static synchronized void reset() {
		loaded = false;
		url = "";
		token = "";
	}

	static @Nullable String debugState() {
		return loaded ? url + "|" + (token.isEmpty() ? "no-token" : "token") : null;
	}
}
