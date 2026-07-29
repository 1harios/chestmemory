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
	/**
	 * Storage profile the list was captured on — {@code ChestMemoryStorage.liveWorldId()},
	 * keyed on the server address / singleplayer world.
	 * <p>
	 * The dimension alone cannot tell two servers apart: every server calls its Overworld
	 * {@code minecraft:overworld}, so a gather parked on server A and resumed on server B
	 * compared equal — Litematica had no list on B either, the cache served A's bill of
	 * materials, and highlights and routes were rebuilt from B's chest memory against a
	 * build that is not there. The profile id is the same boundary the chest memory itself
	 * files records under, so a mismatch is exactly "these are not the schematic's chests".
	 */
	private static @Nullable String cachedWorldId;

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
		cachedWorldId = null;
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
		return resolve(live, listName, dimension, null);
	}

	/**
	 * @param worldId storage profile the player is on right now, or null when unknown
	 */
	public static List<LitematicaCompat.MaterialNeed> resolve(
		List<LitematicaCompat.MaterialNeed> live,
		@Nullable String listName,
		@Nullable String dimension,
		@Nullable String worldId
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
			if (worldId != null) {
				cachedWorldId = worldId;
			}
			return live;
		}
		if (cached == null) {
			return live;
		}
		if (isDifferentWorld(worldId)) {
			// Joining a DIFFERENT server lands here too: Litematica has no list there
			// either, so without this check the copy captured on server A was served on
			// server B and the parked gather resumed against the wrong world's chests.
			// Refuse to serve, but keep the copy — landing back on the original profile
			// resumes the parked build, which is the whole point of park(). Unknown ids
			// fail open: the destructive mistake is emptying a parked gather mid-portal.
			return live;
		}
		// Litematica has no list but a gather is running: serve the copy so a portal trip
		// does not look like a finished build.
		return cached;
	}

	/** True only when both profile ids are known and disagree — unknown never counts. */
	private static boolean isDifferentWorld(@Nullable String worldId) {
		return worldId != null && cachedWorldId != null && !worldId.equals(cachedWorldId);
	}

	/**
	 * Record that Litematica's own (scanned) list reports the build complete.
	 * <p>
	 * Keeping the old copy after that would resurrect it: the next world load drops
	 * Litematica's list as always, {@link #resolve} would fall back to the cached bill, and
	 * a finished build would come back from a Nether trip demanding every material again.
	 * An <em>empty</em> cached list — as opposed to a cleared one — keeps the schematic's
	 * name for the HUD caption while making "nothing left" the answer that gets served.
	 */
	public static void noteFinished(@Nullable String listName, @Nullable String dimension, @Nullable String worldId) {
		if (listName != null && cachedListName != null && !listName.equals(cachedListName)) {
			clear();
		}
		cached = List.of();
		if (listName != null) {
			cachedListName = listName;
		}
		if (dimension != null) {
			cachedDimension = dimension;
		}
		if (worldId != null) {
			cachedWorldId = worldId;
		}
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
		return isAwayFromSchematic(dimension, null);
	}

	/**
	 * @param worldId storage profile the player is on right now, or null when unknown
	 */
	public static boolean isAwayFromSchematic(@Nullable String dimension, @Nullable String worldId) {
		if (cached == null) {
			return false;
		}
		if (isDifferentWorld(worldId)) {
			// Another server entirely is "away" no matter what the dimension says —
			// its Overworld carries the same "minecraft:overworld" id as the schematic's.
			return true;
		}
		if (cachedDimension == null || dimension == null) {
			return false;
		}
		return !cachedDimension.equals(dimension);
	}

	/** Dimension the cached list was captured in, or null. */
	public static @Nullable String cachedDimension() {
		return cachedDimension;
	}

	/** Storage profile the cached list was captured on, or null. */
	public static @Nullable String cachedWorldId() {
		return cachedWorldId;
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
