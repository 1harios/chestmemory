package com.chestmemory.client.gui;

import com.chestmemory.client.clan.ClanCodes;
import com.chestmemory.client.clan.ClanDefaults;
import com.chestmemory.client.clan.ClanEventLog;
import com.chestmemory.client.clan.ClanSession;
import com.chestmemory.client.clan.ClanSessionManager;
import com.chestmemory.client.data.ChestMemoryStorage;
import com.chestmemory.client.data.ModSettings;
import com.chestmemory.client.litematica.LitematicaAccess;
import com.chestmemory.client.util.ClientScreens;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Clan gather: create or join a session by code, then follow who is doing what.
 * <p>
 * While a gather runs there is more to show than fits in the mod's fixed panel — the code,
 * overall progress, the roster with each member's assignment, and a feed of recent activity.
 * Tabs keep the panel one size instead of letting it grow, matching the rest of the mod.
 */
public class ClanGatherScreen extends Screen {
	/** Tabs shown while in a session. Index order matches {@link #TAB_KEYS}. */
	private static final int TAB_GATHER = 0;
	private static final int TAB_MEMBERS = 1;
	private static final int TAB_FEED = 2;
	private static final int TAB_LIST = 3;
	private static final String[] TAB_KEYS = {
		"screen.chestmemory.clan.tab_gather",
		"screen.chestmemory.clan.tab_members",
		"screen.chestmemory.clan.tab_feed",
		"screen.chestmemory.clan.tab_list"
	};

	private final Screen parent;
	private EditBox hubBox;
	private EditBox tokenBox;
	private EditBox codeBox;
	private String status = "";
	/** Two-step guard for "say in chat": the code is readable by everyone on the server. */
	private boolean sayCodeArmed;
	/** Two-step guard for the host's "delete gather": it ends the build for everyone. */
	private boolean deleteArmed;
	/** Selected tab; kept across rebuildWidgets so polling does not snap you back. */
	private int tab = TAB_GATHER;
	private int hoveredTab = -1;
	/** Tab strip geometry, filled while rendering and used for hit-testing. */
	private int tabsY = -1;
	private int tabsLeft;
	private int tabsWidth;
	/** Gather-list geometry, filled while rendering so clicks can be mapped to a code. */
	private int listRowsTop = -1;
	private int listRowH = 22;
	private java.util.List<String> listCodes = java.util.List.of();
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

