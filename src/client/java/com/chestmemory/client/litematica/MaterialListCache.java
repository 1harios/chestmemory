package com.chestmemory.client.litematica;

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
 * Litematica has none. It outlives a single gather on purpose: Litematica only recreates a
 * list when the player opens it by hand, so dropping the copy when a gather finished left no
 * list from either side and the «Сбор» button could not be pressed again. It is replaced when
 * the schematic changes, so two builds are never mixed — the same hazard the
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
	/**
	 * True while a gather is running.
	 * <p>
	 * No longer gates serving the copy — see {@link #setArmed(boolean)} — but still records
	 * whether a gather is in progress for callers that ask.
	 */
	private static boolean armed;
	/**
	 * Dimension the list was captured in.
	 * <p>
	 * Litematica never recreates a material list on its own — only the player does, from its
	 * menu. So "Litematica has no list" stays true after coming home, and cannot be used to
	 * tell whether we are away from the schematic. The captured dimension can.
	 */
	private static @Nullable String cachedDimension;

	private MaterialListCache() {
	}

	/**
	 * Called by the gather session when it starts or stops.
	 * <p>
	 * Note what this does <em>not</em> do: it no longer throws the copy away when a gather
	 * stops. See the note in the body.
	 */
	public static void setArmed(boolean on) {
		armed = on;
		// Deliberately does NOT clear on disarm any more. Litematica drops its list on every
		// world load and only recreates it when the player opens it by hand, so after
		// finishing a gather and walking through a portal there was no list from either side —
		// hasActiveMaterialList() went false and the «Сбор» button could not be pressed again.
		// The copy is kept until the schematic changes; it describes the build, not the gather.
	}

	public static void clear() {
		cached = null;
		cachedListName = null;
		cachedDimension = null;
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
		return resolve(live, listName, null);
	}

	/**
	 * @param dimension dimension the player is standing in right now, or null when unknown
	 */
	public static List<LitematicaCompat.MaterialNeed> resolve(
		List<LitematicaCompat.MaterialNeed> live,
		@Nullable String listName,
		@Nullable String dimension
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
			if (dimension != null) {
				cachedDimension = dimension;
			}
			return live;
		}
		if (cached == null) {
			return live;
		}
		// Litematica has no list but a gather is running: serve the copy so a portal trip
		// does not look like a finished build.
		return cached;
	}

	/**
	 * True when we are away from the dimension the schematic's list was captured in.
	 * <p>
	 * This — not "Litematica has no list" — is what the HUD warning must key on. Litematica
	 * only ever recreates a list when the player opens it, so an empty live list stays empty
	 * after coming home and the warning would never clear.
	 *
	 * @param dimension where the player is standing now
	 */
	public static boolean isAwayFromSchematic(@Nullable String dimension) {
		if (cached == null || cachedDimension == null || dimension == null) {
			return false;
		}
		return !cachedDimension.equals(dimension);
	}

	/** Dimension the cached list was captured in, or null. */
	public static @Nullable String cachedDimension() {
		return cachedDimension;
	}

	/** Schematic name the cache belongs to, for the HUD. */
	public static @Nullable String cachedListName() {
		return cachedListName;
	}

	/** Visible for tests: true when a gather has armed the cache. */
	static boolean isArmed() {
		return armed;
	}
}
