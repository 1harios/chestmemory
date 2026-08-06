package com.chestmemory.client.gui;

import com.chestmemory.client.data.BulkAmount;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * The two lines that say one amount a second way: in stacks, and in shulker boxes.
 * <p>
 * Shared by the chest panel and the gather screen because they used to disagree. The panel
 * said {@code Всего: ×320 (5 ст. + 0)} — a bare remainder with no unit; the gather said
 * {@code Стаками: 5 ст.} on one line and {@code Это 0 ШБ + 25 ст.} on another, where "это"
 * named no subject, so it was impossible to tell whether the figure described the stock in
 * the chests or what was left to collect. A third line then announced
 * {@code 1 ШБ = 1728 шт (27 × 64)}, which explained arithmetic nobody had asked about.
 * <p>
 * The replacement is deliberately dull: each amount keeps its own line, and directly under
 * it come the same numbers restated, indented, one line per unit. Stacks never mention
 * boxes and the box line always leads with boxes, so the two can be compared at a glance
 * without reading labels twice.
 */
public final class BulkTooltip {
	/** Indented, dimmer than the line it belongs to — a restatement, not a new fact. */
	private static final ChatFormatting SUB = ChatFormatting.DARK_GRAY;

	private BulkTooltip() {
	}

	/** «25 ст.» or «25 ст. + 32 шт» — whole stacks and what is left over. */
	public static String stacksText(BulkAmount bulk) {
		String stacks = Component.translatable(
			"screen.chestmemory.tooltip.unit_stack", bulk.totalStacks()
		).getString();
		if (bulk.looseAfterStacks() <= 0) {
			return stacks;
		}
		return stacks + " + " + Component.translatable(
			"screen.chestmemory.tooltip.unit_item", bulk.looseAfterStacks()
		).getString();
	}

	/** «1 ШБ», «1 ШБ + 3 ст.», «1 ШБ + 3 ст. + 5 шт» — zero parts are left out. */
	public static String boxesText(BulkAmount bulk) {
		StringBuilder out = new StringBuilder(Component.translatable(
			"screen.chestmemory.tooltip.unit_box", bulk.boxes()
		).getString());
		if (bulk.stacks() > 0) {
			out.append(" + ").append(Component.translatable(
				"screen.chestmemory.tooltip.unit_stack", bulk.stacks()
			).getString());
		}
		if (bulk.items() > 0) {
			out.append(" + ").append(Component.translatable(
				"screen.chestmemory.tooltip.unit_item", bulk.items()
			).getString());
		}
		return out.toString();
	}

	/**
	 * Restate an amount under the line that introduced it.
	 * <p>
	 * Says nothing when there is nothing worth saying: below one full stack the raw count
	 * is already the clearest form of itself, and the box line appears only once a whole
	 * box exists — "0 ШБ" is not information.
	 *
	 * @param perStack the item's real maximum stack size, never assumed to be 64
	 */
	public static void append(List<Component> lines, int amount, int perStack) {
		append(lines, amount, perStack,
			"screen.chestmemory.tooltip.in_stacks", "screen.chestmemory.tooltip.in_boxes");
	}

	/**
	 * The same breakdown under a caller's own labels.
	 * <p>
	 * A gather cell shows two of these — what to carry and what is lying in the chests — and
	 * an unlabelled pair under both numbers gets read against the wrong one: "126 стаков"
	 * beside a remainder of 5754 looks like broken arithmetic, when it is an exact reading of
	 * the 8097 in the chests.
	 */
	public static void append(
		List<Component> lines, int amount, int perStack, String stacksKey, String boxesKey
	) {
		if (amount <= 0) {
			return;
		}
		BulkAmount bulk = BulkAmount.of(amount, perStack);
		if (bulk.hasStack()) {
			lines.add(Component.translatable(stacksKey, stacksText(bulk)).withStyle(SUB));
		}
		if (bulk.hasBox()) {
			lines.add(Component.translatable(boxesKey, boxesText(bulk)).withStyle(SUB));
		}
	}
}
