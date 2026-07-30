package com.chestmemory.client.litematica;

import com.chestmemory.ChestMemoryMod;
import com.chestmemory.client.data.ModSettings;
import com.chestmemory.client.data.StagingPickMode;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Minimal gather HUD: current item + four clear counts, no clutter.
 */
public final class BuildGatherHud {
	private static final int BOX_W = 160;
	private static final int BOX_X = 6;
	private static final int PAD_X = 6;
	private static final int PAD_Y = 5;
	private static final int LINE_H = 11;
	/** Width reserved for the left label column (e.g. «Инв.»). */
	private static final int LABEL_W = 52;

	private BuildGatherHud() {
	}

	public static void register() {
		HudElementRegistry.addLast(ChestMemoryMod.id("build_gather_hud"), BuildGatherHud::render);
	}

	private static void render(GuiGraphicsExtractor graphics, DeltaTracker delta) {
		if (!BuildGatherSession.isActive()) {
			return;
		}
		if (!com.chestmemory.client.data.ModSettings.get().showGatherHud()) {
			return;
		}
		Minecraft client = Minecraft.getInstance();
		if (client == null || client.player == null) {
			return;
		}
		if (com.chestmemory.client.util.ClientScreens.get(client) != null) {
			return;
		}
		// F3 fills the same corners with vanilla debug text; stay out of its way.
		if (client.getDebugOverlay() != null && client.getDebugOverlay().showDebugScreen()) {
			return;
		}

		List<BuildGatherSession.HudLine> lines = BuildGatherSession.hudLines();
		BuildGatherSession.HudLine current = null;
		for (BuildGatherSession.HudLine l : lines) {
			if (l.current()) {
				current = l;
				break;
			}
		}
		if (current == null && lines.isEmpty() && BuildGatherSession.listName() == null) {
			return;
		}

		Font font = client.font;
		int x = BOX_X;
		int y = 8;
		int textMax = BOX_W - PAD_X * 2;

		List<Row> rows = new ArrayList<>();

		// Short header only
		String title = BuildGatherSession.listName() != null
			? Component.translatable("hud.chestmemory.build_title", BuildGatherSession.listName()).getString()
			: Component.translatable("hud.chestmemory.build_title_generic").getString();
		int accent = 0xFF000000 | ModSettings.get().hudAccentColor();
		int titleCol = 0xFF000000 | ModSettings.get().hudTitleColor();
		rows.add(new Row(ellipsize(font, title, textMax), titleCol, false));

		// Litematica drops its material list on a world load, so while we are away from the
		// schematic's world the counts come from our own copy and stop tracking blocks placed.
		// Say so, otherwise frozen numbers read as a bug.
		if (LitematicaAccess.isAwayFromSchematic()) {
			rows.add(new Row(
				ellipsize(font, Component.translatable("hud.chestmemory.list_cached").getString(), textMax),
				0xFFFFC864,
				false
			));
		}

		if (com.chestmemory.client.clan.ClanSessionManager.isInSession()) {
			var cs = com.chestmemory.client.clan.ClanSessionManager.session();
			if (cs != null) {
				int need = cs.totalNeed();
				int del = cs.totalDelivered();
				int pct = need > 0 ? (int) (100L * del / need) : 0;
				rows.add(new Row(
					ellipsize(font, Component.translatable(
						"hud.chestmemory.clan",
						cs.code, pct
					).getString(), textMax),
					0xFF80D0FF,
					false
				));
			}
		}

		if (StagingPickMode.isActive()) {
			rows.add(new Row(
				ellipsize(font, Component.translatable("hud.chestmemory.staging_pick").getString(), textMax),
				0xFFE080FF,
				false
			));
		}

		if (current != null) {
			// Item name
			rows.add(new Row(ellipsize(font, current.displayName(), textMax), 0xFFFFFFFF, false));

			// Who claimed this material in clan session
			if (com.chestmemory.client.clan.ClanSessionManager.isInSession()) {
				Minecraft mc = client;
				if (com.chestmemory.client.clan.ClanSessionManager.isClaimedByMe(mc, current.itemId())) {
					rows.add(new Row(
						ellipsize(font, Component.translatable("hud.chestmemory.clan_you").getString(), textMax),
						0xFFFFE080,
						false
					));
				} else if (com.chestmemory.client.clan.ClanSessionManager.isClaimedByOther(mc, current.itemId())) {
					String who = com.chestmemory.client.clan.ClanSessionManager.claimName(current.itemId());
					rows.add(new Row(
						ellipsize(font, Component.translatable(
							"hud.chestmemory.clan_who",
							who != null ? who : "?"
						).getString(), textMax),
						0xFFE080FF,
						false
					));
				} else {
					rows.add(new Row(
						ellipsize(font, Component.translatable("hud.chestmemory.clan_free").getString(), textMax),
						0xFF88CC88,
						false
					));
				}
			}

			// Aligned stat rows: label | value
			// Remainder AND total, on one line. "Нужно ×1600" could not say whether 1600 was
			// the whole job or the tail of 31096, and at that size the difference is the
			// whole question. A total of zero means nothing recorded it — the schematic, the
			// snapshot and the clan need were all silent — so the bare remainder stays.
			String needVal = current.total() > 0
				? Component.translatable(
					"hud.chestmemory.val_need_of",
					formatCount(current.missing()),
					formatCount(current.total())
				).getString()
				: formatCount(current.missing());
			rows.add(stat(font, "hud.chestmemory.lbl_need", needVal,
				current.missing() > 0 ? 0xFFFFE066 : 0xFF70E090));
			// The same number in stacks, and in boxes once there is a whole one. Standing at a
			// chest, "×1600" is not the question — "how much do I take" is, and that is counted
			// in stacks. Tools and boats have no stack tier and simply get no line.
			String bulk = bulkText(current.itemId(), current.missing());
			if (!bulk.isEmpty()) {
				rows.add(stat(font, "hud.chestmemory.lbl_bulk", bulk, 0xFFBFBFBF));
			}
			rows.add(stat(font, "hud.chestmemory.lbl_inv", formatCount(current.inPlayer()),
				current.inPlayer() > 0 ? 0xFF70E090 : 0xFF888888));
			rows.add(stat(font, "hud.chestmemory.lbl_staging", formatCount(current.inStaging()),
				current.inStaging() > 0 ? 0xFFC090FF : 0xFF777777));

			if (current.inChests() > 0) {
				String val = current.nearestDist() >= 0
					? Component.translatable(
						"hud.chestmemory.val_chests_dist",
						current.inChests(),
						(int) current.nearestDist()
					).getString()
					: formatCount(current.inChests());
				rows.add(stat(font, "hud.chestmemory.lbl_chests", val, 0xFF70C8FF));
			} else {
				rows.add(stat(font, "hud.chestmemory.lbl_chests",
					Component.translatable("hud.chestmemory.val_chests_none").getString(),
					0xFFFF8866));
			}
		} else {
			rows.add(new Row(
				ellipsize(font, Component.translatable("hud.chestmemory.no_target").getString(), textMax),
				0xFFAAAAAA,
				false
			));
		}

		// This material's own progress. The list-wide bar below answers "how far through the
		// build am I"; standing at a chest the question is "how far through the glass am I",
		// and nothing on the HUD answered it.
		if (current != null && current.total() > 0) {
			rows.add(Row.bar(
				current.done() / (float) current.total(),
				Component.translatable(
					"hud.chestmemory.item_progress",
					(int) (100L * current.done() / current.total())
				).getString()
			));
		}

		// Overall progress of the whole list, not just the current material: the HUD could say
		// "нужно ×1600" all evening with no sense of whether that was the last item or the
		// first of forty.
		int allNeed = BuildGatherSession.hudTotalNeed();
		int allDone = BuildGatherSession.hudTotalDone();
		Row overall = null;
		if (allNeed > 0) {
			overall = Row.bar(allDone / (float) allNeed, Component.translatable(
				"hud.chestmemory.overall", (int) (100L * allDone / allNeed)
			).getString());
			rows.add(overall);
		}

		// Only N — no P in HUD
		rows.add(new Row(
			ellipsize(font, Component.translatable("hud.chestmemory.keys_short").getString(), textMax),
			0xFF707070,
			false
		));

		// Compact mode: the current material and the overall bar, nothing else. For players who
		// want to know what they are on without a panel sitting over the world.
		if (ModSettings.get().gatherHudCompact()) {
			List<Row> slim = new ArrayList<>(2);
			if (current != null) {
				String head = current.total() > 0
					? current.displayName() + "  " + Component.translatable(
						"hud.chestmemory.val_need_of",
						formatCount(current.missing()),
						formatCount(current.total())
					).getString()
					: current.displayName() + "  " + formatCount(current.missing());
				slim.add(new Row(ellipsize(font, head, textMax), 0xFFFFFFFF, false));
			}
			// Explicitly the list-wide bar, not "whichever bar comes first": the head line
			// above already carries this material's own numbers, so the useful bar here is
			// the one about the whole build.
			if (overall != null) {
				slim.add(overall);
			}
			if (slim.isEmpty()) {
				// Nothing to be compact about — say the one thing that is true.
				slim.add(new Row(
					ellipsize(font, Component.translatable("hud.chestmemory.no_target").getString(), textMax),
					0xFFAAAAAA, false
				));
			}
			rows = slim;
		}

		int boxH = PAD_Y * 2 + rows.size() * LINE_H + 2;

		// Scale the whole box: a HUD that reads well on one monitor is tiny on a 4K screen and
		// overbearing at 720p, and this is the setting people reach for first.
		float scale = ModSettings.get().gatherHudScalePct() / 100F;
		boolean scaled = Math.abs(scale - 1F) > 0.001F;

		// Place in the configured corner. The HUD used to be nailed to the top-left, where it
		// fought with the F3 overlay and with other mods' HUDs.
		//
		// The corner maths uses the box's size ON SCREEN, which is the scaled size — measuring
		// the unscaled one left a half-box gap at the bottom and right edges as soon as the
		// scale was not 100%. The result is then divided back into the scaled coordinate space
		// the matrix draws in.
		int screenW = graphics.guiWidth();
		int screenH = graphics.guiHeight();
		int drawW = Math.round(BOX_W * scale);
		int drawH = Math.round(boxH * scale);
		switch (ModSettings.get().gatherHudCorner()) {
			case 1 -> x = screenW - drawW - BOX_X;
			case 2 -> y = screenH - drawH - 8;
			case 3 -> {
				x = screenW - drawW - BOX_X;
				y = screenH - drawH - 8;
			}
			default -> {
				// top-left, as before
			}
		}
		if (scaled) {
			graphics.pose().pushMatrix();
			graphics.pose().scale(scale, scale);
			x = Math.round(x / scale);
			y = Math.round(y / scale);
		}

		// Soft background, single thin border (accent from settings)
		graphics.fill(x, y, x + BOX_W, y + boxH, 0xC0121218);
		graphics.fill(x, y, x + BOX_W, y + 1, accent);
		graphics.fill(x, y + boxH - 1, x + BOX_W, y + boxH, (0x66000000) | (accent & 0x00FFFFFF));
		graphics.fill(x, y, x + 1, y + boxH, (0x66000000) | (accent & 0x00FFFFFF));
		graphics.fill(x + BOX_W - 1, y, x + BOX_W, y + boxH, (0x66000000) | (accent & 0x00FFFFFF));

		int ly = y + PAD_Y;
		for (Row row : rows) {
			if (row.isBar()) {
				// Bar first, its label sitting on top of it: two separate lines for "how far
				// along" would cost a fifth of the box's height to say one thing.
				int barW = BOX_W - PAD_X * 2;
				com.chestmemory.client.gui.ChestGuiStyle.drawProgressBar(
					graphics, x + PAD_X, ly + 1, barW, LINE_H - 3, row.fill
				);
				int tw = font.width(row.text);
				graphics.text(font, row.text, x + PAD_X + (barW - tw) / 2, ly + 1, 0xFFFFFFFF, true);
				ly += LINE_H;
				continue;
			}
			if (row.split) {
				graphics.text(font, row.label, x + PAD_X, ly, 0xFFA0A0A0, false);
				int vx = x + PAD_X + LABEL_W;
				graphics.text(font, row.text, vx, ly, row.color, false);
			} else {
				graphics.text(font, row.text, x + PAD_X, ly, row.color, false);
			}
			ly += LINE_H;
		}
		if (scaled) {
			graphics.pose().popMatrix();
		}
	}

