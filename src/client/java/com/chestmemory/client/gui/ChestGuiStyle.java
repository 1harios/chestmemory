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

	/**
	 * Panel size shared by every screen of the mod.
	 * <p>
	 * Each screen used to compute its own bounds, so the frame visibly jumped when moving
	 * between the item list and settings. One source of truth keeps them identical.
	 */
	public static int panelWidth(int screenWidth) {
		return Math.min(340, Math.max(260, screenWidth - 24));
	}

	public static int panelHeight(int screenHeight) {
		return Math.min(300, Math.max(230, screenHeight - 32));
	}

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

	/**
	 * Section heading for the settings list: wooden label plate with a rule running to
	 * the right edge, instead of a bare line of text floating over the panel.
	 */
	public static void drawSectionHeader(
		GuiGraphicsExtractor graphics,
		Font font,
		Component title,
		int left,
		int y,
		int width
	) {
		int textW = font.width(title);
		int plateW = Math.min(width, textW + 12);

		// Wooden tab behind the caption
		graphics.fill(left, y - 2, left + plateW, y + 11, WOOD_MID);
		graphics.fill(left + 1, y - 1, left + plateW - 1, y + 10, WOOD_LIGHT);
		graphics.text(font, title, left + 6, y + 1, TEXT_LIGHT, false);

		// Rule filling the remaining width, so sections read as bands
		int ruleLeft = left + plateW + 4;
		int ruleRight = left + width;
		if (ruleRight > ruleLeft) {
			graphics.fill(ruleLeft, y + 4, ruleRight, y + 5, HEADER_LINE);
			graphics.fill(ruleLeft, y + 5, ruleRight, y + 6, 0x33000000);
		}
	}

	/**
	 * Wood tones for interactive rows. Deliberately darker than the panel frame: the row
	 * caption is TEXT_LIGHT, and mid-brown backgrounds left it at ~2.6:1 contrast, which
	 * is hard to read. These sit at 4.7:1 (idle) and 3.9:1 (hover).
	 */
	public static final int ROW_WOOD = 0xFF33200F;
	public static final int ROW_WOOD_HOVER = 0xFF432B15;
	public static final int ROW_WOOD_DISABLED = 0xFF2A2018;

	/**
	 * Recessed wooden background for a settings row, so rows sit in the panel instead of
	 * floating on it as flat vanilla buttons.
	 */
	public static void drawSettingRow(
		GuiGraphicsExtractor graphics,
		int x,
		int y,
		int width,
		int height,
		boolean hovered,
		boolean enabled
	) {
		int fill = !enabled ? ROW_WOOD_DISABLED : (hovered ? ROW_WOOD_HOVER : ROW_WOOD);
		// Outer edge, then face, then a lighter top rim for a slight bevel
		graphics.fill(x, y, x + width, y + height, WOOD_DARK);
		graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, fill);
		graphics.fill(x + 1, y + 1, x + width - 1, y + 2, withAlpha(0xFFFFFF, hovered ? 0.22F : 0.12F));
		graphics.fill(x + 1, y + height - 2, x + width - 1, y + height - 1, withAlpha(0x000000, 0.25F));
	}

	/**
	 * Progress bar in the panel's wood palette.
	 * Used by the clan screen, where "delivered 340/1200" reads much faster as a bar
	 * than as a number buried in a status line.
	 */
	public static void drawProgressBar(
		GuiGraphicsExtractor graphics,
		int x,
		int y,
		int width,
		int height,
		float fraction
	) {
		float f = Math.max(0F, Math.min(1F, fraction));
		graphics.fill(x, y, x + width, y + height, WOOD_DARK);
		graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, 0xFF241708);
		int fill = (int) ((width - 2) * f);
		if (fill > 0) {
			// Amber while gathering, green once everything is in.
			int colour = f >= 1F ? 0xFF5FD068 : 0xFFE0A83C;
			graphics.fill(x + 1, y + 1, x + 1 + fill, y + height - 1, colour);
			// Top highlight so the bar reads as raised, matching the row style.
			graphics.fill(x + 1, y + 1, x + 1 + fill, y + 2, withAlpha(0xFFFFFF, 0.25F));
		}
	}

	/** Big monospace-ish session code, drawn as a plate the host can read out loud. */
	public static void drawCodePlate(
		GuiGraphicsExtractor graphics,
		Font font,
		String code,
		int centerX,
		int y,
		int minWidth
	) {
		int textW = font.width(code);
		int w = Math.max(minWidth, textW + 24);
		int x = centerX - w / 2;
		graphics.fill(x, y, x + w, y + 20, WOOD_DARK);
		graphics.fill(x + 1, y + 1, x + w - 1, y + 19, 0xFF3A2414);
		graphics.fill(x + 1, y + 1, x + w - 1, y + 2, withAlpha(0xFFFFFF, 0.18F));
		graphics.text(font, code, centerX - textW / 2 + 1, y + 7, 0xFF000000, false);
		graphics.text(font, code, centerX - textW / 2, y + 6, TEXT_GOLD, false);
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
