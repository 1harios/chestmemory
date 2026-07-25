package com.chestmemory.client.litematica;

import com.chestmemory.client.data.ItemSummary;
import net.minecraft.network.chat.Component;

/**
 * Scheme panel filters — plain-language, collect-oriented.
 */
public enum BuildFilter {
	/**
	 * Everything for the schematic (still needed + already done), smart order.
	 */
	ALL,
	/** Chests already hold enough for what's left. */
	READY,
	/** Some in chests, but not enough. */
	PARTIAL,
	/** Nothing in remembered chests. */
	NONE,
	/** Fully covered by inventory (nothing left to gather). */
	DONE;

	public Component label() {
		return Component.translatable("screen.chestmemory.build_filter." + name().toLowerCase());
	}

	public Component hint() {
		return Component.translatable("screen.chestmemory.build_filter." + name().toLowerCase() + ".hint");
	}

	public BuildFilter next() {
		BuildFilter[] v = values();
		return v[(ordinal() + 1) % v.length];
	}

	/**
	 * Collect priority for sorting (lower = collect first):
	 * 0 = enough in chests, 1 = partial in chests, 2 = none in chests, 3 = already done.
	 */
	public static int gatherPriority(ItemSummary s) {
		if (!s.isBuildNeed() || s.neededForBuild() <= 0) {
			return 3; // done
		}
		int inChests = s.totalCount();
		int need = s.neededForBuild();
		if (inChests <= 0) {
			return 2; // nowhere
		}
		if (inChests >= need) {
			return 0; // full stock in chests
		}
		return 1; // partial
	}

	public boolean matches(ItemSummary s) {
		int p = gatherPriority(s);
		return switch (this) {
			case ALL -> true;
			case READY -> p == 0;
			case PARTIAL -> p == 1;
			case NONE -> p == 2;
			case DONE -> p == 3;
		};
	}
}
