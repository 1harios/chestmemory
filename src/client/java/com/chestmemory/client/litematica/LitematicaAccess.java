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
		if (!isAvailable()) {
			return false;
		}
		if (LitematicaCompat.hasActiveMaterialListSafe()) {
			return true;
		}
		return !missingMaterials().isEmpty();
	}

	/**
	 * Name of the active schematic. Falls back to the cached name, so the HUD keeps its
	 * caption instead of blanking out mid-portal.
	 */
	public static @Nullable String activeListName() {
		if (!isAvailable()) {
			return null;
		}
		String live = LitematicaCompat.getActiveListNameSafe();
		return live != null ? live : MaterialListCache.cachedListName();
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
			return List.of();
		}
		List<LitematicaCompat.MaterialNeed> live = LitematicaCompat.getMissingMaterialsSafe();
		return MaterialListCache.resolve(live, LitematicaCompat.getActiveListNameSafe());
	}

	/** True when the materials being served are cached because Litematica has no list. */
	public static boolean isUsingCachedList() {
		if (!isAvailable()) {
			return false;
		}
		return MaterialListCache.isServingCache(LitematicaCompat.getMissingMaterialsSafe());
	}
}
