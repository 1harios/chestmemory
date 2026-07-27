package com.chestmemory.client.gui;

import com.chestmemory.client.data.ModSettings;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;

/**
 * Shared look of every screen in the mod: a gray chest.
 * <p>
 * The structure is the chest block's — a rim with bevels, a field with subtle seams, and
 * the iron latch on the top edge — but the palette is the vanilla container GUI's grays
 * (#C6C6C6 field, dark rim, white/dark bevels), so the panel sits next to an open chest
 * screen as a sibling. One frame, one button face, one tab strip, one way to draw a count
 * in a slot — defined here once so the item panel, settings and clan screens read as one
 * interface; colour is reserved for information, not decoration.
 */
public final class ChestGuiStyle {
	public static final Identifier SLOT_SPRITE = Identifier.withDefaultNamespace("container/slot");

	// ── Text ── (ARGB — alpha MUST be non-zero for text in 26.2)
	/** Titles on the light field — vanilla screen-title gray. */
	public static final int TEXT_TITLE = 0xFF404040;
	public static final int TEXT_BODY = 0xFF404040;
	/** Captions on dark rows — vanilla button white. */
	public static final int TEXT_LIGHT = 0xFFFFFFFF;
	/** Emphasis (kept for HUD numbers). */
	public static final int TEXT_GOLD = 0xFFFFD56A;
	/** Right-aligned values on rows: present but quieter than the caption. */
	public static final int VALUE_TEXT = 0xFFCFCFCF;
	/** Secondary text on the light field. */
	public static final int TEXT_MUTED = 0xFF525252;
	public static final int TEXT_COUNT = 0xFFFFFFFF;
	public static final int TEXT_COUNT_SHADOW = 0xFF000000;

	// ── Frame / faces — the chest's structure in the container GUI's grays ──
	public static final int WOOD_DARK = 0xFF000000;
	/** The rim band around the field — dark gray, like the chest GUI's shadowed edge. */
	public static final int WOOD_MID = 0xFF555555;
	public static final int WOOD_LIGHT = 0xFFFFFFFF;
	/** Iron of the chest latch. */
	public static final int LATCH = 0xFFA8A8A8;
	/** The light field every screen sits on. */
	public static final int PANEL_INNER = 0xFFC6C6C6;
	/** Recessed border for trays and inset plates. */
	public static final int PANEL_BORDER = 0xFF373737;
	public static final int HEADER_BG = 0xFFC6C6C6;
	/** Dark half of an engraved seam (light half is PLANK_SEAM_LIGHT). */
	public static final int HEADER_LINE = 0xFF8B8B8B;
	/** Light half of an engraved seam. */
	public static final int PLANK_SEAM_LIGHT = 0xFFEFEFEF;
	/** Accent for scrollbar thumbs and slider fills — quiet iron, not paint. */
	public static final int BRASS = 0xFF8B8B8B;
	public static final int BRASS_BRIGHT = 0xFFB8B8B8;
	public static final int ROW_HOVER = 0x66FFFFFF;
	public static final int ROW_BG = 0x33000000;
	public static final int BADGE_BG = 0xEE1C1C1C;
	public static final int BADGE_BORDER = 0xFFA0A0A0;
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
	 * The window frame, built like the chest block itself: a dark outline, a wooden rim
	 * with bevels, an oak-plank field with visible plank seams — and the iron latch
	 * centered on the top edge, which is what makes a box read as a chest.
	 */
	public static void drawChestPanel(GuiGraphicsExtractor graphics, int left, int top, int width, int height) {
		int l = left - 4;
		int t = top - 4;
		int r = left + width + 4;
		int b = top + height + 4;

		// Outline with cut corners
		graphics.fill(l + 1, t, r - 1, b, WOOD_DARK);
		graphics.fill(l, t + 1, r, b - 1, WOOD_DARK);
		// Wooden rim band
		graphics.fill(l + 1, t + 1, r - 1, b - 1, WOOD_MID);
		graphics.fill(l + 1, t + 1, r - 2, t + 2, WOOD_LIGHT);
		graphics.fill(l + 1, t + 1, l + 2, b - 2, WOOD_LIGHT);
		graphics.fill(l + 2, b - 2, r - 1, b - 1, 0xFF3A3A3A);
		graphics.fill(r - 2, t + 2, r - 1, b - 1, 0xFF3A3A3A);
		// Seam between rim and field, then the plank field itself
		graphics.fill(left - 1, top - 1, left + width + 1, top + height + 1, PANEL_BORDER);
		graphics.fill(left, top, left + width, top + height, PANEL_INNER);

		// Plank seams across the field — subtle, so content stays readable on top
		for (int py = top + HEADER_H + 12; py < top + height - 8; py += 16) {
			graphics.fill(left + 3, py, left + width - 3, py + 1, withAlpha(0x000000, 0.10F));
			graphics.fill(left + 3, py + 1, left + width - 3, py + 2, withAlpha(0xFFFFFF, 0.07F));
		}

		// Engraved groove under the header band
		graphics.fill(left + 6, top + HEADER_H - 2, left + width - 6, top + HEADER_H - 1, HEADER_LINE);
		graphics.fill(left + 6, top + HEADER_H - 1, left + width - 6, top + HEADER_H, PLANK_SEAM_LIGHT);

		// The iron latch, centered on the top rim — the chest's signature
		int midX = left + width / 2;
		graphics.fill(midX - 8, top - 6, midX + 8, top + 3, 0xFF2B2B2B);
		graphics.fill(midX - 7, top - 5, midX + 7, top + 2, LATCH);
		graphics.fill(midX - 7, top - 5, midX + 7, top - 4, 0xFFD8D8D8);
		graphics.fill(midX - 1, top - 2, midX + 1, top + 2, 0xFF2B2B2B);
	}

