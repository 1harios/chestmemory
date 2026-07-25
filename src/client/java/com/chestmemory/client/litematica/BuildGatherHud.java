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
			rows.add(stat(font, "hud.chestmemory.lbl_need", formatCount(current.missing()),
				current.missing() > 0 ? 0xFFFFE066 : 0xFF70E090));
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

		// Only N — no P in HUD
		rows.add(new Row(
			ellipsize(font, Component.translatable("hud.chestmemory.keys_short").getString(), textMax),
			0xFF707070,
			false
		));

		int boxH = PAD_Y * 2 + rows.size() * LINE_H + 2;
		// Soft background, single thin border (accent from settings)
		graphics.fill(x, y, x + BOX_W, y + boxH, 0xC0121218);
		graphics.fill(x, y, x + BOX_W, y + 1, accent);
		graphics.fill(x, y + boxH - 1, x + BOX_W, y + boxH, (0x66000000) | (accent & 0x00FFFFFF));
		graphics.fill(x, y, x + 1, y + boxH, (0x66000000) | (accent & 0x00FFFFFF));
		graphics.fill(x + BOX_W - 1, y, x + BOX_W, y + boxH, (0x66000000) | (accent & 0x00FFFFFF));

		int ly = y + PAD_Y;
		for (Row row : rows) {
			if (row.split) {
				graphics.text(font, row.label, x + PAD_X, ly, 0xFFA0A0A0, false);
				int vx = x + PAD_X + LABEL_W;
				graphics.text(font, row.text, vx, ly, row.color, false);
			} else {
				graphics.text(font, row.text, x + PAD_X, ly, row.color, false);
			}
			ly += LINE_H;
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

	private record Row(String text, int color, boolean split, String label) {
		Row(String text, int color, boolean split) {
			this(text, color, split, "");
		}
	}
}
