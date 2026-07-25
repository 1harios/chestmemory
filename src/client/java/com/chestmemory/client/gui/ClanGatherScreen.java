package com.chestmemory.client.gui;

import com.chestmemory.client.clan.ClanCodes;
import com.chestmemory.client.clan.ClanDefaults;
import com.chestmemory.client.clan.ClanSession;
import com.chestmemory.client.clan.ClanSessionManager;
import com.chestmemory.client.data.ModSettings;
import com.chestmemory.client.litematica.LitematicaAccess;
import com.chestmemory.client.util.ClientScreens;
import net.minecraft.client.gui.GuiGraphicsExtractor;
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

		// When the build ships the clan's hub, members only ever type a session code —
		// no URL, no token. The manual fields appear only for a build without one.
		this.hubBox = null;
		this.tokenBox = null;
		if (ClanDefaults.hasBakedHub()) {
			this.addRenderableWidget(new SettingRowButton(
				left, y, w, rowH,
				Component.translatable("screen.chestmemory.clan.hub_builtin"),
				() -> {
				}
			) {
				@Override
				public void onClick(net.minecraft.client.input.MouseButtonEvent e, boolean d) {
					// Informational row, not a button.
				}
			});
			y += rowH + gap + 2;
		} else {
			this.hubBox = new EditBox(this.font, left, y, w, rowH, Component.translatable("screen.chestmemory.clan.hub"));
			this.hubBox.setMaxLength(256);
			this.hubBox.setHint(Component.translatable("screen.chestmemory.clan.hub_hint"));
			this.hubBox.setValue(ModSettings.get().clanHubUrl());
			this.addRenderableWidget(this.hubBox);
			y += rowH + gap;

			// No token field: the hub is protected by rate limiting and Mojang identity,
			// so members never paste a shared secret. A hub that still wants one can have
			// it baked in via -Pclan_hub_token.

			this.addRenderableWidget(new SettingRowButton(
				left, y, w, rowH,
				Component.translatable("screen.chestmemory.clan.save_hub"),
				() -> {
					ModSettings.get().setClanHubUrl(this.hubBox.getValue());
					ModSettings.get().setClanToken(this.tokenBox.getValue());
					this.status = Component.translatable("screen.chestmemory.clan.hub_saved").getString();
				}
			));
			y += rowH + gap + 2;
		}

		boolean in = ClanSessionManager.isInSession();
		int half = (w - gap) / 2;

		if (!in) {
			this.addRenderableWidget(new SettingRowButton(
				left, y, half, rowH,
				Component.translatable("screen.chestmemory.clan.create"),
				() -> {
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
			));

			this.codeBox = new EditBox(this.font, left + half + gap, y, half, rowH,
				Component.translatable("screen.chestmemory.clan.code"));
			this.codeBox.setMaxLength(16);
			this.codeBox.setHint(Component.literal("CM-XXXX"));
			this.addRenderableWidget(this.codeBox);
			y += rowH + gap;

			this.addRenderableWidget(new SettingRowButton(
				left, y, w, rowH,
				Component.translatable("screen.chestmemory.clan.join"),
				() -> {
					saveHubQuiet();
					if (this.minecraft == null) {
						return;
					}
					String code = this.codeBox != null ? this.codeBox.getValue() : "";
					this.status = Component.translatable("screen.chestmemory.clan.working").getString();
					ClanSessionManager.joinAsync(this.minecraft, code, this::rebuildWidgets);
				}
			));
			y += rowH + gap;
		} else {
			ClanSession s = ClanSessionManager.session();
			String code = s != null ? s.code : "?";
			this.addRenderableWidget(new SettingRowButton(
				left, y, w, rowH,
				Component.translatable("screen.chestmemory.clan.say_code", code),
				() -> {
					if (this.minecraft != null && this.minecraft.player != null && s != null) {
						// Prefer client-side system message + clipboard; chat send may be blocked
						this.minecraft.player.connection.sendChat("ChestMemory сбор: " + s.code);
						this.status = Component.translatable("screen.chestmemory.clan.code_sent").getString();
					}
				}
			));
			y += rowH + gap;

			this.addRenderableWidget(new SettingRowButton(
				left, y, half, rowH,
				Component.translatable("screen.chestmemory.clan.copy_code"),
				() -> {
					if (this.minecraft != null && s != null) {
						this.minecraft.keyboardHandler.setClipboard(s.code);
						this.status = Component.translatable("screen.chestmemory.clan.copied", s.code).getString();
					}
				}
			));

			boolean host = this.minecraft != null && ClanSessionManager.isHost(this.minecraft);
			this.addRenderableWidget(new SettingRowButton(
				left + half + gap, y, half, rowH,
				Component.translatable(host
					? "screen.chestmemory.clan.close_session"
					: "screen.chestmemory.clan.leave"),
				() -> {
					if (this.minecraft == null) {
						return;
					}
					this.status = Component.translatable("screen.chestmemory.clan.working").getString();
					ClanSessionManager.leaveAsync(this.minecraft, this::rebuildWidgets);
				}
			));
			y += rowH + gap;
		}

		this.addRenderableWidget(new SettingRowButton(
			left, this.panelTop + this.panelH - 26, w, rowH,
			Component.translatable("screen.chestmemory.clan.back"),
			this::onClose
		));
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
		int centerX = this.panelLeft + this.panelW / 2;
		int contentW = this.panelW - 24;

		if (ClanSessionManager.isInSession()) {
			ClanSession s = ClanSessionManager.session();
			if (s != null) {
				// The code is the one thing the host reads out loud, so it gets a plate
				// of its own instead of being buried in a status sentence.
				ChestGuiStyle.drawCodePlate(graphics, this.font, s.code, centerX, this.panelTop + 22, 90);

				int need = s.totalNeed();
				int delivered = s.totalDelivered();
				float f = need > 0 ? delivered / (float) need : 0F;
				int barY = this.panelTop + this.panelH - 62;

				ChestGuiStyle.drawProgressBar(graphics, left, barY, contentW, 8, f);
				String amount = Component.translatable(
					"screen.chestmemory.clan.progress",
					delivered, need, need > 0 ? (int) (f * 100) : 0
				).getString();
				ChestGuiStyle.drawCentered(
					graphics, this.font, amount, centerX, barY + 11, ChestGuiStyle.TEXT_LIGHT
				);

				// Roster: who is here, and what each one is carrying to the build.
				int rosterY = barY + 22;
				int shown = 0;
				if (s.members != null) {
					for (ClanSession.ClanMember m : s.members) {
						if (shown >= 3) {
							break;
						}
						boolean host = m.uuid != null && m.uuid.equalsIgnoreCase(s.hostUuid);
						boolean away = m.isAway();
						// Mark who the hub has stopped hearing from, so a claim freeing up
						// on its own is explained rather than mysterious.
						String label = (host ? "★ " : "· ") + (m.name == null ? "?" : m.name)
							+ (away ? "  " + Component.translatable("screen.chestmemory.clan.away").getString() : "");
						int colour = away
							? ChestGuiStyle.TEXT_MUTED
							: (host ? ChestGuiStyle.TEXT_GOLD : ChestGuiStyle.TEXT_LIGHT);
						graphics.text(
							this.font,
							ChestGuiStyle.ellipsize(this.font, label, contentW),
							left, rosterY + shown * 10,
							colour,
							false
						);
						shown++;
					}
					int rest = s.members.size() - shown;
					if (rest > 0) {
						graphics.text(
							this.font,
							Component.translatable("screen.chestmemory.clan.more_members", rest).getString(),
							left, rosterY + shown * 10,
							ChestGuiStyle.TEXT_MUTED,
							false
						);
					}
				}
			}
		}

		// Status line last, so a fresh message always wins over the standing hints.
		String line;
		if (!this.status.isBlank()) {
			line = this.status;
		} else if (ClanSessionManager.isInSession()) {
			line = "";
		} else if (!ClanSessionManager.isConfigured()) {
			line = Component.translatable("screen.chestmemory.clan.status_need_hub").getString();
		} else {
			line = Component.translatable("screen.chestmemory.clan.status_ready").getString();
		}
		if (!line.isEmpty()) {
			ChestGuiStyle.drawCentered(
				graphics,
				this.font,
				ChestGuiStyle.ellipsize(this.font, line, contentW),
				centerX,
				this.panelTop + this.panelH - 42,
				ChestGuiStyle.TEXT_MUTED
			);
		}
	}
}