	public static void drawSlot(GuiGraphicsExtractor graphics, int x, int y) {
		graphics.blitSprite(RenderPipelines.GUI_TEXTURED, SLOT_SPRITE, x, y, 18, 18);
	}

	/**
	 * Section heading: dark caption with an engraved rule running to the right edge —
	 * quiet structure, no plates.
	 */
	public static void drawSectionHeader(
		GuiGraphicsExtractor graphics,
		Font font,
		Component title,
		int left,
		int y,
		int width
	) {
		graphics.text(font, title, left, y + 1, TEXT_TITLE, false);
		int ruleLeft = left + font.width(title) + 6;
		int ruleRight = left + width;
		if (ruleRight > ruleLeft) {
			graphics.fill(ruleLeft, y + 4, ruleRight, y + 5, HEADER_LINE);
			graphics.fill(ruleLeft, y + 5, ruleRight, y + 6, PLANK_SEAM_LIGHT);
		}
	}

	/** Gray button faces, one step darker than the field. */
	public static final int ROW_WOOD = 0xFF6E6E6E;
	public static final int ROW_WOOD_HOVER = 0xFF7F7F7F;
	/**
	 * A neutral palette cannot drain saturation to say "dead", so a disabled face leans
	 * on brightness alone — clearly darker — plus the flatness (no bevel, see
	 * {@link #drawSettingRow}).
	 */
	public static final int ROW_WOOD_DISABLED = 0xFF464646;

	/** Caption of a disabled row: 5:1 on the disabled face — readable, clearly dimmer. */
	public static final int TEXT_DISABLED = 0xFFBDBDBD;

	/** Secondary text on a button face. */
	public static final int TEXT_ON_WOOD_MUTED = 0xFFE2E2E2;

	/** Face of the selected tab (legacy constant; the tab strip no longer draws boxes). */
	public static final int TAB_ACTIVE = 0xFFE0E0E0;

