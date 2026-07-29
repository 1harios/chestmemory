package com.chestmemory.client.data;

/**
 * A quantity counted the way players count bulk: shulker boxes, then stacks, then loose items.
 * <p>
 * "Осталось 43 200" is a number nobody can act on. "25 ШБ" is a shopping list — it says how
 * many trips, how much space, how many boxes to bring. A shulker box is 27 slots, so a box
 * holds 27 full stacks: 1728 of a 64-stack item, but only 432 of a 16-stack one and 27 of
 * anything that does not stack. The stack size therefore has to come from the item, never
 * from a hardcoded 64 — an ender pearl breakdown computed against 64 is off by a factor of
 * four.
 * <p>
 * Deliberately free of any Minecraft type: this is integer arithmetic, and keeping it that
 * way is what lets it be unit-tested without the game on the classpath.
 *
 * @param count    the original quantity
 * @param perStack the item's maximum stack size, at least 1
 * @param boxes    whole shulker boxes
 * @param stacks   whole stacks left over after the boxes
 * @param items    loose items left over after the stacks
 */
public record BulkAmount(int count, int perStack, int boxes, int stacks, int items) {
	/** Slots in a shulker box. */
	public static final int SHULKER_SLOTS = 27;

	/** Split a quantity for this item's stack size. Negative counts read as zero. */
	public static BulkAmount of(int count, int perStack) {
		int per = Math.max(1, perStack);
		int n = Math.max(0, count);
		int boxCap = boxCapacity(per);
		int boxes = n / boxCap;
		int rest = n - boxes * boxCap;
		// An item that does not stack has no stack tier at all. Dividing by one is
		// arithmetically fine and reads as nonsense: seven pickaxes were reported as
		// "7 стаков кирок", and the same went for boats, tools and shulker boxes. Their
		// remainder is loose items, and boxes still mean something — 27 slots is 27 pickaxes.
		int stacks = per > 1 ? rest / per : 0;
		int items = per > 1 ? rest % per : rest;
		return new BulkAmount(n, per, boxes, stacks, items);
	}

	/** True when this item stacks at all — a tool, a boat or a shulker box does not. */
	public boolean stackable() {
		return perStack > 1;
	}

	/** How many items fill one shulker box of this item. */
	public static int boxCapacity(int perStack) {
		return Math.max(1, perStack) * SHULKER_SLOTS;
	}

	/** Whole stacks in the total, ignoring the box split — "27 ст." rather than "1 ШБ". */
	public int totalStacks() {
		return stackable() ? count / perStack : 0;
	}

	/** Items left over once {@link #totalStacks()} are taken out. */
	public int looseAfterStacks() {
		return stackable() ? count % perStack : count;
	}

	/** True once there is at least one full box — below that, boxes are not worth saying. */
	public boolean hasBox() {
		return boxes > 0;
	}

	/**
	 * True once there is at least one full stack — never for an item that does not stack,
	 * where a "stack" would just be one of the thing.
	 */
	public boolean hasStack() {
		return stackable() && totalStacks() > 0;
	}
}
