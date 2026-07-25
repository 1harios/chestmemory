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

	public static boolean hasActiveMaterialList() {
		if (!isAvailable()) {
			return false;
		}
		return LitematicaCompat.hasActiveMaterialListSafe();
	}

	public static @Nullable String activeListName() {
		if (!isAvailable()) {
			return null;
		}
		return LitematicaCompat.getActiveListNameSafe();
	}

	public static List<LitematicaCompat.MaterialNeed> missingMaterials() {
		if (!isAvailable()) {
			return List.of();
		}
		return LitematicaCompat.getMissingMaterialsSafe();
	}
}