	/**
	 * A row that acts as a button: gray face, white caption, white outline on hover.
	 * Disabled rows go dark and flat.
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
		int border = !enabled ? PANEL_BORDER : (hovered ? TEXT_LIGHT : WOOD_DARK);
		int face = !enabled ? ROW_WOOD_DISABLED : (hovered ? ROW_WOOD_HOVER : ROW_WOOD);
		graphics.fill(x, y, x + width, y + height, border);
		graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, face);
		if (enabled) {
			// The bevel is what makes a row look pressable, so a disabled row goes flat —
			// colour alone is a weak signal, and a flat plate reads as inert immediately.
			graphics.fill(x + 1, y + 1, x + width - 1, y + 2, withAlpha(0xFFFFFF, hovered ? 0.20F : 0.12F));
			graphics.fill(x + 1, y + height - 2, x + width - 1, y + height - 1, withAlpha(0x000000, 0.22F));
		}
	}

	/**
	 * Progress bar in the panel's palette.
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
		graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, 0xFF2A2A2A);
		int fill = (int) ((width - 2) * f);
		if (fill > 0) {
			// Amber while gathering, green once everything is in — colour as information.
			int colour = f >= 1F ? 0xFF5FD068 : 0xFFE0A83C;
			graphics.fill(x + 1, y + 1, x + 1 + fill, y + height - 1, colour);
			// Top highlight so the bar reads as raised, matching the row style.
			graphics.fill(x + 1, y + 1, x + 1 + fill, y + 2, withAlpha(0xFFFFFF, 0.25F));
		}
	}

	/** Big session code, drawn as a dark plate the host can read out loud. */
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
		graphics.fill(x + 1, y + 1, x + w - 1, y + 19, 0xFF2E2E2E);
		graphics.fill(x + 1, y + 1, x + w - 1, y + 2, withAlpha(0xFFFFFF, 0.16F));
		graphics.text(font, code, centerX - textW / 2 + 1, y + 7, 0xFF000000, false);
		graphics.text(font, code, centerX - textW / 2, y + 6, TEXT_LIGHT, false);
	}

	/**
	 * One row of the clan roster: name on the left, what they are carrying on the right.
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
		graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, 0xFF3A3A3A);
		int cy = y + height / 2;
		// Lamp with a dark rim, so it stays visible against the plate.
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
	 * Recessed tray behind a grid of item slots: dark border, light gray face — items on
	 * this read as an inventory instead of loose icons.
	 */
	public static void drawGridTray(
		GuiGraphicsExtractor graphics, int x, int y, int width, int height
	) {
		graphics.fill(x, y, x + width, y + height, PANEL_BORDER);
		graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, PANEL_INNER);
	}

	// ── Slot counts ─────────────────────────────────────────────────────────

	/** Count text style: how the number sits over the item icon. */
	public static final int COUNT_STYLE_SHADOW = 0;
	public static final int COUNT_STYLE_OUTLINE = 1;
	public static final int COUNT_STYLE_PLATE = 2;
	public static final int COUNT_STYLE_PLAIN = 3;

	/**
	 * Count in the corner of an 18px slot, drawn with the player's configured size, style
	 * and colour (see settings → panel).
	 *
	 * @param colour explicit state colour (build mode tints), or 0 to use the configured one
	 */
	public static void drawSlotCount(
		GuiGraphicsExtractor graphics, Font font, String text, int slotX, int slotY, int colour
	) {
		ModSettings s = ModSettings.get();
		int c = colour != 0 ? colour : 0xFF000000 | s.slotCountColor();
		drawSlotCountStyled(graphics, font, text, slotX, slotY, c,
			s.slotCountScalePct() / 100F, s.slotCountStyle());
	}

	/**
	 * The actual renderer — parameters explicit, so the settings preview can show any
	 * combination before it is applied.
	 */
	public static void drawSlotCountStyled(
		GuiGraphicsExtractor graphics,
		Font font,
		String text,
		int slotX,
		int slotY,
		int colour,
		float scale,
		int style
	) {
		int textW = font.width(text);
		float drawX = Math.max(slotX + 1, slotX + 17 - textW * scale);
		float drawY = slotY + 17 - 7.2F * scale;
		graphics.pose().pushMatrix();
		graphics.pose().translate(drawX, drawY);
		graphics.pose().scale(scale, scale);
		switch (style) {
			case COUNT_STYLE_PLATE -> {
				graphics.fill(-1, -1, textW + 1, 8, 0xB8000000);
				graphics.text(font, text, 0, 0, colour, false);
			}
			case COUNT_STYLE_PLAIN -> graphics.text(font, text, 0, 0, colour, false);
			case COUNT_STYLE_SHADOW -> {
				graphics.text(font, text, 1, 1, 0xE0000000, false);
				graphics.text(font, text, 0, 0, colour, false);
			}
			// Outline: readable over any icon — dark rim on all four sides.
			default -> {
				graphics.text(font, text, 1, 0, 0xE0000000, false);
				graphics.text(font, text, -1, 0, 0xE0000000, false);
				graphics.text(font, text, 0, 1, 0xE0000000, false);
				graphics.text(font, text, 0, -1, 0xE0000000, false);
				graphics.text(font, text, 0, 0, colour, false);
			}
		}
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
	 * Small coloured dot used by the activity feed to mark the kind of event.
	 */
	public static void drawEventDot(GuiGraphicsExtractor graphics, int x, int y, int colour) {
		graphics.fill(x, y + 1, x + 3, y + 4, colour);
		graphics.fill(x + 1, y, x + 2, y + 5, colour);
	}

	// ── Tabs ────────────────────────────────────────────────────────────────

	/**
	 * Tab boundaries: widths follow each label's text instead of dividing space evenly,
	 * so «Подсветка» is not squeezed to the same box as «Вид». When everything fits, tabs
	 * are left-aligned at their natural size; when it does not, widths shrink
	 * proportionally so the strip always spans exactly {@code width}.
	 *
	 * @return n+1 x positions; tab i spans [xs[i], xs[i+1])
	 */
	public static int[] tabBounds(Font font, Component[] labels, int left, int width) {
		int n = labels.length;
		int[] xs = new int[n + 1];
		xs[0] = left;
		int[] natural = new int[n];
		int sum = 0;
		for (int i = 0; i < n; i++) {
			natural[i] = font.width(labels[i]) + 16;
			sum += natural[i];
		}
		if (sum <= width) {
			for (int i = 0; i < n; i++) {
				xs[i + 1] = xs[i] + natural[i];
			}
			return xs;
		}
		int acc = 0;
		for (int i = 0; i < n; i++) {
			int w = i == n - 1 ? width - acc : Math.round(width * (float) natural[i] / sum);
			xs[i + 1] = xs[i] + w;
			acc += w;
		}
		return xs;
	}

	/** Tab index under the pointer, or -1. Shares geometry with {@link #drawTabs}. */
	public static int tabIndexAt(
		Font font, Component[] labels, int left, int width, int tabsY, double mx, double my
	) {
		if (my < tabsY || my > tabsY + 14) {
			return -1;
		}
		int[] xs = tabBounds(font, labels, left, width);
		for (int i = 0; i < labels.length; i++) {
			if (mx >= xs[i] && mx < xs[i + 1]) {
				return i;
			}
		}
		return -1;
	}

	/**
	 * Tab strip: quiet text labels over an engraved baseline. The active tab is dark with
	 * a solid underline that interrupts the baseline; hover is a hint of the same. No
	 * boxes, no plates — the strip structures the panel instead of decorating it.
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
		int[] xs = tabBounds(font, labels, left, width);
		// Engraved plank seam as the baseline
		graphics.fill(left, y + 12, left + width, y + 13, HEADER_LINE);
		graphics.fill(left, y + 13, left + width, y + 14, PLANK_SEAM_LIGHT);
		for (int i = 0; i < labels.length; i++) {
			int tx = xs[i];
			int tw = xs[i + 1] - tx;
			boolean on = i == selected;
			if (on) {
				// The active tab's underline replaces the baseline segment beneath it.
				graphics.fill(tx + 2, y + 12, tx + tw - 2, y + 14, TEXT_TITLE);
			} else if (i == hovered) {
				graphics.fill(tx + 3, y + 12, tx + tw - 3, y + 13, TEXT_MUTED);
			}
			int color = on ? TEXT_TITLE : (i == hovered ? 0xFF565656 : 0xFF757575);
			String text = ellipsize(font, labels[i].getString(), tw - 8);
			drawCentered(graphics, font, text, tx + tw / 2, y + 2, color);
		}
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