	private static Row stat(Font font, String labelKey, String value, int valueColor) {
		String label = Component.translatable(labelKey).getString();
		// Keep value short enough for remaining width
		int valMax = BOX_W - PAD_X * 2 - LABEL_W;
		return new Row(ellipsize(font, value, valMax), valueColor, true, ellipsize(font, label, LABEL_W - 2));
	}

	private static String formatCount(int n) {
		return "×" + n;
	}

	private static String ellipsize(Font font, String text, int maxW) {
		if (text == null) {
			return "";
		}
		if (maxW <= 0 || font.width(text) <= maxW) {
			return text;
		}
		while (text.length() > 3 && font.width(text + "…") > maxW) {
			text = text.substring(0, text.length() - 1);
		}
		return text + "…";
	}

	/**
	 * One HUD line: plain text, a label/value pair, or a progress bar.
	 *
	 * @param fill 0..1 for a bar row, negative for a text row
	 */
	private record Row(String text, int color, boolean split, String label, float fill) {
		Row(String text, int color, boolean split) {
			this(text, color, split, "", -1F);
		}

		Row(String text, int color, boolean split, String label) {
			this(text, color, split, label, -1F);
		}

		static Row bar(float fill, String text) {
			return new Row(text, 0xFFD8D8D8, false, "", Math.max(0F, Math.min(1F, fill)));
		}

		boolean isBar() {
			return fill >= 0F;
		}
	}

	/** «25 ст.» / «1 ШБ + 3 ст.», or empty when the amount does not warrant either. */
	private static String bulkText(String itemId, int amount) {
		if (amount <= 0) {
			return "";
		}
		int per = 64;
		try {
			per = Math.max(1, com.chestmemory.client.data.ItemStackKeys.toStack(itemId).getMaxStackSize());
		} catch (Exception e) {
			// An unknown or removed item: fall back to a plain stack size rather than no line.
		}
		var bulk = com.chestmemory.client.data.BulkAmount.of(amount, per);
		if (bulk.hasBox()) {
			return com.chestmemory.client.gui.BulkTooltip.boxesText(bulk);
		}
		if (bulk.hasStack()) {
			return com.chestmemory.client.gui.BulkTooltip.stacksText(bulk);
		}
		return "";
	}
}
