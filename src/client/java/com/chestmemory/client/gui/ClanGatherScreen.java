package com.chestmemory.client.gui;

import com.chestmemory.client.clan.ClanCodes;
import com.chestmemory.client.clan.ClanSession;
import com.chestmemory.client.clan.ClanSessionManager;
import com.chestmemory.client.data.ModSettings;
import com.chestmemory.client.litematica.LitematicaAccess;
import com.chestmemory.client.util.ClientScreens;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Clan gather: set hub URL, create session (code), join by code, leave.
 */
public class ClanGatherScreen extends Screen {
	private final Screen parent;
	private EditBox hubBox;
	private EditBox tokenBox;
	private EditBox codeBox;
	private String status = "";
	private int panelLeft;
	private int panelTop;
	private int panelW;
	private int panelH;

	public ClanGatherScreen(Screen parent) {
		super(Component.translatable("screen.chestmemory.clan.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		this.panelW = ChestGuiStyle.panelWidth(this.width);
		this.panelH = ChestGuiStyle.panelHeight(this.height);
		this.panelLeft = (this.width - this.panelW) / 2;
		this.panelTop = (this.height - this.panelH) / 2;

		int left = this.panelLeft + 12;
		int w = this.panelW - 24;
		int y = this.panelTop + ChestGuiStyle.HEADER_H + 8;
		int rowH = 18;
		int gap = 4;

		// Hub URL
		this.hubBox = new EditBox(this.font, left, y, w, rowH, Component.translatable("screen.chestmemory.clan.hub"));
		this.hubBox.setMaxLength(256);
		this.hubBox.setHint(Component.translatable("screen.chestmemory.clan.hub_hint"));
		this.hubBox.setValue(ModSettings.get().clanHubUrl());
		this.addRenderableWidget(this.hubBox);
		y += rowH + gap;

		// Token (optional)
		this.tokenBox = new EditBox(this.font, left, y, w, rowH, Component.translatable("screen.chestmemory.clan.token"));
		this.tokenBox.setMaxLength(128);
		this.tokenBox.setHint(Component.translatable("screen.chestmemory.clan.token_hint"));
		this.tokenBox.setValue(ModSettings.get().clanToken());
		this.addRenderableWidget(this.tokenBox);
		y += rowH + gap;

		this.addRenderableWidget(Button.builder(
			Component.translatable("screen.chestmemory.clan.save_hub"),
			btn -> {
				ModSettings.get().setClanHubUrl(this.hubBox.getValue());
				ModSettings.get().setClanToken(this.tokenBox.getValue());
				this.status = Component.translatable("screen.chestmemory.clan.hub_saved").getString();
			}
		).bounds(left, y, w, rowH).build());
		y += rowH + gap + 2;

		boolean in = ClanSessionManager.isInSession();
		int half = (w - gap) / 2;

		if (!in) {
			this.addRenderableWidget(Button.builder(
				Component.translatable("screen.chestmemory.clan.create"),
				btn -> {
					saveHubQuiet();
					if (this.minecraft == null) {
						return;
					}
					if (!LitematicaAccess.hasActiveMaterialList()) {
						this.status = Component.translatable("screen.chestmemory.status.litematica_no_list").getString();
						return;
					}
					this.status = Component.translatable("screen.chestmemory.clan.working").getString();
					ClanSessionManager.createAsync(this.minecraft, this::rebuildWidgets);
				}
			).bounds(left, y, half, rowH).build());

			this.codeBox = new EditBox(this.font, left + half + gap, y, half, rowH,
				Component.translatable("screen.chestmemory.clan.code"));
			this.codeBox.setMaxLength(16);
			this.codeBox.setHint(Component.literal("CM-XXXX"));
			this.addRenderableWidget(this.codeBox);
			y += rowH + gap;

			this.addRenderableWidget(Button.builder(
				Component.translatable("screen.chestmemory.clan.join"),
				btn -> {
					saveHubQuiet();
					if (this.minecraft == null) {
						return;
					}
					String code = this.codeBox != null ? this.codeBox.getValue() : "";
					this.status = Component.translatable("screen.chestmemory.clan.working").getString();
					ClanSessionManager.joinAsync(this.minecraft, code, this::rebuildWidgets);
				}
			).bounds(left, y, w, rowH).build());
			y += rowH + gap;
		} else {
			ClanSession s = ClanSessionManager.session();
			String code = s != null ? s.code : "?";
			this.addRenderableWidget(Button.builder(
				Component.translatable("screen.chestmemory.clan.say_code", code),
				btn -> {
					if (this.minecraft != null && this.minecraft.player != null && s != null) {
						// Prefer client-side system message + clipboard; chat send may be blocked
						this.minecraft.player.connection.sendChat("ChestMemory сбор: " + s.code);
						this.status = Component.translatable("screen.chestmemory.clan.code_sent").getString();
					}
				}
			).bounds(left, y, w, rowH).build());
			y += rowH + gap;

			this.addRenderableWidget(Button.builder(
				Component.translatable("screen.chestmemory.clan.copy_code"),
				btn -> {
					if (this.minecraft != null && s != null) {
						this.minecraft.keyboardHandler.setClipboard(s.code);
						this.status = Component.translatable("screen.chestmemory.clan.copied", s.code).getString();
					}
				}
			).bounds(left, y, half, rowH).build());

			boolean host = this.minecraft != null && ClanSessionManager.isHost(this.minecraft);
			this.addRenderableWidget(Button.builder(
				Component.translatable(host
					? "screen.chestmemory.clan.close_session"
					: "screen.chestmemory.clan.leave"),
				btn -> {
					if (this.minecraft == null) {
						return;
					}
					this.status = Component.translatable("screen.chestmemory.clan.working").getString();
					ClanSessionManager.leaveAsync(this.minecraft, this::rebuildWidgets);
				}
			).bounds(left + half + gap, y, half, rowH).build());
			y += rowH + gap;
		}

		this.addRenderableWidget(Button.builder(
			Component.translatable("screen.chestmemory.clan.back"),
			btn -> this.onClose()
		).bounds(left, this.panelTop + this.panelH - 26, w, rowH).build());
	}

	private void saveHubQuiet() {
		if (this.hubBox != null) {
			ModSettings.get().setClanHubUrl(this.hubBox.getValue());
		}
		if (this.tokenBox != null) {
			ModSettings.get().setClanToken(this.tokenBox.getValue());
		}
	}

	@Override
	public void onClose() {
		saveHubQuiet();
		if (this.minecraft != null) {
			ClientScreens.set(this.minecraft, this.parent);
		}
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		graphics.fill(0, 0, this.width, this.height, ChestGuiStyle.VIGNETTE);
		ChestGuiStyle.drawChestPanel(graphics, this.panelLeft, this.panelTop, this.panelW, this.panelH);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		super.extractRenderState(graphics, mouseX, mouseY, a);
		ChestGuiStyle.drawCentered(
			graphics, this.font, this.title,
			this.panelLeft + this.panelW / 2, this.panelTop + 10,
			ChestGuiStyle.TEXT_TITLE
		);

		int left = this.panelLeft + 12;
		int y = this.panelTop + this.panelH - 48;
		String line;
		if (ClanSessionManager.isInSession()) {
			ClanSession s = ClanSessionManager.session();
			if (s != null) {
				int need = s.totalNeed();
				int del = s.totalDelivered();
				int pct = need > 0 ? (int) (100L * del / need) : 0;
				int members = s.members != null ? s.members.size() : 0;
				line = Component.translatable(
					"screen.chestmemory.clan.status_in",
					s.code, members, del, need, pct
				).getString();
			} else {
				line = "";
			}
		} else if (!ClanSessionManager.isConfigured() && (this.hubBox == null || this.hubBox.getValue().isBlank())) {
			line = Component.translatable("screen.chestmemory.clan.status_need_hub").getString();
		} else {
			line = Component.translatable("screen.chestmemory.clan.status_ready").getString();
		}
		if (!this.status.isBlank()) {
			line = this.status;
		}
		graphics.text(
			this.font,
			ChestGuiStyle.ellipsize(this.font, line, this.panelW - 24),
			left, y,
			ChestGuiStyle.TEXT_MUTED,
			false
		);
	}
}
