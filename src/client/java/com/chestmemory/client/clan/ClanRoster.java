package com.chestmemory.client.clan;

import com.chestmemory.client.data.ModSettings;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The gathers this player knows about, and which one is currently active.
 * <p>
 * A clan runs more than one build at a time: a code for the farm, another for the house. The
 * hub already supports that — sessions live in a map keyed by code, each with its own
 * materials, warehouse and progress — but the client only ever remembered one, so joining the
 * second gather meant losing the first.
 * <p>
 * <b>Exactly one gather is active.</b> That is a deliberate constraint, not a simplification:
 * <ul>
 *   <li>Polling is per session. The client polls every 3s, so following every known gather
 *       would multiply request volume by the number of gathers — and the Python hub counts a
 *       session poll in its tightest rate-limit bucket.</li>
 *   <li>The warehouse is one set of marked chests per world profile. Two gathers glowing at
 *       once would mix the farm's drop-off with the house's.</li>
 *   <li>The HUD names one target. "What am I collecting" has to have one answer.</li>
 * </ul>
 * Codes are remembered in settings, so a relog does not lose the list.
 */
public final class ClanRoster {
	/**
	 * Cap on remembered codes. Generous for a clan, small enough that a stale list cannot
	 * grow without bound if gathers are abandoned rather than closed.
	 */
	public static final int MAX_REMEMBERED = 12;

	/** code (upper case) → last known label, newest first. */
	private static final Map<String, Entry> known = new LinkedHashMap<>();
	private static boolean loaded;

	private ClanRoster() {
	}

	/**
	 * One remembered gather.
	 *
	 * @param code     session code, upper case
	 * @param label    schematic name last seen for it, or empty
	 * @param delivered items delivered as of the last time we saw this session
	 * @param need     items required as of the last time we saw this session
	 */
	public record Entry(String code, String label, int delivered, int need) {
		public int percent() {
			return need > 0 ? (int) (100L * delivered / need) : 0;
		}
	}

	private static void ensureLoaded() {
		if (loaded) {
			return;
		}
		loaded = true;
		for (String raw : ModSettings.get().clanKnownCodes()) {
			// Stored as "CODE|label|delivered|need" — a flat string keeps the settings file
			// readable and avoids a schema migration for a list of four fields.
			if (raw == null || raw.isBlank()) {
				continue;
			}
			String[] parts = raw.split("\\|", 4);
			String code = normalize(parts[0]);
			if (code.isEmpty()) {
				continue;
			}
			int delivered = parts.length > 2 ? parseInt(parts[2]) : 0;
			int need = parts.length > 3 ? parseInt(parts[3]) : 0;
			known.put(code, new Entry(code, parts.length > 1 ? parts[1] : "", delivered, need));
		}
	}

	private static int parseInt(String s) {
		try {
			return Math.max(0, Integer.parseInt(s.trim()));
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	static String normalize(@Nullable String code) {
		return code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
	}

	private static void persist() {
		List<String> out = new ArrayList<>(known.size());
		for (Entry e : known.values()) {
			out.add(e.code() + "|" + e.label().replace('|', ' ') + "|" + e.delivered() + "|" + e.need());
		}
		ModSettings.get().setClanKnownCodes(out);
	}

	/** Remember a gather, or refresh what we know about it. Newest entries sort first. */
	public static void remember(@Nullable String code, @Nullable String label, int delivered, int need) {
		String key = normalize(code);
		if (key.isEmpty()) {
			return;
		}
		ensureLoaded();
		Entry prev = known.remove(key);
		String name = label != null && !label.isBlank()
			? label
			: (prev != null ? prev.label() : "");
		known.put(key, new Entry(key, name, delivered, need));
		// Re-insert to the front by rebuilding: LinkedHashMap keeps insertion order, and the
		// most recently touched gather is the one the player cares about.
		while (known.size() > MAX_REMEMBERED) {
			String oldest = known.keySet().iterator().next();
			known.remove(oldest);
		}
		persist();
	}

	/** Forget a gather — left, or closed by the host. */
	public static void forget(@Nullable String code) {
		String key = normalize(code);
		if (key.isEmpty()) {
			return;
		}
		ensureLoaded();
		if (known.remove(key) != null) {
			persist();
		}
	}

	/** Known gathers, most recently touched last (matches insertion order). */
	public static List<Entry> all() {
		ensureLoaded();
		return List.copyOf(known.values());
	}

	public static boolean isKnown(@Nullable String code) {
		ensureLoaded();
		return known.containsKey(normalize(code));
	}

	public static int size() {
		ensureLoaded();
		return known.size();
	}

	/** Drop everything — used when switching servers, where codes do not carry over. */
	public static void clearAll() {
		ensureLoaded();
		if (known.isEmpty()) {
			return;
		}
		known.clear();
		persist();
	}
}
