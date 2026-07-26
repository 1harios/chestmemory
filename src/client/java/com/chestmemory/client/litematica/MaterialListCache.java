package com.chestmemory.client.litematica;

import com.chestmemory.ChestMemoryMod;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Keeps the schematic's material list alive across a dimension change.
 * <p>
 * The list belongs to Litematica, and Litematica drops it when a new world loads: its
 * {@code WorldLoadListener} reaches {@code DataManager.clear()}, which calls
 * {@code setMaterialList(null)}. Walking through a Nether portal is a world load, so the
 * list is gone the moment you step through — and a gather session that asked for it got an
 * empty list and reported that there was nothing left to collect.
 * <p>
 * That matters on this server because the clan walks <em>through</em> the Nether to move
 * between worlds. Gathering had to survive the trip.
 * <p>
 * The cache mirrors the last non-empty list Litematica reported and serves it while
 * Litematica has none. It is not a permanent store: it is dropped when the session ends or
 * the schematic changes, so two builds can never be mixed — the same hazard the
 * {@code snapshotListName} reset already guards against.
 * <p>
 * <b>Known limitation.</b> While Litematica has no list, it also stops counting blocks you
 * place, so the cached totals stand still until you return to the schematic's world. Stale
 * counts for the length of a Nether trip beat an empty list that hides the whole build.
 */
public final class MaterialListCache {
	/** Last non-empty list seen from Litematica, or null when nothing has been cached. */
	private static @Nullable List<LitematicaCompat.MaterialNeed> cached;
	/** Name of the schematic the cache belongs to, so a different build never reuses it. */
	private static @Nullable String cachedListName;
	/** True while a gather session wants the cache kept alive. */
	private static boolean armed;

	private MaterialListCache() {
	}

	/**
	 * Called by the gather session when it starts or stops.
	 * <p>
	 * The cache only serves a running session. Outside one, an empty list from Litematica is
	 * the honest answer — the user closed the material list and expects the panel to be empty.
	 */
	public static void setArmed(boolean on) {
		armed = on;
		if (!on) {
			clear();
		}
	}

	public static void clear() {
		cached = null;
		cachedListName = null;
	}

	/**
	 * Latest material list, falling back to the cached copy when Litematica has dropped its
	 * own. Also refreshes the cache whenever Litematica reports a real list.
	 *
	 * @param live     what Litematica returned right now (possibly empty)
	 * @param listName the active schematic name, or null when Litematica has no list
	 */
	public static List<LitematicaCompat.MaterialNeed> resolve(
		List<LitematicaCompat.MaterialNeed> live,
		@Nullable String listName
	) {
		if (!live.isEmpty()) {
			// A different schematic must never inherit the previous one's cache.
			if (listName != null && cachedListName != null && !listName.equals(cachedListName)) {
				clear();
			}
			cached = List.copyOf(live);
			if (listName != null) {
				cachedListName = listName;
			}
			return live;
		}
		if (!armed || cached == null) {
			return live;
		}
		// Litematica has no list but a gather is running: serve the copy so a portal trip
		// does not look like a finished build.
		return cached;
	}

	/** True when the list currently being served came from the cache, not from Litematica. */
	public static boolean isServingCache(List<LitematicaCompat.MaterialNeed> live) {
		return armed && live.isEmpty() && cached != null && !cached.isEmpty();
	}

	/** Schematic name the cache belongs to, for the HUD. */
	public static @Nullable String cachedListName() {
		return cachedListName;
	}

	static void debugLog(String what) {
		ChestMemoryMod.LOGGER.debug("Material list cache: {}", what);
	}
}
