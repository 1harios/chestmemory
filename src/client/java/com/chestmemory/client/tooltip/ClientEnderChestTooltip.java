package com.chestmemory.client.tooltip;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * Renders ender-chest memory as a grid of item/block icons with counts (like a mini inventory).
 */
public final class ClientEnderChestTooltip implements ClientTooltipComponent {
	private static final int SLOT = 18;
	private static final int COLS = 9;
	private static final int PAD = 1;

	private final List<ItemStack> stacks;
	private final int hiddenExtra;

	public ClientEnderChestTooltip(EnderChestTooltipComponent data) {
		this.stacks = data.stacks();
		this.hiddenExtra = data.hiddenExtra();
	}

	private int rows() {
		int n = stacks.size();
		if (hiddenExtra > 0) {
			n += 1; // "+N" chip row cell
		}
		return Math.max(1, (n + COLS - 1) / COLS);
	}

	private int cells() {
		return stacks.size() + (hiddenExtra > 0 ? 1 : 0);
	}

	@Override
	public int getHeight(Font font) {
		return rows() * SLOT + 2;
	}

	@Override
	public int getWidth(Font font) {
		int cols = Math.min(COLS, Math.max(1, cells()));
		return cols * SLOT + PAD * 2;
	}

	@Override
	public void extractImage(Font font, int x, int y, int width, int height, GuiGraphicsExtractor graphics) {
		int total = cells();
		int cols = Math.min(COLS, Math.max(1, total));

		// Dark plate behind slots
		int gridW = cols * SLOT;
		int gridH = rows() * SLOT;
		graphics.fill(x, y, x + gridW + PAD * 2, y + gridH + 2, 0xFF1A120A);
		graphics.fill(x + 1, y + 1, x + gridW + PAD * 2 - 1, y + gridH + 1, 0xFF3A2A18);

		int ox = x + PAD;
		int oy = y + 1;

		for (int i = 0; i < stacks.size(); i++) {
			int col = i % COLS;
			int row = i / COLS;
			int sx = ox + col * SLOT;
			int sy = oy + row * SLOT;

			// Slot background
			graphics.fill(sx, sy, sx + 16, sy + 16, 0xFF8B8B8B);
			graphics.fill(sx + 1, sy + 1, sx + 15, sy + 15, 0xFF373737);

			ItemStack stack = stacks.get(i);
			if (!stack.isEmpty()) {
				graphics.item(stack, sx, sy);

				// Count in white with shadow (full alpha)
				int count = stack.getCount();
				if (count > 1 || count == 0) {
					String text = formatCount(count);
					int tw = font.width(text);
					int tx = sx + 17 - tw;
					int ty = sy + 9;
					graphics.text(font, text, tx + 1, ty + 1, 0xFF000000, false);
					graphics.text(font, text, tx, ty, 0xFFFFFFFF, false);
				}
			}
		}

		if (hiddenExtra > 0) {
			int i = stacks.size();
			int col = i % COLS;
			int row = i / COLS;
			int sx = ox + col * SLOT;
			int sy = oy + row * SLOT;
			graphics.fill(sx, sy, sx + 16, sy + 16, 0xFF555555);
			String more = "+" + hiddenExtra;
			int tw = font.width(more);
			graphics.text(font, more, sx + (16 - tw) / 2, sy + 4, 0xFFE0E0E0, false);
		}
	}

	private static String formatCount(int count) {
		if (count >= 1_000_000) {
			return String.format("%.1fM", count / 1_000_000.0);
		}
		if (count >= 10_000) {
			return String.format("%.1fk", count / 1000.0);
		}
		return String.valueOf(count);
	}
}