		if (in) {
			// Tab strip sits above the controls; only the gather tab carries buttons, so the
			// other tabs get the whole panel body for their list.
			this.tabsLeft = left;
			this.tabsWidth = w;
			this.tabsY = y;
			y += 20;
			if (this.tab == TAB_LIST) {
				// Gathers tab: start another build or join one by code, without leaving the
				// gather being followed right now.
				int halfL = (w - gap) / 2;
				int rowTop = this.panelTop + this.panelH - 48;
				this.addRenderableWidget(new SettingRowButton(
					left, rowTop, halfL, rowH,
					Component.translatable("screen.chestmemory.clan.create_more"),
					() -> {
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
				this.codeBox = new EditBox(
					this.font, left + halfL + gap, rowTop, halfL, rowH,
					Component.translatable("screen.chestmemory.clan.code")
				);
				this.codeBox.setMaxLength(16);
				this.codeBox.setHint(Component.literal("CM-XXXX"));
				this.addRenderableWidget(this.codeBox);
				this.addRenderableWidget(new SettingRowButton(
					left, this.panelTop + this.panelH - 26, halfL, rowH,
					Component.translatable("screen.chestmemory.clan.join"),
					() -> {
						if (this.minecraft == null || this.codeBox == null) {
							return;
						}
						this.status = Component.translatable("screen.chestmemory.clan.working").getString();
						ClanSessionManager.switchToAsync(this.minecraft, this.codeBox.getValue(), this::rebuildWidgets);
					}
				));
				boolean host = this.minecraft != null && ClanSessionManager.isHost(this.minecraft);
				if (host) {
					// Only the creator can end a gather for everyone, and until now there was no way to
					// do it from here — the hub already enforces host-only close.
					this.addRenderableWidget(new SettingRowButton(
						left + halfL + gap, this.panelTop + this.panelH - 26, halfL, rowH,
						this.deleteArmed
							? Component.translatable("screen.chestmemory.clan.delete_confirm")
							: Component.translatable("screen.chestmemory.clan.delete"),
						() -> {
							if (this.minecraft == null) {
								return;
							}
							// Ending a gather throws away everyone's progress, so ask twice.
							if (!this.deleteArmed) {
								this.deleteArmed = true;
								this.status = Component.translatable("screen.chestmemory.clan.delete_hint").getString();
								this.rebuildWidgets();
								return;
							}
							this.deleteArmed = false;
							this.status = Component.translatable("screen.chestmemory.clan.working").getString();
							ClanSessionManager.leaveAsync(this.minecraft, this::rebuildWidgets);
						}
					));
				} else {
					this.addRenderableWidget(new SettingRowButton(
						left + halfL + gap, this.panelTop + this.panelH - 26, halfL, rowH,
						Component.translatable("screen.chestmemory.clan.back"),
						this::onClose
					));
				}
				return;
			}
			if (this.tab != TAB_GATHER) {
				// Members / feed tabs: no controls, just the back row at the bottom.
				this.addRenderableWidget(new SettingRowButton(
					left, this.panelTop + this.panelH - 26, w, rowH,
					Component.translatable("screen.chestmemory.clan.back"),
					this::onClose
				));
				return;
			}
		} else {
			// Not in a session, but the Gathers tab still has to be reachable: after leaving one
			// gather there was no way back to another you had already joined — the list only
			// existed while in a session, so the codes were remembered and unreachable.
			this.tabsLeft = left;
			this.tabsWidth = w;
			this.tabsY = y;
			y += 20;
			if (this.tab != TAB_GATHER && this.tab != TAB_LIST) {
				// Members / feed describe a session we are not in.
				this.tab = TAB_LIST;
			}
		}

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
				this.sayCodeArmed
					? Component.translatable("screen.chestmemory.clan.say_code_confirm")
					: Component.translatable("screen.chestmemory.clan.say_code", code),
				() -> {
					if (this.minecraft == null || this.minecraft.player == null || s == null) {
						return;
					}
					// Public chat: anyone on the server can read the code and join the session,
					// so require a second click instead of firing on the first one.
					if (!this.sayCodeArmed) {
						this.sayCodeArmed = true;
						this.status = Component.translatable("screen.chestmemory.clan.say_code_hint").getString();
						this.rebuildWidgets();
						return;
					}
					this.sayCodeArmed = false;
					this.minecraft.player.connection.sendChat(
						Component.translatable("message.chestmemory.clan_code_line", s.code).getString()
					);
					this.status = Component.translatable("screen.chestmemory.clan.code_sent").getString();
					this.rebuildWidgets();
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

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		// Tabs are painted, not widgets, so they are hit-tested here — before the default
		// handling, which would otherwise swallow the click on the panel background.
		String pick = gatherAt(event.x(), event.y());
		if (pick != null) {
			if (this.minecraft != null) {
				this.status = Component.translatable("screen.chestmemory.clan.working").getString();
				ClanSessionManager.switchToAsync(this.minecraft, pick, this::rebuildWidgets);
			}
			return true;
		}
		int t = tabAt(event.x(), event.y());
		if (t >= 0) {
			if (t != this.tab) {
				this.tab = t;
				this.status = "";
				this.rebuildWidgets();
			}
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}

	/**
	 * Code of the gather row under the cursor, or null.
	 * <p>
	 * Rows are painted rather than built as widgets, so clicks are mapped from the geometry
	 * recorded during the last render.
	 */
	private @org.jspecify.annotations.Nullable String gatherAt(double mx, double my) {
		if (this.tab != TAB_LIST || this.listRowsTop < 0 || this.listCodes.isEmpty()) {
			return null;
		}
		if (mx < this.tabsLeft || mx > this.tabsLeft + this.tabsWidth) {
			return null;
		}
		// Anything above the first row belongs to the tab strip. Without this the (int) cast
		// below turned a negative offset into row 0 — so clicking a tab switched gathers and
		// never reached tabAt(), which is why the Gathers tab could not be left.
		if (my < this.listRowsTop) {
			return null;
		}
		int idx = (int) ((my - this.listRowsTop) / Math.max(1, this.listRowH));
		if (idx < 0 || idx >= this.listCodes.size()) {
			return null;
		}
		// Guard the gap between rows: a click in the 2px seam should do nothing rather than
		// switch a gather the player was not aiming at.
		int rowTop = this.listRowsTop + idx * this.listRowH;
		if (my > rowTop + 20) {
			return null;
		}
		return this.listCodes.get(idx);
	}

	/** Tab index under the cursor, or -1. */
	private int tabAt(double mx, double my) {
		if (this.tabsY < 0 || my < this.tabsY || my > this.tabsY + 15) {
			return -1;
		}
		if (mx < this.tabsLeft || mx > this.tabsLeft + this.tabsWidth) {
			return -1;
		}
		int tabW = this.tabsWidth / TAB_KEYS.length;
		int i = (int) ((mx - this.tabsLeft) / Math.max(1, tabW));
		return Math.min(TAB_KEYS.length - 1, Math.max(0, i));
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

		// The tab strip is drawn whether or not we are in a session: the Gathers list has to
		// stay reachable after leaving one, or codes already joined become unreachable.
		if (this.tabsY >= 0) {
			this.hoveredTab = tabAt(mouseX, mouseY);
			Component[] labels = new Component[TAB_KEYS.length];
			for (int i = 0; i < TAB_KEYS.length; i++) {
				labels[i] = Component.translatable(TAB_KEYS[i]);
			}
			ChestGuiStyle.drawTabs(
				graphics, this.font, labels,
				this.tabsLeft, this.tabsY, this.tabsWidth, this.tab, this.hoveredTab
			);
		}
		if (!ClanSessionManager.isInSession() && this.tab == TAB_LIST) {
			// Outside a session the list is the whole screen; nothing is "active".
			drawGatherList(graphics, null, left, contentW);
		}
		if (ClanSessionManager.isInSession()) {
			ClanSession s = ClanSessionManager.session();
			if (s != null) {
				// The code is the one thing the host reads out loud, so it gets a plate
				// of its own instead of being buried in a status sentence.
				ChestGuiStyle.drawCodePlate(graphics, this.font, s.code, centerX, this.panelTop + 22, 90);


				switch (this.tab) {
					case TAB_MEMBERS -> drawMembers(graphics, s, left, contentW);
					case TAB_FEED -> drawFeed(graphics, left, contentW);
					case TAB_LIST -> drawGatherList(graphics, s, left, contentW);
					default -> drawGatherSummary(graphics, s, left, centerX, contentW);
				}
			}
		}

		// Status line last, so a fresh message always wins over the standing hints.
		String line;
		if (ClanSessionManager.isInSession() && this.tab != TAB_GATHER) {
			// The list tabs use the full body; a status sentence under them would collide
			// with the back row.
			line = "";
		} else if (!this.status.isBlank()) {
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
				this.panelTop + this.panelH - 39,
				ChestGuiStyle.TEXT_MUTED
			);
		}
	}

	/** Gather tab: overall progress plus the three headline numbers. */
	private void drawGatherSummary(
		GuiGraphicsExtractor graphics,
		ClanSession s,
		int left,
		int centerX,
		int contentW
	) {
		int need = s.totalNeed();
		int delivered = s.totalDelivered();
		float f = need > 0 ? delivered / (float) need : 0F;
		// Rows are stacked from the back button upwards with explicit gaps: the summary line
		// and the status line used to be computed independently and overlapped by 12px.
		int barY = this.panelTop + this.panelH - 72;

		ChestGuiStyle.drawProgressBar(graphics, left, barY, contentW, 8, f);
		String amount = Component.translatable(
			"screen.chestmemory.clan.progress",
			delivered, need, need > 0 ? (int) (f * 100) : 0
		).getString();
		ChestGuiStyle.drawCentered(graphics, this.font, amount, centerX, barY + 11, ChestGuiStyle.TEXT_LIGHT);

		// Counts that answer "is anyone actually working on this?" without switching tabs.
		int online = 0;
		int claimed = 0;
		for (ClanSession.ClanMember m : s.members) {
			if (!m.isAway()) {
				online++;
			}
		}
		for (ClanSession.ClanMaterial m : s.materials.values()) {
			if (m.claimedBy != null && !m.claimedBy.isBlank()) {
				claimed++;
			}
		}
		String summary = Component.translatable(
			"screen.chestmemory.clan.summary",
			online, s.members.size(), claimed
		).getString();
		ChestGuiStyle.drawCentered(
			graphics, this.font,
			ChestGuiStyle.ellipsize(this.font, summary, contentW),
			centerX, barY + 22, ChestGuiStyle.TEXT_LIGHT
		);
	}

	/**
	 * Members tab: one plank per player with what they reserved and how much they brought in.
	 * <p>
	 * This is the view that used to be missing — the roster only ever showed names, so there
	 * was no way to tell who was on the glass and who had already delivered.
	 */
	private void drawMembers(GuiGraphicsExtractor graphics, ClanSession s, int left, int contentW) {
		int y = this.tabsY + 22;
		int bottom = this.panelTop + this.panelH - 32;
		int rowH = 20;

		if (s.members.isEmpty()) {
			graphics.text(
				this.font,
				Component.translatable("screen.chestmemory.clan.no_members").getString(),
				left, y, ChestGuiStyle.TEXT_ON_WOOD_MUTED, false
			);
			return;
		}

		int shown = 0;
		for (ClanSession.ClanMember m : s.members) {
			if (y + rowH > bottom) {
				// Count what is left explicitly; deriving it from pixel positions worked but
				// silently depends on the row/gap constants staying in sync.
				int rest = s.members.size() - shown;
				if (rest > 0) {
					graphics.text(
						this.font,
						Component.translatable("screen.chestmemory.clan.more_members", rest).getString(),
						left, y + 2, ChestGuiStyle.TEXT_ON_WOOD_MUTED, false
					);
				}
				break;
			}
			boolean host = m.uuid != null && m.uuid.equalsIgnoreCase(s.hostUuid);
			boolean away = m.isAway();

			// What this member is holding, and how much of it already reached the warehouse.
			String claimItem = null;
			int claimDone = 0;
			int claimNeed = 0;
			for (var e : s.materials.entrySet()) {
				ClanSession.ClanMaterial mat = e.getValue();
				if (mat.claimedBy != null && m.uuid != null && mat.claimedBy.equalsIgnoreCase(m.uuid)) {
					claimItem = ChestMemoryStorage.itemDisplayName(e.getKey());
					claimDone = Math.max(0, mat.delivered);
					claimNeed = Math.max(0, mat.need);
					break;
				}
			}

			int accent = away
				? ChestGuiStyle.TEXT_ON_WOOD_MUTED
				: (host ? ChestGuiStyle.LATCH : (claimItem != null ? 0xFF5FD068 : ChestGuiStyle.WOOD_LIGHT));
			ChestGuiStyle.drawMemberRow(graphics, left, y, contentW, rowH, accent, away);

			String name = (host ? "★ " : "") + (m.name == null || m.name.isBlank() ? "?" : m.name);
			int nameColour = away
				? ChestGuiStyle.TEXT_ON_WOOD_MUTED
				: (host ? ChestGuiStyle.TEXT_GOLD : ChestGuiStyle.TEXT_LIGHT);

			// Right-hand column: the assignment. Reserve its width first so a long player
			// name gets ellipsized instead of running underneath it.
			String right;
			int rightColour;
			if (away) {
				right = Component.translatable("screen.chestmemory.clan.away").getString();
				rightColour = ChestGuiStyle.TEXT_ON_WOOD_MUTED;
			} else if (claimItem != null) {
				right = Component.translatable(
					"screen.chestmemory.clan.carrying", claimItem, claimDone, claimNeed
				).getString();
				rightColour = claimNeed > 0 && claimDone >= claimNeed ? 0xFF7FE08A : ChestGuiStyle.TEXT_GOLD;
			} else {
				right = Component.translatable("screen.chestmemory.clan.idle").getString();
				rightColour = ChestGuiStyle.TEXT_ON_WOOD_MUTED;
			}
			int rightW = Math.min(this.font.width(right), contentW - 60);
			right = ChestGuiStyle.ellipsize(this.font, right, rightW);
			rightW = this.font.width(right);

			int textY = y + (rowH - this.font.lineHeight) / 2 + 1;
			graphics.text(
				this.font,
				ChestGuiStyle.ellipsize(this.font, name, contentW - rightW - 18),
				left + 7, textY, nameColour, false
			);
			graphics.text(this.font, right, left + contentW - 6 - rightW, textY, rightColour, false);
			y += rowH + 2;
			shown++;
		}
	}

	/** Feed tab: recent claims, deliveries and arrivals, newest first. */
	private void drawFeed(GuiGraphicsExtractor graphics, int left, int contentW) {
		int y = this.tabsY + 22;
		int bottom = this.panelTop + this.panelH - 32;
		int lineH = 11;
		int rows = Math.max(1, (bottom - y) / lineH);

		List<ClanEventLog.Entry> events = ClanEventLog.recent(rows);
		// Recessed panel behind the feed. Without it the light text sat on the light panel at
		// 1.19:1 contrast — the "barely visible" the user reported. On this backing it is 10.8:1.
		int feedH = Math.max(lineH, Math.min(rows, Math.max(1, events.size())) * lineH) + 4;
		graphics.fill(left - 2, y - 3, left + contentW + 2, y + feedH, ChestGuiStyle.WOOD_DARK);
		graphics.fill(left - 1, y - 2, left + contentW + 1, y + feedH - 1, ChestGuiStyle.ROW_WOOD);
		if (events.isEmpty()) {
			graphics.text(
				this.font,
				Component.translatable("screen.chestmemory.clan.no_events").getString(),
				left, y, ChestGuiStyle.TEXT_ON_WOOD_MUTED, false
			);
			return;
		}

		long now = System.currentTimeMillis();
		for (ClanEventLog.Entry e : events) {
			int dot = switch (e.kind()) {
				case CLAIM -> 0xFFE0A83C;
				case RELEASE -> 0xFF9A8A70;
				case DELIVER -> 0xFF5FD068;
				case JOIN -> 0xFF6FB7E8;
				case LEAVE -> 0xFFD9695A;
			};
			ChestGuiStyle.drawEventDot(graphics, left + 1, y + 1, dot);

			// Relative age on the right — "2м" reads faster than a clock time here.
			String age = ageLabel(now - e.at());
			int ageW = this.font.width(age);
			String text = ChestGuiStyle.ellipsize(
				this.font, e.text().getString(), contentW - 10 - ageW - 6
			);
			graphics.text(this.font, text, left + 8, y, ChestGuiStyle.TEXT_LIGHT, false);
			graphics.text(
				this.font, age, left + contentW - ageW, y,
				ChestGuiStyle.TEXT_ON_WOOD_MUTED, false
			);
			y += lineH;
		}
	}

	/**
	 * Gathers tab: every gather this player knows about, with the active one marked.
	 * <p>
	 * Click a row to follow it instead. Only the active gather is polled, so this list shows
	 * the progress last seen for the others rather than live numbers — following all of them
	 * would multiply request volume by the number of gathers.
	 */
	private void drawGatherList(
		GuiGraphicsExtractor graphics,
		ClanSession current,
		int left,
		int contentW
	) {
		int y = this.tabsY + 22;
		// This tab carries two rows of buttons at the bottom (new gather / join / back), so the
		// list has to stop above them — the shared "panelH - 32" would run underneath.
		int bottom = this.panelTop + this.panelH - 52;
		int rowH = 20;

		List<com.chestmemory.client.clan.ClanRoster.Entry> entries =
			com.chestmemory.client.clan.ClanRoster.all();
		this.listRowsTop = y;
		this.listRowH = rowH + 2;
		this.listCodes = new java.util.ArrayList<>();

		if (entries.isEmpty()) {
			graphics.text(
				this.font,
				Component.translatable("screen.chestmemory.clan.no_gathers").getString(),
				left, y, ChestGuiStyle.TEXT_BODY, false
			);
			return;
		}

		for (com.chestmemory.client.clan.ClanRoster.Entry e : entries) {
			if (y + rowH > bottom) {
				break;
			}
			boolean active = current != null && e.code().equalsIgnoreCase(current.code);
			// Active gather gets the gold marker; the rest read as available to switch to.
			int accent = active ? ChestGuiStyle.LATCH : ChestGuiStyle.WOOD_LIGHT;
			ChestGuiStyle.drawMemberRow(graphics, left, y, contentW, rowH, accent, !active);

			boolean iAmHost = active && this.minecraft != null && ClanSessionManager.isHost(this.minecraft);
			// Mark ownership: only the creator can delete a gather, so it should be visible
			// which of them are yours.
			String name = (active ? "▶ " : "") + (iAmHost ? "★ " : "") + e.code();
			String right = e.need() > 0
				? Component.translatable("screen.chestmemory.clan.list_progress", e.percent()).getString()
				: "";
			int rightW = right.isEmpty() ? 0 : this.font.width(right);
			int textY = y + (rowH - this.font.lineHeight) / 2 + 1;

			// Schematic name under the code when there is room, so two gathers are told apart
			// by what they build, not only by their code.
			String label = e.label();
			String main = label.isBlank() ? name : name + " · " + label;
			graphics.text(
				this.font,
				ChestGuiStyle.ellipsize(this.font, main, contentW - rightW - 16),
				left + 7, textY,
				active ? ChestGuiStyle.TEXT_GOLD : ChestGuiStyle.TEXT_LIGHT,
				false
			);
			if (rightW > 0) {
				graphics.text(
					this.font, right, left + contentW - 6 - rightW, textY,
					active ? ChestGuiStyle.TEXT_GOLD : ChestGuiStyle.TEXT_ON_WOOD_MUTED, false
				);
			}
			this.listCodes.add(e.code());
			y += rowH + 2;
		}
	}

	/** Compact age: seconds under a minute, then minutes, then hours. */
	private static String ageLabel(long millis) {
		long sec = Math.max(0, millis / 1000L);
		if (sec < 60) {
			return Component.translatable("screen.chestmemory.clan.age_sec", sec).getString();
		}
		long min = sec / 60;
		if (min < 60) {
			return Component.translatable("screen.chestmemory.clan.age_min", min).getString();
		}
		return Component.translatable("screen.chestmemory.clan.age_hour", min / 60).getString();
	}
}
