package com.chestmemory.client.util;

/**
 * The string half of legacy colour handling: the sixteen codes, the {@code §#RRGGBB}
 * extension, and how to walk a name that mixes them.
 * <p>
 * Split out of {@link LegacyText} on purpose. That class has to build Minecraft components,
 * which means it cannot be unit-tested without the game on the classpath — and this is the
 * part that most needs testing, because every one of these methods rewrites a player-visible
 * name. A malformed marker, a literal "§#" typed into an anvil, a truncated code at the end
 * of a string: each must pass through without eating characters.
 */
public final class LegacyColors {
	public static final char PREFIX = '§';
	/** Length of a "§#RRGGBB" marker. */
	public static final int MARKER_LEN = 8;

	/** The classic 16 colours, index = legacy code value (0–15). */
	static final int[] LEGACY_RGB = {
		0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA,
		0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF
	};
	static final char[] LEGACY_CODE = {
		'0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'
	};

	private LegacyColors() {
	}

	/** «§#1AFF3C» for a colour outside the sixteen, «§a» for one of them. */
	public static String code(int rgb) {
		int c = rgb & 0xFFFFFF;
		for (int i = 0; i < LEGACY_RGB.length; i++) {
			if (LEGACY_RGB[i] == c) {
				return String.valueOf(PREFIX) + LEGACY_CODE[i];
			}
		}
		return String.format(java.util.Locale.ROOT, "%c#%06X", PREFIX, c);
	}

	/**
	 * RGB of the marker starting at {@code at}, or -1 when there is no complete one there.
	 * <p>
	 * -1 rather than an exception: "§#" is a perfectly legal thing to name an item, and a
	 * name ending mid-marker must survive rather than throw while a list is being drawn.
	 */
	public static int markerAt(String s, int at) {
		if (s == null || at < 0 || at + MARKER_LEN > s.length()) {
			return -1;
		}
		if (s.charAt(at) != PREFIX || s.charAt(at + 1) != '#') {
			return -1;
		}
		int rgb = 0;
		for (int i = at + 2; i < at + MARKER_LEN; i++) {
			int d = Character.digit(s.charAt(i), 16);
			if (d < 0) {
				return -1;
			}
			rgb = (rgb << 4) | d;
		}
		return rgb;
	}

	/** The nearest of the sixteen codes to an RGB colour. */
	public static char nearestCode(int rgb) {
		int c = rgb & 0xFFFFFF;
		int best = 0;
		long bestDist = Long.MAX_VALUE;
		for (int i = 0; i < LEGACY_RGB.length; i++) {
			long dist = distance(c, LEGACY_RGB[i]);
			if (dist < bestDist) {
				bestDist = dist;
				best = i;
			}
		}
		return LEGACY_CODE[best];
	}

	/**
	 * Replace every {@code §#RRGGBB} with the nearest plain legacy code.
	 * <p>
	 * For the call sites that pass names around as strings — chat, CSV, status text — where
	 * the font renderer would print the marker itself instead of colouring anything.
	 */
	public static String downgrade(String s) {
		if (s == null || s.isEmpty() || s.indexOf(PREFIX) < 0) {
			return s == null ? "" : s;
		}
		StringBuilder out = new StringBuilder(s.length());
		int i = 0;
		while (i < s.length()) {
			int rgb = markerAt(s, i);
			if (rgb >= 0) {
				out.append(PREFIX).append(nearestCode(rgb));
				i += MARKER_LEN;
				continue;
			}
			out.append(s.charAt(i));
			i++;
		}
		return out.toString();
	}

	/**
	 * The name with all styling removed — both marker form and plain codes.
	 * <p>
	 * Vanilla's own stripper only knows the plain codes, so on a marker it would delete the
	 * "§" and leave "#1AFF3C" sitting in the middle of a searchable name.
	 */
	public static String strip(String s) {
		if (s == null || s.isEmpty() || s.indexOf(PREFIX) < 0) {
			return s == null ? "" : s;
		}
		StringBuilder out = new StringBuilder(s.length());
		int i = 0;
		while (i < s.length()) {
			if (markerAt(s, i) >= 0) {
				i += MARKER_LEN;
				continue;
			}
			char c = s.charAt(i);
			if (c == PREFIX && i + 1 < s.length() && isCode(s.charAt(i + 1))) {
				i += 2;
				continue;
			}
			out.append(c);
			i++;
		}
		return out.toString();
	}

	/** True for the plain legacy codes: the sixteen colours, the modifiers, and reset. */
	public static boolean isCode(char c) {
		char lower = Character.toLowerCase(c);
		for (char code : LEGACY_CODE) {
			if (code == lower) {
				return true;
			}
		}
		return lower == 'k' || lower == 'l' || lower == 'm'
			|| lower == 'n' || lower == 'o' || lower == 'r';
	}

	/** RGB of one of the sixteen codes, or -1 when the character is not a colour. */
	public static int rgbOfCode(char c) {
		char lower = Character.toLowerCase(c);
		for (int i = 0; i < LEGACY_CODE.length; i++) {
			if (LEGACY_CODE[i] == lower) {
				return LEGACY_RGB[i];
			}
		}
		return -1;
	}

	private static long distance(int a, int b) {
		long dr = ((a >> 16) & 0xFF) - ((b >> 16) & 0xFF);
		long dg = ((a >> 8) & 0xFF) - ((b >> 8) & 0xFF);
		long db = (a & 0xFF) - (b & 0xFF);
		return dr * dr + dg * dg + db * db;
	}
}
