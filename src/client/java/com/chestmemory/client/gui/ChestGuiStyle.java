package com.chestmemory.client.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;

/**
 * Shared “wooden chest” look for the Ё panel.
 */
public final class ChestGuiStyle {
	public static final Identifier SLOT_SPRITE = Identifier.withDefaultNamespace("container/slot");

	// ARGB — alpha MUST be non-zero for text in 26.2
	public static final int TEXT_TITLE = 0xFF3F3F3F;
	public static final int TEXT_BODY = 0xFF404040;
	public static final int TEXT_LIGHT = 0xFFE8D5B0;
	public static final int TEXT_GOLD = 0xFFFFD56A;
	public static final int TEXT_MUTED = 0xFF7A6A50;
	public static final int TEXT_COUNT = 0xFFFFFFFF;
	public static final int TEXT_COUNT_SHADOW = 0xFF000000;

	public static final int WOOD_DARK = 0xFF2A1A0E;
	public static final int WOOD_MID = 0xFF5C3A1E;
	public static final int WOOD_LIGHT = 0xFF8B5A2B;
	public static final int LATCH = 0xFFE0C040;
	public static final int PANEL_INNER = 0xFFC6C6C6;
	public static final int HEADER_BG = 0xFFE0D0B8;
	public static final int HEADER_LINE = 0xFF8B5A2B;
	public static final int ROW_HOVER = 0x66FFFFFF;
	public static final int ROW_BG = 0x33000000;
	public static final int BADGE_BG = 0xEE1A1208;
	public static final int BADGE_BORDER = 0xFFE8B84A;
	public static final int VIGNETTE = 0xB0000000;

	/** Height of title header area (title + "you are here"). */
	public static final int HEADER_H = 36;

	private ChestGuiStyle() {
	}

	/**
	 * Clean wood frame — no metal strap / lid strip across the title.
	 */
	public static void drawChestPanel(GuiGraphicsExtractor graphics, int left, int top, int width, int height) {
		// Outer wood frame
		graphics.fill(left - 4, top - 4, left + width + 4, top + height + 4, WOOD_DARK);
		graphics.fill(left - 3, top - 3, left + width + 3, top + height + 3, WOOD_LIGHT);
		graphics.fill(left - 1, top - 1, left + width + 1, top + height + 1, WOOD_MID);

		// Inner panel
		graphics.fill(left, top, left + width, top + height, PANEL_INNER);

		// Soft header band for title (not a metal strip)
		graphics.fill(left, top, left + width, top + HEADER_H, HEADER_BG);
		// Thin separator under header only
		graphics.fill(left + 8, top + HEADER_H - 1, left + width - 8, top + HEADER_H, HEADER_LINE);

		// Small decorative latch centered on the top wood rim (outside content, not over text)
		int midX = left + width / 2;
		graphics.fill(midX - 7, top - 3, midX + 7, top + 2, 0xFF5A5A5A);
		graphics.fill(midX - 5, top - 2, midX + 5, top + 1, LATCH);
	}

	public static void drawSlot(GuiGraphicsExtractor graphics, int x, int y) {
		graphics.blitSprite(RenderPipelines.GUI_TEXTURED, SLOT_SPRITE, x, y, 18, 18);
	}

	public static void drawCountBadge(GuiGraphicsExtractor graphics, Font font, String text, int right, int y) {
		int w = Math.max(28, font.width(text) + 10);
		int x = right - w;
		graphics.fill(x, y, x + w, y + 16, BADGE_BG);
		graphics.fill(x, y, x + w, y + 1, BADGE_BORDER);
		graphics.fill(x, y + 15, x + w, y + 16, BADGE_BORDER);
		graphics.fill(x, y, x + 1, y + 16, BADGE_BORDER);
		graphics.fill(x + w - 1, y, x + w, y + 16, BADGE_BORDER);
		int tx = x + (w - font.width(text)) / 2;
		graphics.text(font, text, tx + 1, y + 5, TEXT_COUNT_SHADOW, false);
		graphics.text(font, text, tx, y + 4, TEXT_COUNT, false);
	}

	public static void drawCentered(GuiGraphicsExtractor graphics, Font font, Component text, int centerX, int y, int color) {
		int w = font.width(text);
		graphics.text(font, text, centerX - w / 2, y, color, false);
	}

	public static void drawCentered(GuiGraphicsExtractor graphics, Font font, String text, int centerX, int y, int color) {
		int w = font.width(text);
		graphics.text(font, text, centerX - w / 2, y, color, false);
	}

	/** Ellipsize string to max pixel width. */
	public static String ellipsize(Font font, String text, int maxW) {
		if (font.width(text) <= maxW) {
			return text;
		}
		while (text.length() > 3 && font.width(text + "…") > maxW) {
			text = text.substring(0, text.length() - 1);
		}
		return text + "…";
	}

	public static int withAlpha(int rgb, float alpha) {
		int a = Math.max(0, Math.min(255, (int) (alpha * 255)));
		return ARGB.color(a, ARGB.red(rgb | 0xFF000000), ARGB.green(rgb | 0xFF000000), ARGB.blue(rgb | 0xFF000000));
	}
}
