package com.chestmemory.client.litematica;

import net.fabricmc.loader.api.FabricLoader;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * Safe façade — never touches Litematica classes unless the mod is installed.
 */
public final class LitematicaAccess {
	/**
	 * Snapshot TTL for {@link #missingMaterials()}.
	 * <p>
	 * One HUD refresh used to rebuild the material list once per material row —
	 * remainingNeed re-fetched it for every entry, then the panel and rankPhase did it
	 * again — O(E²) rebuilds, each one walking the player inventory per entry and
	 * re-mutating Litematica's own entries through updateAvailableCounts. 250ms is far
	 * inside human reaction time but spans several 4Hz gather refreshes, so a whole
	 * refresh (and a whole panel rebuild) sees exactly one fetch.
	 * <p>
	 * Invalidation is time-only on purpose: everything the snapshot can go stale against
	 * (schematic switched, world joined, clan poll landed) self-heals on the next rebuild,
	 * and 250ms of lag on any of those is invisible next to the network round trips that
	 * cause them. Client-thread confined, like the rest of this class.
	 */
	private static final long SNAPSHOT_TTL_MS = 250;
	private static long snapshotAtMillis;
	private static List<LitematicaCompat.MaterialNeed> snapshotList = List.of();
	private static Map<String, LitematicaCompat.MaterialNeed> snapshotById = Map.of();

	private LitematicaAccess() {
	}

	public static boolean isAvailable() {
		// String literal — do not touch Litematica classes unless the mod is present
		return FabricLoader.getInstance().isModLoaded("litematica");
	}

	/**
	 * True when a material list is available — from Litematica, or from the cache while a
	 * gather survives a dimension change.
	 */
	public static boolean hasActiveMaterialList() {
		// A clan member who joined by code has materials — the hub sent them — but no
		// Litematica list of their own, because the schematic was opened by the host. Asking
		// only Litematica left them unable to open the gather at all.
		//
		// Keyed on "the gather defines materials", not on "something is still missing", so a
		// finished gather still opens instead of claiming there is no list.
		if (hasClanMaterials()) {
			return true;
		}
		if (!isAvailable()) {
			return false;
		}
		if (LitematicaCompat.hasActiveMaterialListSafe()) {
			return true;
		}
		return !missingMaterials().isEmpty();
	}

	/**
	 * Materials of the clan gather being followed, or null when there is no session.
	 * <p>
	 * This is what a member who joined by code has instead of a schematic: the host defined
	 * the build, the hub distributes it. Treated as a real material list so the whole gather
	 * flow works for them without Litematica having anything open.
	 */
	private static boolean hasClanMaterials() {
		var session = com.chestmemory.client.clan.ClanSessionManager.session();
		return session != null && !session.materials.isEmpty();
	}

	private static @Nullable List<LitematicaCompat.MaterialNeed> clanMaterials() {
		var session = com.chestmemory.client.clan.ClanSessionManager.session();
		if (session == null || session.materials.isEmpty()) {
			return null;
		}
		List<LitematicaCompat.MaterialNeed> out = new java.util.ArrayList<>(session.materials.size());
		for (var e : session.materials.entrySet()) {
			// total() carries the FULL clan need, netting deliveries is remainingNeed()'s
			// job: it already folds clanDelivered() into `covered` — the same single
			// subtraction its clanNeed() fallback applies when this list is absent. Emitting
			// need-minus-delivered here made it subtract twice: clan needs 100 glass, 40
			// delivered → HUD said 20 left, and at 50 delivered auto-advance called it done
			// and walked the member off an item that was half missing. Items the clan has
			// fully delivered still drop out, so a finished material never re-enters the queue.
			int remaining = session.remaining(e.getKey());
			if (remaining > 0) {
				out.add(new LitematicaCompat.MaterialNeed(
					e.getKey(), Math.max(0, e.getValue().need), remaining, 0
				));
			}
		}
		return out.isEmpty() ? null : out;
	}

	/**
	 * Name of the active schematic. Falls back to the cached name, so the HUD keeps its
	 * caption instead of blanking out mid-portal.
	 */
	public static @Nullable String activeListName() {
		// No early return on isAvailable(): a clan gather has a name even with no Litematica.
		String live = isAvailable() ? LitematicaCompat.getActiveListNameSafe() : null;
		if (live != null) {
			return live;
		}
		String cached = MaterialListCache.cachedListName();
		if (cached != null) {
			return cached;
		}
		var session = com.chestmemory.client.clan.ClanSessionManager.session();
		return session != null && !session.schemaName.isBlank() ? session.schemaName : null;
	}

