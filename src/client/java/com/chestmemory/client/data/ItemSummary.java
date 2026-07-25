package com.chestmemory.client.data;

/**
 * Aggregated item info for the panel list.
 */
public final class ItemSummary {
	private final String itemId;
	private final int totalCount;
	private final int containerCount;
	/** Nearest container distance in blocks, or -1 if unknown. */
	private final double nearestDistance;
	/**
	 * How many are still needed for the active Litematica list (after player inv), or -1.
	 */
	private final int neededForBuild;
	/** Count currently in player inventory (build mode), or -1. */
	private final int inPlayer;
	/** Total required by schematic (build mode), or -1. */
	private final int schematicTotal;

	public ItemSummary(String itemId, int totalCount, int containerCount) {
		this(itemId, totalCount, containerCount, -1, -1, -1, -1);
	}

	public ItemSummary(String itemId, int totalCount, int containerCount, double nearestDistance) {
		this(itemId, totalCount, containerCount, nearestDistance, -1, -1, -1);
	}

	public ItemSummary(
		String itemId,
		int totalCount,
		int containerCount,
		double nearestDistance,
		int neededForBuild
	) {
		this(itemId, totalCount, containerCount, nearestDistance, neededForBuild, -1, -1);
	}

	public ItemSummary(
		String itemId,
		int totalCount,
		int containerCount,
		double nearestDistance,
		int neededForBuild,
		int inPlayer,
		int schematicTotal
	) {
		this.itemId = itemId;
		this.totalCount = totalCount;
		this.containerCount = containerCount;
		this.nearestDistance = nearestDistance;
		this.neededForBuild = neededForBuild;
		this.inPlayer = inPlayer;
		this.schematicTotal = schematicTotal;
	}

	public String itemId() {
		return itemId;
	}

	public int totalCount() {
		return totalCount;
	}

	public int containerCount() {
		return containerCount;
	}

	public double nearestDistance() {
		return nearestDistance;
	}

	public boolean hasDistance() {
		return nearestDistance >= 0;
	}

	public boolean isBuildNeed() {
		return neededForBuild >= 0;
	}

	public int neededForBuild() {
		return neededForBuild;
	}

	public int inPlayer() {
		return inPlayer;
	}

	public int schematicTotal() {
		return schematicTotal;
	}

	/** How many more chests must supply after what's already in memory. */
	public int stillShort() {
		if (!isBuildNeed()) {
			return 0;
		}
		return Math.max(0, neededForBuild - totalCount);
	}

	/** How many can be taken from remembered chests right now. */
	public int canGatherFromChests() {
		if (!isBuildNeed()) {
			return 0;
		}
		return Math.min(neededForBuild, totalCount);
	}

	/** @deprecated assumes 64 per stack; use {@link #fullStacks(int)} */
	@Deprecated
	public int fullStacks() {
		return totalCount / 64;
	}

	/** @deprecated assumes 64 per stack; use {@link #remainder(int)} */
	@Deprecated
	public int remainder() {
		return totalCount % 64;
	}

	/**
	 * Full stacks for this item's real stack size. Ender pearls stack to 16 and tools to
	 * 1, so the fixed /64 reported e.g. "2 stacks" for 128 pearls when it is really 8.
	 */
	public int fullStacks(int maxStackSize) {
		int per = Math.max(1, maxStackSize);
		return totalCount / per;
	}

	public int remainder(int maxStackSize) {
		int per = Math.max(1, maxStackSize);
		return totalCount % per;
	}
}
