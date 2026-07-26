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

	/**
	 * A button that cannot be pressed.
	 * <p>
	 * The previous tone was another brown, 1.03:1 against ROW_WOOD — invisible, so a dead
	 * button looked live and players kept clicking it. Wood is deeply saturated (0.71), so
	 * this drains the colour instead of chasing brightness: near-grey at 0.14 reads as
	 * "dead" next to warm planks even though it is only 1.26:1 apart in luminance.
	 */
	public static final int ROW_WOOD_DISABLED = 0xFF383430;

	/** Caption of a disabled row: 4.9:1 on ROW_WOOD_DISABLED — readable, clearly dimmer. */
	public static final int TEXT_DISABLED = 0xFFA8A39C;

	/**
	 * Secondary text on a wooden row. TEXT_MUTED is tuned for the light panel and drops to
	 * 2.96:1 on ROW_WOOD, which is unreadable; this sits at 5.5:1 while still reading as
	 * quieter than TEXT_LIGHT.
	 */
	public static final int TEXT_ON_WOOD_MUTED = 0xFFA89880;

	/**
	 * Face of the selected tab. WOOD_LIGHT looked right but only gave 2.87:1 against the
	 * dark caption; this lighter plank reaches 6.9:1.
	 */
	public static final int TAB_ACTIVE = 0xFFC9A063;

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
		if (enabled) {
			// The bevel is what makes a row look pressable, so a disabled row goes flat —
			// colour alone is a weak signal, and a flat plate reads as inert immediately.
			graphics.fill(x + 1, y + 1, x + width - 1, y + 2, withAlpha(0xFFFFFF, hovered ? 0.22F : 0.12F));
			graphics.fill(x + 1, y + height - 2, x + width - 1, y + height - 1, withAlpha(0x000000, 0.25F));
		}
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

	/**
	 * One row of the clan roster: name on the left, what they are carrying on the right.
	 * <p>
	 * Drawn as a recessed plank like the settings rows, so the roster reads as part of the
	 * chest panel rather than as a list of plain text lines floating over it.
	 *
	 * @param accent left edge marker — gold for the host, green when they are delivering,
	 *               muted when the hub has lost them
	 */
	public static void drawMemberRow(
		GuiGraphicsExtractor graphics,
		int x,
		int y,
		int width,
		int height,
		int accent,
		boolean dim
	) {
		graphics.fill(x, y, x + width, y + height, WOOD_DARK);
		graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, dim ? ROW_WOOD_DISABLED : ROW_WOOD);
		graphics.fill(x + 1, y + 1, x + width - 1, y + 2, withAlpha(0xFFFFFF, 0.10F));
		// Accent stripe: colour-codes state without spending horizontal room on words.
		graphics.fill(x + 1, y + 1, x + 3, y + height - 1, accent);
	}

	/** Hub reachable. */
	public static final int LAMP_ONLINE = 0xFF5FD068;
	/** Hub unreachable — the one state a player has to notice. */
	public static final int LAMP_OFFLINE = 0xFFE0603C;
	/** Check in flight. */
	public static final int LAMP_CHECKING = 0xFFE0A83C;

	/**
	 * Status strip for the hub: a state lamp, a caption and an optional detail on the right.
	 * <p>
	 * Replaces a row that looked like a button, did nothing when clicked, and said "hub:
	 * built in" whether or not anything answered there. A player cannot act on that; they can
	 * act on "hub unreachable".
	 *
	 * @param lamp colour of the state dot — green online, red offline, amber checking
	 */
	public static void drawStatusStrip(
		GuiGraphicsExtractor graphics,
		Font font,
		int x,
		int y,
		int width,
		int height,
		String label,
		@org.jspecify.annotations.Nullable String detail,
		int lamp
	) {
		// Flat and recessed: it carries information, so it must not read as pressable.
		graphics.fill(x, y, x + width, y + height, WOOD_DARK);
		graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, 0xFF2A1B0D);
		int cy = y + height / 2;
		// Lamp with a dark rim, so it stays visible against the plank.
		graphics.fill(x + 6, cy - 4, x + 12, cy + 2, withAlpha(0x000000, 0.45F));
		graphics.fill(x + 7, cy - 5, x + 11, cy + 1, lamp);
		int textY = y + (height - font.lineHeight) / 2 + 1;
		int detailW = detail == null || detail.isBlank() ? 0 : font.width(detail);
		graphics.text(
			font, ellipsize(font, label, width - 22 - detailW), x + 17, textY, TEXT_LIGHT, false
		);
		if (detailW > 0) {
			graphics.text(font, detail, x + width - 7 - detailW, textY, TEXT_ON_WOOD_MUTED, false);
		}
	}

	/** Slot pitch of the main screen's item grid. Everything item-shaped uses this. */
	public static final int GRID_SLOT = 18;

	/**
	 * Recessed tray behind a grid of item slots, exactly as the main screen draws it:
	 * a dark border with a light grey face.
	 * <p>
	 * Items on the bare panel read as loose icons; on this they read as an inventory. The
	 * clan screen looked like a different mod because it skipped this.
	 */
	public static void drawGridTray(
		GuiGraphicsExtractor graphics, int x, int y, int width, int height
	) {
		graphics.fill(x, y, x + width, y + height, 0xFF1A120A);
		graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, 0xFFC6C6C6);
	}

	/**
	 * Stack count in the corner of an 18px slot — scaled down, shadowed, no plate.
	 * <p>
	 * Copied from the main screen rather than reinvented: a count drawn at full size does
	 * not fit an 18px slot, which is why the clan grid needed 24px cells and still looked
	 * wrong next to the real thing.
	 */
	public static void drawSlotCount(
		GuiGraphicsExtractor graphics, Font font, String text, int slotX, int slotY, int colour
	) {
		float scale = 0.72F;
		int textW = font.width(text);
		float drawX = Math.max(slotX + 1, slotX + 17 - textW * scale);
		float drawY = slotY + 17 - 7.2F * scale;
		graphics.pose().pushMatrix();
		graphics.pose().translate(drawX, drawY);
		graphics.pose().scale(scale, scale);
		graphics.text(font, text, 1, 1, 0xE0000000, false);
		graphics.text(font, text, 0, 0, colour, false);
		graphics.pose().popMatrix();
	}

	/** Compact counts that fit an 18px slot: 999, 1k, 1.5k, 12k, 1.2M. */
	public static String formatCount(int count) {
		if (count <= 0) {
			return "0";
		}
		if (count >= 1_000_000) {
			double m = count / 1_000_000.0;
			return m >= 10 ? String.format("%.0fM", m) : String.format("%.1fM", m);
		}
		if (count >= 1000) {
			double k = count / 1000.0;
			// Rounding up at 999_500 produced "1000k" — five glyphs where the slot fits
			// three. Anything that would round to 1000k is a million as far as this is
			// concerned.
			if (k >= 999.5) {
				return "1.0M";
			}
			if (k >= 10 || Math.abs(k - Math.rint(k)) < 0.05) {
				return String.format("%.0fk", k);
			}
			return String.format("%.1fk", k);
		}
		return String.valueOf(count);
	}

	/**
	 * A label and its value on one line, the value right-aligned.
	 * <p>
	 * This is how a detail sheet reads: the eye runs down the labels on the left and the
	 * values line up on the right, instead of every fact being a centred sentence.
	 */
	public static void drawDetailRow(
		GuiGraphicsExtractor graphics,
		Font font,
		String label,
		String value,
		int x,
		int y,
		int width,
		int valueColour
	) {
		int valueW = font.width(value);
		graphics.text(font, ellipsize(font, label, width - valueW - 8), x, y, TEXT_MUTED, false);
		graphics.text(font, value, x + width - valueW, y, valueColour, false);
	}

	/**
	 * Small coloured dot used by the activity feed to mark the kind of event, so the list is
	 * scannable at a glance instead of being a wall of sentences.
	 */
	public static void drawEventDot(GuiGraphicsExtractor graphics, int x, int y, int colour) {
		graphics.fill(x, y + 1, x + 3, y + 4, colour);
		graphics.fill(x + 1, y, x + 2, y + 5, colour);
	}

	/**
	 * Tab strip along the top of a panel section. The clan screen has more to say than fits
	 * in one 340×300 panel, and tabs keep the frame a fixed size instead of growing it.
	 *
	 * @param selected index of the active tab
	 * @return x position where each tab starts, so callers can hit-test clicks
	 */
	public static int[] drawTabs(
		GuiGraphicsExtractor graphics,
		Font font,
		Component[] labels,
		int left,
		int y,
		int width,
		int selected,
		int hovered
	) {
		int[] xs = new int[labels.length + 1];
		int tabW = width / Math.max(1, labels.length);
		for (int i = 0; i < labels.length; i++) {
			int tx = left + i * tabW;
			int tw = (i == labels.length - 1) ? (left + width - tx) : tabW;
			xs[i] = tx;
			boolean on = i == selected;
			graphics.fill(tx, y, tx + tw, y + 14, WOOD_DARK);
			graphics.fill(tx + 1, y + 1, tx + tw - 1, y + 13,
				on ? TAB_ACTIVE : (i == hovered ? ROW_WOOD_HOVER : ROW_WOOD));
			if (on) {
				// Lift the active tab with a bright top rim and a matching underline below.
				graphics.fill(tx + 1, y + 1, tx + tw - 1, y + 2, withAlpha(0xFFFFFF, 0.28F));
				graphics.fill(tx, y + 14, tx + tw, y + 15, LATCH);
			}
			String text = ellipsize(font, labels[i].getString(), tw - 8);
			drawCentered(graphics, font, text, tx + tw / 2, y + 4, on ? 0xFF2A1A0E : TEXT_LIGHT);
		}
		xs[labels.length] = left + width;
		return xs;
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
