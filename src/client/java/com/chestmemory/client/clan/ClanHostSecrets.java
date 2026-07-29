package com.chestmemory.client.clan;

import com.chestmemory.client.data.ModSettings;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The host secrets this player holds, one per gather they created.
 * <p>
 * A host proves itself to the hub in one of two ways: a Mojang-verified identity whose
 * uuid matches the gather's host, or the secret the hub issued when the gather was
 * created. The second exists because an offline-mode launcher can never complete the
 * Mojang handshake, which used to leave such a host with no host tools at all — no
 * rename, no kick, no claim reset, no excluding materials, no closing the gather.
 * <p>
 * The uuid could not stand in for the handshake: it is public in every session snapshot,
 * so anybody who read the roster could replay it. A secret can, because it is issued
 * once, to the creator, and the hub strips it from every later snapshot.
 * <p>
 * Kept in settings rather than memory so host tools survive a relog — losing the secret
 * means losing control of your own gather until you create a new one.
 */
public final class ClanHostSecrets {
	/**
	 * Cap on remembered secrets. Matches {@link ClanRoster#MAX_REMEMBERED}: a secret is
	 * only useful for a gather still in the roster, so remembering more is dead weight.
	 */
	public static final int MAX_REMEMBERED = ClanRoster.MAX_REMEMBERED;

	/** code (upper case) → secret. */
	private static final Map<String, String> secrets = new LinkedHashMap<>();
	private static boolean loaded;

	private ClanHostSecrets() {
	}

	private static void ensureLoaded() {
		if (loaded) {
			return;
		}
		loaded = true;
		for (String raw : ModSettings.get().clanHostSecrets()) {
			if (raw == null || raw.isBlank()) {
				continue;
			}
			// "CODE|secret" — the same flat encoding the known-codes list uses, so the
			// settings file stays readable and no schema migration is needed.
			int bar = raw.indexOf('|');
			if (bar <= 0 || bar == raw.length() - 1) {
				continue;
			}
			String code = ClanRoster.normalize(raw.substring(0, bar));
			String secret = raw.substring(bar + 1).trim();
			if (!code.isEmpty() && !secret.isEmpty()) {
				secrets.put(code, secret);
			}
		}
	}

	private static void persist() {
		List<String> out = new ArrayList<>(secrets.size());
		for (Map.Entry<String, String> e : secrets.entrySet()) {
			out.add(e.getKey() + "|" + e.getValue());
		}
		ModSettings.get().setClanHostSecrets(out);
	}

	/**
	 * Remember the secret for a gather we just created.
	 * <p>
	 * Silently ignores a blank secret: a hub older than this feature simply does not send
	 * one, and overwriting a good secret with nothing would cost the host their tools.
	 */
	public static void remember(@Nullable String code, @Nullable String secret) {
		String key = ClanRoster.normalize(code);
		if (key.isEmpty() || secret == null || secret.isBlank()) {
			return;
		}
		ensureLoaded();
		secrets.remove(key);
		secrets.put(key, secret.trim());
		while (secrets.size() > MAX_REMEMBERED) {
			secrets.remove(secrets.keySet().iterator().next());
		}
		persist();
	}

	/** The secret for this gather, or null when we did not create it. */
	public static @Nullable String get(@Nullable String code) {
		String key = ClanRoster.normalize(code);
		if (key.isEmpty()) {
			return null;
		}
		ensureLoaded();
		return secrets.get(key);
	}

	public static boolean has(@Nullable String code) {
		return get(code) != null;
	}

	/** Forget a gather's secret — it was closed, or we left it. */
	public static void forget(@Nullable String code) {
		String key = ClanRoster.normalize(code);
		if (key.isEmpty()) {
			return;
		}
		ensureLoaded();
		if (secrets.remove(key) != null) {
			persist();
		}
	}

	/** Drop everything — used when switching servers, where codes do not carry over. */
	public static void clearAll() {
		ensureLoaded();
		if (secrets.isEmpty()) {
			return;
		}
		secrets.clear();
		persist();
	}

	public static int size() {
		ensureLoaded();
		return secrets.size();
	}
}
