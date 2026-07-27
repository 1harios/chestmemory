package com.chestmemory.client.data;

import org.jspecify.annotations.Nullable;

import java.util.Locale;

/**
 * Composition and comparison rules for world tags — the short opaque strings that tell two
 * worlds apart when they report the same dimension id (multiworld servers, proxied networks).
 * <p>
 * Two current formats, by strength of the underlying signal:
 * <ul>
 *   <li>{@code w<hex>} — derived from the hashed seed the server sends in every login and
 *       respawn packet. Per-world, identical for every player in that world, and stable for
 *       as long as the world keeps its seed. The primary signal.</li>
 *   <li>{@code p<hex>} — derived from the world's spawn point, used only when the server
 *       sends no usable seed (some anti-seed-cracking setups zero it). Weaker: an admin can
 *       move the spawn.</li>
 * </ul>
 * Anything else — including the legacy {@code s<x>_<y>_<z>} spawn tags written by earlier
 * versions — is treated as <b>unknown</b>, never as a distinct world. Unknown must always
 * fail open: the destructive failure mode is hiding or deleting a chest that is perfectly
 * fine, so "cannot prove different" is the only safe reading.
 * <p>
 * Deliberately free of Minecraft imports so the rules stay unit-testable.
 */
public final class WorldTags {
	/** Tag prefix for hashed-seed identity. */
	public static final char SEED_PREFIX = 'w';
	/** Tag prefix for spawn-point identity (fallback). */
	public static final char SPAWN_PREFIX = 'p';

	private WorldTags() {
	}

	/**
	 * Tag for a world's hashed seed, or null when the server sent nothing usable.
	 * A literal 0 is what seed-hiding setups send, so it means "unknown", not "world 0".
	 */
	public static @Nullable String seedTag(long hashedSeed) {
		if (hashedSeed == 0L) {
			return null;
		}
		return SEED_PREFIX + hex32(hashedSeed);
	}

	/** Tag for a world's explicitly-set spawn point (fallback when no seed is available). */
	public static String spawnTag(int x, int y, int z) {
		long h = 1125899906842597L;
		h = 31 * h + x;
		h = 31 * h + y;
		h = 31 * h + z;
		return SPAWN_PREFIX + hex32(h);
	}

	private static String hex32(long value) {
		return String.format(Locale.ROOT, "%08x", (int) (value ^ (value >>> 32)));
	}

	/** True for tags this version writes ({@code w…} / {@code p…} + hex). */
	public static boolean isCurrentFormat(@Nullable String tag) {
		if (tag == null || tag.length() < 2 || tag.length() > 17) {
			return false;
		}
		char prefix = tag.charAt(0);
		if (prefix != SEED_PREFIX && prefix != SPAWN_PREFIX) {
			return false;
		}
		for (int i = 1; i < tag.length(); i++) {
			char c = tag.charAt(i);
			boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
			if (!hex) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Tag as loaded from disk, normalized: legacy or malformed formats become null (unknown).
	 * Legacy {@code s…} tags compared against the new format would read as "different world"
	 * for a chest that is right here — exactly the false positive this migration prevents.
	 */
	public static @Nullable String sanitize(@Nullable String tag) {
		return isCurrentFormat(tag) ? tag : null;
	}

	/**
	 * True only when two tags are known to describe different worlds.
	 * <p>
	 * Fails open in every uncertain case: null/blank (unknown), legacy format (unknown),
	 * and mixed prefixes (a seed tag and a spawn tag are different <i>signals</i>, not
	 * provably different <i>worlds</i>). Callers may use this to filter what is shown,
	 * but never as a licence to delete.
	 */
	public static boolean provablyDifferent(@Nullable String a, @Nullable String b) {
		if (a == null || a.isBlank() || b == null || b.isBlank()) {
			return false;
		}
		if (!isCurrentFormat(a) || !isCurrentFormat(b)) {
			return false;
		}
		if (a.charAt(0) != b.charAt(0)) {
			return false;
		}
		return !a.equals(b);
	}
}
