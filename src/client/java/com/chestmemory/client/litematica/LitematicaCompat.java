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

	private LitematicaCompat() {
	}

	public static boolean isLoaded() {
		return FabricLoader.getInstance().isModLoaded(MOD_ID);
	}

	public static List<MaterialNeed> getMissingMaterialsSafe() {
		if (!isLoaded()) {
			return List.of();
		}
		try {
			return getMissingMaterials();
		} catch (Throwable t) {
			return List.of();
		}
	}

	public static @Nullable String getActiveListNameSafe() {
		if (!isLoaded()) {
			return null;
		}
		try {
			return getActiveListName();
		} catch (Throwable t) {
			return null;
		}
	}

	public static boolean hasActiveMaterialListSafe() {
		if (!isLoaded()) {
			return false;
		}
		try {
			return DataManager.getMaterialList() != null;
		} catch (Throwable t) {
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

		// Ask Litematica to refresh available from player (best-effort)
		LocalPlayer player = Minecraft.getInstance().player;
		if (player != null) {
			try {
				MaterialListUtils.updateAvailableCounts(entries, player);
			} catch (Throwable ignored) {
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
