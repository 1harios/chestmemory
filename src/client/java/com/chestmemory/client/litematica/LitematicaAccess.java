package com.chestmemory.client.litematica;

import net.fabricmc.loader.api.FabricLoader;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Safe façade — never touches Litematica classes unless the mod is installed.
 */
public final class LitematicaAccess {
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
			// What is left for the clan to bring, not the total the build needs: the queue
			// treats total() as the amount to gather, and whatever teammates already delivered
			// must not be gathered twice.
			int remaining = session.remaining(e.getKey());
			if (remaining > 0) {
				out.add(new LitematicaCompat.MaterialNeed(e.getKey(), remaining, remaining, 0));
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
	 */
	public static List<LitematicaCompat.MaterialNeed> missingMaterials() {
		if (!isAvailable()) {
			// No Litematica at all: a clan gather still has materials worth showing.
			List<LitematicaCompat.MaterialNeed> clanOnly = clanMaterials();
			return clanOnly != null ? clanOnly : List.of();
		}
		List<LitematicaCompat.MaterialNeed> live = LitematicaCompat.getMissingMaterialsSafe();
		List<LitematicaCompat.MaterialNeed> resolved = MaterialListCache.resolve(
			live, LitematicaCompat.getActiveListNameSafe(), currentDimension()
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
	 * True when the player is away from the dimension the schematic list was captured in.
	 * <p>
	 * Keyed on the dimension rather than on "Litematica has no list": Litematica never
	 * recreates a list by itself, so the empty-list condition stays true after coming home
	 * and the HUD warning would never clear.
	 */
	public static boolean isAwayFromSchematic() {
		if (!isAvailable()) {
			return false;
		}
		return MaterialListCache.isAwayFromSchematic(currentDimension());
	}

	private static @Nullable String currentDimension() {
		var mc = net.minecraft.client.Minecraft.getInstance();
		if (mc == null || mc.level == null) {
			return null;
		}
		return com.chestmemory.client.data.ChestMemoryStorage.dimensionId(mc.level);
	}
}
