package com.chestmemory.client.litematica;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.materials.MaterialListBase;
import fi.dy.masa.litematica.materials.MaterialListEntry;
import fi.dy.masa.litematica.materials.MaterialListUtils;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Optional bridge to Litematica's active Material List.
 * <p>
 * Missing counts are recomputed from schematic total − player inventory
 * (Litematica's own available count often stays stale until its GUI refreshes).
 */
public final class LitematicaCompat {
	public static final String MOD_ID = "litematica";

	/**
	 * Set on the first throwable out of the bridge; never retried this session.
	 * <p>
	 * The catch blocks below are what turn a 26.1-vs-26.2 signature drift into a no-op
	 * instead of a crash. But a no-op that silently retries re-enters the broken call every
	 * tick — re-mutating Litematica's entries through {@code updateAvailableCounts} on the
	 * way — and the degraded mode is indistinguishable from "no schematic open": the gather
	 * button goes dead, the HUD stays empty, and the real incompatibility hides forever.
	 * So the first failure is logged once at WARN and the bridge stays down.
	 */
	private static boolean bridgeBroken;
	/**
	 * {@code updateAvailableCounts} drifting alone must not take the read path down: we
	 * never read the available counts it writes (inPlayer is recomputed below) — the call
	 * only keeps Litematica's own GUI in step with ours. See the note at the call site.
	 */
	private static boolean availableRefreshBroken;
	/** One WARN per session total — this runs on a tick path and must not spam. */
	private static boolean failureLogged;
	/**
	 * List we have watched report {@code countMissing > 0} at least once. Entries carry no
	 * setters for missing, so if the same list object later reads all-zero, a rescan really
	 * found the build done — it cannot have quietly "un-refreshed".
	 */
	private static java.lang.ref.WeakReference<MaterialListBase> trustedList = new java.lang.ref.WeakReference<>(null);

	private LitematicaCompat() {
	}

	public static boolean isLoaded() {
		return FabricLoader.getInstance().isModLoaded(MOD_ID);
	}

	/** True after any Litematica call has thrown this session — callers must expect no data. */
	public static boolean isBridgeBroken() {
		return bridgeBroken;
	}

	private static void markBridgeBroken(String where, Throwable t) {
		bridgeBroken = true;
		logFailureOnce(where, t);
	}

	private static void logFailureOnce(String where, Throwable t) {
		if (failureLogged) {
			return;
		}
		failureLogged = true;
		com.chestmemory.ChestMemoryMod.LOGGER.warn(
			"Litematica bridge failed in {} ({}: {}) — schematic materials will read as absent for this session",
			where, t.getClass().getName(), t.getMessage()
		);
	}

	public static List<MaterialNeed> getMissingMaterialsSafe() {
		if (!isLoaded() || bridgeBroken) {
			return List.of();
		}
		try {
			return getMissingMaterials();
		} catch (Throwable t) {
			markBridgeBroken("getMissingMaterials", t);
			return List.of();
		}
	}

	public static @Nullable String getActiveListNameSafe() {
		if (!isLoaded() || bridgeBroken) {
			return null;
		}
		try {
			return getActiveListName();
		} catch (Throwable t) {
			markBridgeBroken("getActiveListName", t);
			return null;
		}
	}

	public static boolean hasActiveMaterialListSafe() {
		if (!isLoaded() || bridgeBroken) {
			return false;
		}
		try {
			return DataManager.getMaterialList() != null;
		} catch (Throwable t) {
			markBridgeBroken("getMaterialList", t);
			return false;
		}
	}

	/**
	 * True when Litematica's own list says the build is complete: totals exist, nothing missing.
	 * <p>
	 * Reads the list-level aggregates, which are written only when a count task completes
	 * ({@code setMaterialListEntries → updateCounts}, verified against the bundled 26.2 jar).
	 * An uncounted list still has 0/0 aggregates, so a nonzero total is proof these numbers
	 * came from a real world scan — which is what separates "finished" from "not yet
	 * refreshed" and lets the cache stop resurrecting the full bill for a done build.
	 */
	public static boolean isListFinishedSafe() {
		if (!isLoaded() || bridgeBroken) {
			return false;
		}
		try {
			MaterialListBase list = DataManager.getMaterialList();
			return list != null && list.getCountTotal() > 0 && list.getCountMissing() == 0;
		} catch (Throwable t) {
			markBridgeBroken("isListFinished", t);
			return false;
		}
	}

	private static @Nullable String getActiveListName() {
		MaterialListBase list = DataManager.getMaterialList();
		if (list == null) {
			return null;
		}
		String name = list.getName();
		if (name == null || name.isBlank()) {
			name = list.getTitle();
		}
		return name;
	}

	/**
	 * All schematic materials with live inventory-based missing counts.
	 * Includes items already fully covered by inventory ({@code missing == 0}).
	 */
	private static List<MaterialNeed> getMissingMaterials() {
		MaterialListBase list = DataManager.getMaterialList();
		if (list == null) {
			return List.of();
		}

		// Full list (not only "missing" — that list is often stale)
		List<MaterialListEntry> entries = list.getMaterialsAll();
		if (entries == null || entries.isEmpty()) {
			entries = list.getMaterialsFiltered(false);
		}
		if (entries == null) {
			return List.of();
		}

		// Ask Litematica to refresh available from player (best-effort). This MUTATES
		// Litematica's own entries — it must run at most once per rebuild, which is why
		// LitematicaAccess snapshots this result instead of re-fetching per material row.
		LocalPlayer player = Minecraft.getInstance().player;
		if (player != null && !availableRefreshBroken) {
			try {
				MaterialListUtils.updateAvailableCounts(entries, player);
			} catch (Throwable t) {
				availableRefreshBroken = true;
				logFailureOnce("updateAvailableCounts", t);
			}
		}

		// Decide once for the whole list whether countMissing can be trusted.
		// It is 0 for every entry both when the build is finished and when Litematica has
		// not refreshed the list yet; per-entry we could not tell those apart, but across
		// the list we can: if nothing is missing anywhere while the schematic clearly has
		// blocks, the data is stale and the full totals are the safer answer.
		boolean anyMissing = false;
		boolean anyTotal = false;
		for (MaterialListEntry e : entries) {
			if (e == null) {
				continue;
			}
			if (e.getCountMissing() > 0) {
				anyMissing = true;
				break;
			}
			if (e.getCountTotal() > 0) {
				anyTotal = true;
			}
		}
		boolean useMissingCounts = anyMissing || !anyTotal;
		if (!useMissingCounts) {
			// All-zero missing with real totals is where "finished" and "stale" collide, and
			// resolving it as "stale" made a genuinely finished build (or a list opened just
			// to check a done schematic) report the entire bill of materials again. Two
			// independent proofs of "finished" break the tie:
			//  - the list's own aggregates carry a nonzero total only after a completed
			//    world scan wrote these entries (see isListFinishedSafe), so their zeros
			//    mean "placed", not "not yet counted";
			//  - we watched this same list object report missing > 0 earlier — see trustedList.
			// Neither proof present → keep the stale-safe fallback to full totals.
			useMissingCounts = listCountsAreFromScan(list) || list == trustedList.get();
		}
		if (anyMissing && list != trustedList.get()) {
			trustedList = new java.lang.ref.WeakReference<>(list);
		}

		Map<String, MaterialNeed> merged = new LinkedHashMap<>();
		for (MaterialListEntry entry : entries) {
			if (entry == null) {
				continue;
			}
			ItemStack stack = entry.getStack();
			if (stack == null || stack.isEmpty()) {
				continue;
			}
			if (stack.is(Items.AIR)) {
				continue;
			}
			// Distinct enchanted books / gear by enchantments
			String key = com.chestmemory.client.data.ItemStackKeys.keyOf(stack);
			int schematicTotal = Math.max(0, entry.getCountTotal());
			int stillNeeded = Math.max(0, entry.getCountMissing());
			// See useMissingCounts: countMissing excludes blocks already placed, which is
			// what a half-built schematic actually needs — but only when the list has
			// been refreshed. Otherwise fall back to the full schematic total.
			int total = useMissingCounts ? Math.min(stillNeeded, schematicTotal) : schematicTotal;
			if (total <= 0) {
				continue;
			}

			int inPlayer = countInPlayerMatching(player, key);
			// Authoritative: how many more after OUR inventory count (0 = already done)
			int missing = Math.max(0, total - inPlayer);

			MaterialNeed prev = merged.get(key);
			if (prev == null) {
				merged.put(key, new MaterialNeed(key, total, missing, inPlayer));
			} else {
				int newTotal = prev.total() + total;
				int newInPlayer = Math.max(prev.availableInPlayer(), inPlayer);
				merged.put(key, new MaterialNeed(
					key,
					newTotal,
					Math.max(0, newTotal - newInPlayer),
					newInPlayer
				));
			}
		}

		return new ArrayList<>(merged.values());
	}

	private static boolean listCountsAreFromScan(MaterialListBase list) {
		try {
			return list.getCountTotal() > 0;
		} catch (Throwable t) {
			// Only the aggregate getters drifting: fall back to the stale-safe heuristic
			// instead of taking the whole (still working) read path down with them.
			logFailureOnce("getCountTotal", t);
			return false;
		}
	}

	private static int countInPlayerMatching(@Nullable LocalPlayer player, String key) {
		if (player == null || key == null) {
			return 0;
		}
		Inventory inv = player.getInventory();
		int total = 0;
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack stack = inv.getItem(i);
			if (!stack.isEmpty() && com.chestmemory.client.data.ItemStackKeys.matches(stack, key)) {
				total += stack.getCount();
			}
		}
		return total;
	}

	/**
	 * @param itemId            registry id
	 * @param total             total needed by schematic
	 * @param missing           still missing after player inventory (our calc)
	 * @param availableInPlayer items currently in player inventory
	 */
	public record MaterialNeed(String itemId, int total, int missing, int availableInPlayer) {
	}
}