	/**
	 * Schematic materials.
	 * <p>
	 * Litematica drops its list on every world load, so stepping through a Nether portal
	 * used to empty this and make a running gather look finished. The cache serves the last
	 * known list until Litematica has one again — see {@link MaterialListCache}.
	 * <p>
	 * Served from a short-lived snapshot — see {@link #SNAPSHOT_TTL_MS}. Callers must not
	 * mutate the returned list.
	 */
	public static List<LitematicaCompat.MaterialNeed> missingMaterials() {
		refreshSnapshotIfStale();
		return snapshotList;
	}

	/**
	 * The same snapshot keyed by item id, so a per-item need check is a map lookup instead
	 * of a scan of the whole list (which is how remainingNeed went quadratic). Callers must
	 * not mutate the returned map.
	 */
	public static Map<String, LitematicaCompat.MaterialNeed> missingMaterialsById() {
		refreshSnapshotIfStale();
		return snapshotById;
	}

	private static void refreshSnapshotIfStale() {
		long now = System.currentTimeMillis();
		if (snapshotAtMillis != 0 && now - snapshotAtMillis < SNAPSHOT_TTL_MS) {
			return;
		}
		snapshotAtMillis = now;
		List<LitematicaCompat.MaterialNeed> list = fetchMaterials();
		Map<String, LitematicaCompat.MaterialNeed> byId = new java.util.LinkedHashMap<>();
		for (LitematicaCompat.MaterialNeed n : list) {
			byId.putIfAbsent(n.itemId(), n);
		}
		snapshotList = List.copyOf(list);
		snapshotById = java.util.Collections.unmodifiableMap(byId);
	}

	/** The uncached fetch behind the snapshot — one Litematica read, one cache resolve. */
	private static List<LitematicaCompat.MaterialNeed> fetchMaterials() {
		if (!isAvailable()) {
			// No Litematica at all: a clan gather still has materials worth showing.
			List<LitematicaCompat.MaterialNeed> clanOnly = clanMaterials();
			return clanOnly != null ? clanOnly : List.of();
		}
		List<LitematicaCompat.MaterialNeed> live = LitematicaCompat.getMissingMaterialsSafe();
		String liveName = LitematicaCompat.getActiveListNameSafe();
		String dimension = currentDimension();
		String worldId = currentWorldId();
		if (live.isEmpty() && LitematicaCompat.isListFinishedSafe()) {
			// Litematica HAS its list and a completed count says nothing is missing. Without
			// this, resolve() below read the empty result as "list dropped on world load"
			// and served the cached copy — a finished build re-reported its full bill and
			// the panel offered a complete re-gather.
			MaterialListCache.noteFinished(liveName, dimension, worldId);
		}
		List<LitematicaCompat.MaterialNeed> resolved = MaterialListCache.resolve(
			live, liveName, dimension, worldId
		);
		if (!resolved.isEmpty()) {
			return resolved;
		}
		// Fall back to the gather's own materials, so a member who joined by code sees the
		// build even though the schematic lives on the host's client.
		List<LitematicaCompat.MaterialNeed> clan = clanMaterials();
		return clan != null ? clan : resolved;
	}

	/**
	 * True when the player is away from the dimension — or the server — the schematic list
	 * was captured in.
	 * <p>
	 * Keyed on the dimension rather than on "Litematica has no list": Litematica never
	 * recreates a list by itself, so the empty-list condition stays true after coming home
	 * and the HUD warning would never clear. The storage profile id joins the check because
	 * dimension ids collide across servers — every Overworld is "minecraft:overworld".
	 */
	public static boolean isAwayFromSchematic() {
		if (!isAvailable()) {
			return false;
		}
		return MaterialListCache.isAwayFromSchematic(currentDimension(), currentWorldId());
	}

	private static @Nullable String currentDimension() {
		var mc = net.minecraft.client.Minecraft.getInstance();
		if (mc == null || mc.level == null) {
			return null;
		}
		return com.chestmemory.client.data.ChestMemoryStorage.dimensionId(mc.level);
	}

	/** Storage profile of the server / SP world we are on, or null when not in a world. */
	private static @Nullable String currentWorldId() {
		return com.chestmemory.client.data.ChestMemoryStorage.get().liveWorldId();
	}
}
