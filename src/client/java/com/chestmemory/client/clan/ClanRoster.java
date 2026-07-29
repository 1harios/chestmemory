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
	public record Entry(String code, String label, int delivered, int need, String host, long seenAt) {
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
			// Two fields were added later, so anything shorter is an older entry and simply
			// has no host or timestamp — reading it must not throw those entries away.
			String[] parts = raw.split("\\|", 6);
			String code = normalize(parts[0]);
			if (code.isEmpty()) {
				continue;
			}
			int delivered = parts.length > 2 ? parseInt(parts[2]) : 0;
			int need = parts.length > 3 ? parseInt(parts[3]) : 0;
			String host = parts.length > 4 ? parts[4] : "";
			long seen = parts.length > 5 ? parseLong(parts[5]) : 0L;
			known.put(code, new Entry(
				code, parts.length > 1 ? parts[1] : "", delivered, need, host, seen
			));
		}
	}

	private static long parseLong(String s) {
		try {
			return Math.max(0L, Long.parseLong(s.trim()));
		} catch (NumberFormatException e) {
			return 0L;
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
			out.add(e.code() + "|" + e.label().replace('|', ' ') + "|" + e.delivered()
				+ "|" + e.need() + "|" + e.host().replace('|', ' ') + "|" + e.seenAt());
		}
		ModSettings.get().setClanKnownCodes(out);
	}

	/** Remember a gather, or refresh what we know about it. Newest entries sort first. */
	public static void remember(@Nullable String code, @Nullable String label, int delivered, int need) {
		remember(code, label, delivered, need, null);
	}

	/** Remember a gather along with who runs it, so the list can say more than a code. */
	public static void remember(
		@Nullable String code, @Nullable String label, int delivered, int need, @Nullable String host
	) {
		String key = normalize(code);
		if (key.isEmpty()) {
			return;
		}
		ensureLoaded();
		// get, NOT remove: LinkedHashMap leaves an existing key where it is on re-put, but
		// removing it first moves the entry to the end. That is what made the gather you had
		// just switched to drop to the bottom of the list — and it moved again on the next
		// poll, because polling refreshes progress through this same method. Rows the player
		// is clicking must not reorder underneath the click.
		Entry prev = known.get(key);
		String name = label != null && !label.isBlank()
			? label
			: (prev != null ? prev.label() : "");
		// Keep what we knew when the new call cannot say: a poll refreshing progress must
		// not erase the host recorded when the gather was joined.
		String owner = host != null && !host.isBlank()
			? host
			: (prev != null ? prev.host() : "");
		known.put(key, new Entry(key, name, delivered, need, owner, System.currentTimeMillis()));
		// The list is in the order gathers were first met, so eviction takes the one met
		// longest ago — the least likely to be wanted, and the only one whose removal cannot
		// shuffle the rows above it.
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

	/**
	 * Known gathers in the order they were first met, oldest first.
	 * <p>
	 * Stable on purpose: switching gathers and polling both refresh entries, and an order
	 * that tracked "most recently touched" moved the active row to the bottom every few
	 * seconds while the player was reading it.
	 */
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
