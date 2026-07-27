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
	/** What the build needs, with icons — the view this screen was missing entirely. */
	private static final int TAB_MATERIALS = 1;
	private static final int TAB_MEMBERS = 2;
	private static final int TAB_FEED = 3;
	private static final int TAB_LIST = 4;
	private static final String[] TAB_KEYS = {
		"screen.chestmemory.clan.tab_gather",
		"screen.chestmemory.clan.tab_materials",
		"screen.chestmemory.clan.tab_members",
		"screen.chestmemory.clan.tab_feed",
		"screen.chestmemory.clan.tab_list"
	};

	/** Scroll state per list, so switching tabs does not lose your place. */
	private final ScrollList materialScroll = new ScrollList();
	private final ScrollList memberScroll = new ScrollList();
	private final ScrollList feedScroll = new ScrollList();
	private final ScrollList gatherScroll = new ScrollList();
	/** Item ids drawn in the materials tab this frame, for hit-testing clicks. */
	private java.util.List<String> materialIds = java.util.List.of();
	/** Columns in the material grid, needed to map a click back to an item. */
	private int materialGridPerRow;
	/** Left edge of the grid's first column, inside the tray border. */
	private int materialGridLeft;
	/** Pointer position from the last render, so painted rows can show a hover. */
	private int hoverX = -1;
	private int hoverY = -1;
	/** Icons are resolved once per item: building a stack hits the registry. */
	private final java.util.Map<String, net.minecraft.world.item.ItemStack> iconCache =
		new java.util.HashMap<>();

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
	/** Y of the painted hub status strip, or -1 when the build has no baked hub. */
	private int hubStripY = -1;
	/** Y where the summary must stop, recorded next to the controls it has to clear. */
	private int summaryBottom = -1;
	private int listRowsTop = -1;
	/**
	 * Y where the gather list has to stop, set by init() next to the controls it must clear.
	 * Hard-coding the offset in the drawing code is what let the empty-list caption land on
	 * top of the buttons.
	 */
	private int listBottom = -1;
	private int listRowH = 22;
	private java.util.List<String> listCodes = java.util.List.of();
	private int panelLeft;
	private int panelTop;
	private int panelW;
	private int panelH;
	/**
	 * Typed code, kept across widget rebuilds.
	 * <p>
	 * init() builds a fresh EditBox every time, so any rebuild — a finished switch, a poll —
	 * silently erased what the player was typing.
	 */
	private String codeDraft = "";
	/** State the widgets were last built for; a change means their enabled state is stale. */
	private @org.jspecify.annotations.Nullable String builtForSwitching;
	private boolean builtForBusy;
	private boolean builtForHasCode;
	private ClanSessionManager.@org.jspecify.annotations.Nullable HubState builtForHub;
	private @org.jspecify.annotations.Nullable String builtForCode;

	/**
	 * Rebuild only when something that changes a button's enabled state actually changed.
	 * <p>
	 * The screen has no tick of its own, so a switch finishing in the background left every
	 * button greyed out until the next click. Rebuilding unconditionally is not an option
	 * either: it recreates the widgets, and doing that 20×/s fights with typing.
	 */
	@Override
	public void tick() {
		super.tick();
		String switching = ClanSessionManager.switchingTo();
		boolean busy = ClanSessionManager.isBusy();
		ClanSession s = ClanSessionManager.session();
		String code = s != null ? s.code : null;
		// Typing the first character has to light up "join", and a hub coming back has to
		// swap the retry button away — both change which buttons are usable, so both belong
		// in the same comparison as the rest.
		boolean hasCode = !this.codeDraft.isBlank();
		ClanSessionManager.HubState hub = ClanSessionManager.hubState();
		if (!java.util.Objects.equals(code, this.builtForCode)) {
			// A different gather means a different material list and roster; keeping the old
			// offset would drop the player into the middle of a list they have not seen.
			this.materialScroll.reset();
			this.memberScroll.reset();
			this.iconCache.clear();
		}
		if (!java.util.Objects.equals(switching, this.builtForSwitching)
			|| busy != this.builtForBusy
			|| !java.util.Objects.equals(code, this.builtForCode)
			|| hasCode != this.builtForHasCode
			|| hub != this.builtForHub) {
			// A rebuild recreates the EditBox, and typing the first character triggers one —
			// so without this the box loses focus after a single keystroke and the player has
			// to click it again for every letter of the code.
			boolean wasTyping = this.codeBox != null && this.codeBox.isFocused();
			this.rebuildWidgets();
			if (wasTyping && this.codeBox != null) {
				this.setFocused(this.codeBox);
				this.codeBox.setFocused(true);
				this.codeBox.moveCursorToEnd(false);
			}
		}
	}

	public ClanGatherScreen(Screen parent) {
		super(Component.translatable("screen.chestmemory.clan.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		this.builtForSwitching = ClanSessionManager.switchingTo();
		this.builtForBusy = ClanSessionManager.isBusy();
		this.builtForHasCode = !this.codeDraft.isBlank();
		this.builtForHub = ClanSessionManager.hubState();
		ClanSession built = ClanSessionManager.session();
		this.builtForCode = built != null ? built.code : null;
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
			// Painted, not a widget: it reports state and cannot be pressed. It used to be a
			// SettingRowButton with an empty handler, which looked exactly like the buttons
			// below it and did nothing — the single most confusing thing on this screen.
			this.hubStripY = y;
			if (this.minecraft != null) {
				ClanSessionManager.checkHubAsync(this.minecraft, null);
			}
			y += rowH + gap + 2;
		} else {
			this.hubStripY = -1;
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
				// The list must stop above these two rows of controls. Recorded here, where the
				// controls are actually placed, so the drawing code cannot drift out of step.
				this.listBottom = rowTop - 6;
				boolean switching = ClanSessionManager.switchingTo() != null;
				// A new gather needs a schematic of its own; the clan's materials belong to the
				// gather already being followed, so they cannot seed a second one.
				boolean canCreate = LitematicaAccess.isAvailable()
					&& com.chestmemory.client.litematica.LitematicaCompat.hasActiveMaterialListSafe();
				SettingRowButton createBtn = new SettingRowButton(
					left, rowTop, halfL, rowH,
					Component.translatable("screen.chestmemory.clan.create_more"),
					() -> {
						if (this.minecraft == null) {
							return;
						}
						this.status = Component.translatable("screen.chestmemory.clan.working").getString();
						ClanSessionManager.createAsync(this.minecraft, this::rebuildWidgets);
					}
				);
				// Grey instead of silently doing nothing: this button used to look live and
				// answer with an error only after being clicked.
				createBtn.active = canCreate && !switching;
				createBtn.setTooltip(net.minecraft.client.gui.components.Tooltip.create(
					canCreate
						? Component.translatable("screen.chestmemory.clan.create_more_tip")
						: Component.translatable("screen.chestmemory.clan.create_need_list")
				));
				this.addRenderableWidget(createBtn);
				// 64, not 58: "Вставить" measures 48px and needs 12px of padding, and a
				// clipped verb on a button is worse than a slightly narrower code box.
				int pasteW = 64;
				int codeW = halfL - gap - pasteW;
				this.codeBox = new EditBox(
					this.font, left + halfL + gap, rowTop, codeW, rowH,
					Component.translatable("screen.chestmemory.clan.code")
				);
				this.codeBox.setMaxLength(16);
				this.codeBox.setHint(Component.literal("CM-XXXX"));
				this.codeBox.setValue(this.codeDraft);
				this.codeBox.setResponder(v -> this.codeDraft = v);
				this.addRenderableWidget(this.codeBox);
				SettingRowButton pasteInSession = new SettingRowButton(
					left + halfL + gap + codeW + gap, rowTop, pasteW, rowH,
					Component.translatable("screen.chestmemory.clan.paste_code"),
					this::pasteCodeFromClipboard
				);
				pasteInSession.active = !switching;
				this.addRenderableWidget(pasteInSession);
				SettingRowButton joinBtn = new SettingRowButton(
					left, this.panelTop + this.panelH - 26, halfL, rowH,
					Component.translatable("screen.chestmemory.clan.join"),
					() -> {
						if (this.minecraft == null || this.codeBox == null) {
							return;
						}
						this.status = Component.translatable("screen.chestmemory.clan.working").getString();
						ClanSessionManager.switchToAsync(this.minecraft, this.codeBox.getValue(), this::rebuildWidgets);
					}
				);
				joinBtn.active = !switching && !this.codeDraft.isBlank();
				this.addRenderableWidget(joinBtn);
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
			if (this.tab == TAB_LIST) {
				// The remembered-gathers list gets the panel body and the controls go to the
				// bottom. They used to be laid out from the top, straight over the list, so
				// "Сборов пока нет" was printed across the "Создать сбор" button.
				int halfL = (w - gap) / 2;
				int rowTop = this.panelTop + this.panelH - 48;
				this.listBottom = rowTop - 6;
				boolean canCreate = LitematicaAccess.hasActiveMaterialList();
				SettingRowButton createBtn = new SettingRowButton(
					left, rowTop, halfL, rowH,
					Component.translatable("screen.chestmemory.clan.create"),
					() -> {
						saveHubQuiet();
						if (this.minecraft == null) {
							return;
						}
						this.status = Component.translatable("screen.chestmemory.clan.working").getString();
						ClanSessionManager.createAsync(this.minecraft, this::rebuildWidgets);
					}
				);
				createBtn.active = canCreate;
				createBtn.setTooltip(net.minecraft.client.gui.components.Tooltip.create(
					canCreate
						? Component.translatable("screen.chestmemory.clan.create_tip")
						: Component.translatable("screen.chestmemory.clan.create_need_list")
				));
				this.addRenderableWidget(createBtn);
				// Code box shares its row with a paste button: the code arrives in chat, so
				// copying that line and pasting beats typing four characters by hand.
				// 64, not 58: "Вставить" measures 48px and needs 12px of padding, and a
				// clipped verb on a button is worse than a slightly narrower code box.
				int pasteW = 64;
				int codeW = halfL - gap - pasteW;
				this.codeBox = new EditBox(
					this.font, left + halfL + gap, rowTop, codeW, rowH,
					Component.translatable("screen.chestmemory.clan.code")
				);
				this.codeBox.setMaxLength(16);
				this.codeBox.setHint(Component.literal("CM-XXXX"));
				this.codeBox.setValue(this.codeDraft);
				this.codeBox.setResponder(v -> this.codeDraft = v);
				this.addRenderableWidget(this.codeBox);
				SettingRowButton pasteBtn = new SettingRowButton(
					left + halfL + gap + codeW + gap, rowTop, pasteW, rowH,
					Component.translatable("screen.chestmemory.clan.paste_code"),
					this::pasteCodeFromClipboard
				);
				this.addRenderableWidget(pasteBtn);

				SettingRowButton joinRow = new SettingRowButton(
					left, this.panelTop + this.panelH - 26, halfL, rowH,
					Component.translatable("screen.chestmemory.clan.join"),
					() -> {
						saveHubQuiet();
						if (this.minecraft == null || this.codeBox == null) {
							return;
						}
						this.status = Component.translatable("screen.chestmemory.clan.working").getString();
						ClanSessionManager.joinAsync(this.minecraft, this.codeBox.getValue(), this::rebuildWidgets);
					}
				);
				// Nothing to join without a code — grey rather than an error after the click.
				joinRow.active = !this.codeDraft.isBlank();
				this.addRenderableWidget(joinRow);

				// When the hub is down, the useful button here is "try again", not "back".
				boolean hubDown = ClanSessionManager.hubState() == ClanSessionManager.HubState.OFFLINE;
				if (hubDown) {
					SettingRowButton retry = new SettingRowButton(
						left + halfL + gap, this.panelTop + this.panelH - 26, halfL, rowH,
						Component.translatable("screen.chestmemory.clan.hub_retry"),
						this::retryHubCheck
					);
					retry.setTooltip(net.minecraft.client.gui.components.Tooltip.create(
						Component.translatable("screen.chestmemory.clan.hub_retry_tip")
					));
					this.addRenderableWidget(retry);
				} else {
					this.addRenderableWidget(new SettingRowButton(
						left + halfL + gap, this.panelTop + this.panelH - 26, halfL, rowH,
						Component.translatable("screen.chestmemory.clan.back"),
						this::onClose
					));
				}
				return;
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
			this.codeBox.setValue(this.codeDraft);
			this.codeBox.setResponder(v -> this.codeDraft = v);
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
			// Session controls live at the BOTTOM. Laid out from the top they sat exactly
			// where the summary paints — "Нажми ещё" printed across the schematic name and
			// "Копировать код" across the progress line. The body belongs to the content.
			int ctlTop = this.panelTop + this.panelH - 70;
			this.summaryBottom = ctlTop - 6;
			this.addRenderableWidget(new SettingRowButton(
				left, ctlTop, w, rowH,
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

			this.addRenderableWidget(new SettingRowButton(
				left, ctlTop + rowH + gap, half, rowH,
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
				left + half + gap, ctlTop + rowH + gap, half, rowH,
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
		}

		this.addRenderableWidget(new SettingRowButton(
			left, this.panelTop + this.panelH - 26, w, rowH,
			Component.translatable("screen.chestmemory.clan.back"),
			this::onClose
		));
	}

	/**
	 * Reserve the clicked material, or give it up when it is already yours.
	 * <p>
	 * Claiming used to live only on the chest panel, so a player had to leave the clan screen
	 * to say what they were working on. The rules are the hub's, not this screen's: someone
	 * else's claim is refused, and a finished item is not claimable at all.
	 */
	private void claimFromList(String itemId) {
		if (this.minecraft == null) {
			return;
		}
		ClanSession s = ClanSessionManager.session();
		if (s == null) {
			return;
		}
		if (s.remaining(itemId) <= 0) {
			this.status = Component.translatable("screen.chestmemory.clan.mat_already_done").getString();
			return;
		}
		ClanSession.ClanMaterial m = s.material(itemId);
		String me = ClanSessionManager.localUuid(this.minecraft);
		if (m != null && m.claimedBy != null && !m.claimedBy.isBlank() && !m.claimedBy.equals(me)) {
			this.status = Component.translatable(
				"screen.chestmemory.clan.mat_taken_by",
				m.claimedName != null ? m.claimedName : "?"
			).getString();
			return;
		}
		this.status = Component.translatable("screen.chestmemory.clan.working").getString();
		ClanSessionManager.claimToggleAsync(this.minecraft, itemId, this::rebuildWidgets);
	}

	/**
	 * Pull a CM-XXXX code out of the clipboard into the code box.
	 * <p>
	 * The code arrives in chat, and a player who copies that line gets a whole sentence, not
	 * a bare code — so this picks the code out of whatever was copied rather than demanding
	 * a clean paste. Typing it by hand was the only option before.
	 */
	private void pasteCodeFromClipboard() {
		if (this.minecraft == null || this.codeBox == null) {
			return;
		}
		String clip = this.minecraft.keyboardHandler.getClipboard();
		var m = java.util.regex.Pattern
			.compile("(?i)\\bCM[-\\s]?([A-Z0-9]{4})\\b")
			.matcher(clip == null ? "" : clip);
		if (!m.find()) {
			this.status = Component.translatable("screen.chestmemory.clan.paste_empty").getString();
			return;
		}
		String code = "CM-" + m.group(1).toUpperCase(java.util.Locale.ROOT);
		this.codeDraft = code;
		this.codeBox.setValue(code);
		this.status = Component.translatable("screen.chestmemory.clan.pasted", code).getString();
	}

	/** Ask the hub again after a failed check, instead of leaving the player stuck. */
	private void retryHubCheck() {
		if (this.minecraft == null) {
			return;
		}
		ClanSessionManager.forceHubRecheck();
		ClanSessionManager.checkHubAsync(this.minecraft, this::rebuildWidgets);
	}

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		// Every list scrolls. Before this the rows simply stopped when they ran out of room,
		// so a long roster or a big material list was unreachable past the first screenful.
		ScrollList active = switch (this.tab) {
			case TAB_MATERIALS -> this.materialScroll;
			case TAB_MEMBERS -> this.memberScroll;
			case TAB_FEED -> this.feedScroll;
			case TAB_LIST -> this.gatherScroll;
			default -> null;
		};
		if (active != null && active.scrolled(x, y, scrollY)) {
			return true;
		}
		return super.mouseScrolled(x, y, scrollX, scrollY);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		// Tabs are painted, not widgets, so they are hit-tested here — before the default
		// handling, which would otherwise swallow the click on the panel background.
		if (this.tab == TAB_MATERIALS && this.minecraft != null) {
			int idx = materialAt(event.x(), event.y());
			if (idx >= 0 && idx < this.materialIds.size()) {
				// The screen stays open: taking one item used to close it, so reserving three
				// meant opening the panel three times.
				claimFromList(this.materialIds.get(idx));
				return true;
			}
		}
		String pick = gatherAt(event.x(), event.y());
		if (pick != null) {
			if (ClanSessionManager.switchingTo() != null) {
				// A switch is already running. Queuing a second one is what made rapid
				// clicking feel unpredictable.
				return true;
			}
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
		if (this.tab != TAB_LIST) {
			return null;
		}
		// Delegated to the scroll state, which knows the offset. Doing the arithmetic here
		// broke the moment the list could scroll: listCodes holds only the visible rows, so a
		// scrolled list mapped every click to the wrong gather.
		int idx = this.gatherScroll.rowAt(mx, my, 20);
		if (idx < 0) {
			return null;
		}
		var entries = com.chestmemory.client.clan.ClanRoster.all();
		return idx < entries.size() ? entries.get(idx).code() : null;
	}

	/** Tab index under the cursor, or -1. Geometry shared with the tab renderer. */
	private int tabAt(double mx, double my) {
		if (this.tabsY < 0) {
			return -1;
		}
		Component[] labels = new Component[TAB_KEYS.length];
		for (int i = 0; i < TAB_KEYS.length; i++) {
			labels[i] = Component.translatable(TAB_KEYS[i]);
		}
		return ChestGuiStyle.tabIndexAt(
			this.font, labels, this.tabsLeft, this.tabsWidth, this.tabsY, mx, my
		);
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
		this.hoverX = mouseX;
		this.hoverY = mouseY;
		// Title and subtitle on two lines, exactly as the chest panel does it: the header has
		// room for both, and the second line is where the screen says what it is showing.
		ChestGuiStyle.drawCentered(
			graphics, this.font, this.title,
			this.panelLeft + this.panelW / 2, this.panelTop + 8,
			ChestGuiStyle.TEXT_TITLE
		);
		ClanSession header = ClanSessionManager.session();
		String subtitle;
		if (header != null) {
			String build = header.schemaName == null || header.schemaName.isBlank()
				? Component.translatable("screen.chestmemory.clan.unnamed_build").getString()
				: header.schemaName;
			subtitle = Component.translatable(
				"screen.chestmemory.clan.header_in", header.code, build
			).getString();
		} else {
			subtitle = Component.translatable("screen.chestmemory.clan.header_out").getString();
		}
		ChestGuiStyle.drawCentered(
			graphics, this.font,
			ChestGuiStyle.ellipsize(this.font, subtitle, this.panelW - 24),
			this.panelLeft + this.panelW / 2, this.panelTop + 20,
			ChestGuiStyle.TEXT_MUTED
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
				// No code plate here any more: the header subtitle carries the code, and the
				// plate sat at +22 — straight on top of it.
				switch (this.tab) {
					case TAB_MATERIALS -> drawMaterials(graphics, s, left, contentW);
					case TAB_MEMBERS -> drawMembers(graphics, s, left, contentW);
					case TAB_FEED -> drawFeed(graphics, left, contentW);
					case TAB_LIST -> drawGatherList(graphics, s, left, contentW);
					default -> drawGatherSummary(graphics, s, left, centerX, contentW);
				}
			}
		}

		if (this.hubStripY >= 0) {
			ClanSessionManager.HubState st = ClanSessionManager.hubState();
			int lamp = switch (st) {
				case ONLINE -> ChestGuiStyle.LAMP_ONLINE;
				case OFFLINE -> ChestGuiStyle.LAMP_OFFLINE;
				case UNKNOWN -> ChestGuiStyle.LAMP_CHECKING;
			};
			// The state is spelled out as well as coloured — a red/green lamp alone is no
			// help to a colour-blind player, and "unreachable" is the one state that matters.
			String label = Component.translatable(switch (st) {
				case ONLINE -> "screen.chestmemory.clan.hub_online";
				case OFFLINE -> "screen.chestmemory.clan.hub_offline";
				case UNKNOWN -> "screen.chestmemory.clan.hub_checking";
			}).getString();
			String detail = st == ClanSessionManager.HubState.ONLINE
				? Component.translatable("screen.chestmemory.clan.hub_builtin_short").getString()
				: null;
			ChestGuiStyle.drawStatusStrip(
				graphics, this.font, left, this.hubStripY, contentW, 18, label, detail, lamp
			);
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
			// Below the panel, like the chest screen's footer. Inside it the sentence fought
			// the buttons for the same rows; out here it has the whole width and cannot
			// collide with anything.
			ChestGuiStyle.drawCentered(
				graphics,
				this.font,
				ChestGuiStyle.ellipsize(this.font, line, this.panelW),
				centerX,
				this.panelTop + this.panelH + 6,
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
		int y = this.tabsY + 22;

		// Headline plate: the build's name, which is what a gather actually IS. The code
		// identifies it, the schematic explains it — two gathers used to be told apart only
		// by four random characters.
		String schema = s.schemaName == null || s.schemaName.isBlank()
			? Component.translatable("screen.chestmemory.clan.unnamed_build").getString()
			: s.schemaName;
		graphics.fill(left, y, left + contentW, y + 16, ChestGuiStyle.WOOD_DARK);
		graphics.fill(left + 1, y + 1, left + contentW - 1, y + 15, 0xFF2E2E2E);
		graphics.fill(left + 1, y + 1, left + contentW - 1, y + 2,
			ChestGuiStyle.withAlpha(0xFFFFFF, 0.18F));
		ChestGuiStyle.drawCentered(
			graphics, this.font, ChestGuiStyle.ellipsize(this.font, schema, contentW - 12),
			centerX, y + 5, ChestGuiStyle.TEXT_GOLD
		);
		y += 20;

		ChestGuiStyle.drawProgressBar(graphics, left, y, contentW, 8, f);
		String amount = Component.translatable(
			"screen.chestmemory.clan.progress",
			delivered, need, need > 0 ? (int) (f * 100) : 0
		).getString();
		ChestGuiStyle.drawCentered(graphics, this.font, amount, centerX, y + 11, ChestGuiStyle.TEXT_LIGHT);
		y += 24;

		int online = 0;
		for (ClanSession.ClanMember m : s.members) {
			if (!s.isMemberAway(m)) {
				online++;
			}
		}
		int claimed = 0;
		int leftItems = 0;
		int doneItems = 0;
		for (var e : s.materials.entrySet()) {
			ClanSession.ClanMaterial m = e.getValue();
			if (m.claimedBy != null && !m.claimedBy.isBlank()) {
				claimed++;
			}
			if (s.remaining(e.getKey()) > 0) {
				leftItems++;
			} else {
				doneItems++;
			}
		}

		// Who set this up and when — the questions a member joining an unfamiliar gather
		// asks first, and the screen could answer neither.
		long now = System.currentTimeMillis();
		boolean iAmHost = this.minecraft != null && ClanSessionManager.isHost(this.minecraft);
		String host = (s.hostName == null || s.hostName.isBlank() ? "?" : s.hostName)
			+ (iAmHost ? " " + Component.translatable("screen.chestmemory.clan.you_marker").getString() : "");
		ChestGuiStyle.drawDetailRow(
			graphics, this.font,
			Component.translatable("screen.chestmemory.clan.detail_host").getString(),
			host, left, y, contentW, ChestGuiStyle.TEXT_LIGHT
		);
		y += 11;
		ChestGuiStyle.drawDetailRow(
			graphics, this.font,
			Component.translatable("screen.chestmemory.clan.detail_created").getString(),
			s.createdAt > 0
				? Component.translatable("screen.chestmemory.clan.ago", ageLabel(now - s.createdAt)).getString()
				: "—",
			left, y, contentW, ChestGuiStyle.TEXT_LIGHT
		);
		y += 11;
		ChestGuiStyle.drawDetailRow(
			graphics, this.font,
			Component.translatable("screen.chestmemory.clan.detail_updated").getString(),
			s.updatedAt > 0
				? Component.translatable("screen.chestmemory.clan.ago", ageLabel(now - s.updatedAt)).getString()
				: "—",
			left, y, contentW, ChestGuiStyle.TEXT_LIGHT
		);
		y += 11;
		ChestGuiStyle.drawDetailRow(
			graphics, this.font,
			Component.translatable("screen.chestmemory.clan.detail_items").getString(),
			Component.translatable(
				"screen.chestmemory.clan.detail_items_value",
				s.materials.size(), doneItems
			).getString(),
			left, y, contentW,
			leftItems == 0 && need > 0 ? ChestGuiStyle.LAMP_ONLINE : ChestGuiStyle.TEXT_LIGHT
		);
		y += 11;
		ChestGuiStyle.drawDetailRow(
			graphics, this.font,
			Component.translatable("screen.chestmemory.clan.detail_warehouse").getString(),
			s.stagingKeys == null || s.stagingKeys.isEmpty()
				? Component.translatable("screen.chestmemory.clan.detail_no_warehouse").getString()
				: Component.translatable("screen.chestmemory.clan.detail_chests", s.stagingKeys.size()).getString(),
			left, y, contentW,
			s.stagingKeys == null || s.stagingKeys.isEmpty()
				? ChestGuiStyle.LAMP_OFFLINE : ChestGuiStyle.TEXT_LIGHT
		);
		ChestGuiStyle.drawDetailRow(
			graphics, this.font,
			Component.translatable("screen.chestmemory.clan.detail_members").getString(),
			Component.translatable(
				"screen.chestmemory.clan.detail_members_value", online, s.members.size(), claimed
			).getString(),
			left, y, contentW,
			online > 0 ? ChestGuiStyle.TEXT_LIGHT : ChestGuiStyle.TEXT_ON_WOOD_MUTED
		);
		y += 15;

		// No quick-take strip here any more. It duplicated the Materials tab — the same
		// slots, the same click — and the detail rows it competed with are the reason this
		// tab exists. The card ends with a line telling the player where to go instead.
		int freeItems = 0;
		for (var e : s.materials.entrySet()) {
			ClanSession.ClanMaterial m = e.getValue();
			if (s.remaining(e.getKey()) > 0 && (m.claimedBy == null || m.claimedBy.isBlank())) {
				freeItems++;
			}
		}
		String hint;
		int hintColour;
		if (need == 0) {
			hint = Component.translatable("screen.chestmemory.clan.hint_empty").getString();
			hintColour = ChestGuiStyle.TEXT_MUTED;
		} else if (leftItems == 0) {
			hint = Component.translatable("screen.chestmemory.clan.hint_finished").getString();
			hintColour = ChestGuiStyle.LAMP_ONLINE;
		} else if (freeItems == 0) {
			hint = Component.translatable("screen.chestmemory.clan.all_claimed").getString();
			hintColour = ChestGuiStyle.TEXT_GOLD;
		} else {
			hint = Component.translatable("screen.chestmemory.clan.hint_free", freeItems).getString();
			hintColour = ChestGuiStyle.TEXT_MUTED;
		}
		ChestGuiStyle.drawCentered(
			graphics, this.font, ChestGuiStyle.ellipsize(this.font, hint, contentW),
			centerX, y + 2, hintColour
		);
	}


	private net.minecraft.world.item.ItemStack icon(String itemId) {
		return this.iconCache.computeIfAbsent(
			itemId, com.chestmemory.client.data.ItemStackKeys::toStack
		);
	}

	/**
	 * Materials tab: what the build still needs, with icons, and a claim on every row.
	 * <p>
	 * The clan screen used to show a percentage and nothing else — to see what was actually
	 * missing you had to leave it, open the chest panel and read the gather list there. This
	 * is the same information where it belongs, and clicking a row reserves the item without
	 * going anywhere.
	 */
	/** Slot pitch, shared with the main screen's item grid so the two look alike. */
	private static final int CELL = ChestGuiStyle.GRID_SLOT;

	/**
	 * Materials tab: the gather's items as an inventory grid.
	 * <p>
	 * Built from the same pieces as the chest panel — 18px slots on a light tray, scaled
	 * counts, a tint for state and the claimer's initial in the corner. It used to invent
	 * its own 24px cells on the bare panel, which is why it looked like a different mod.
	 * <p>
	 * Clicking a slot takes the item on; clicking your own gives it back. The screen stays
	 * open, so several can be taken in one visit.
	 */
	private void drawMaterials(GuiGraphicsExtractor graphics, ClanSession s, int left, int contentW) {
		int top = this.tabsY + 22;
		// Two lines of caption below the tray, clear of the back button.
		int bottom = this.panelTop + this.panelH - 50;
		// The tray has a 2px border, and the scrollbar lives inside it.
		int inner = contentW - 4;
		int perRow = Math.max(1, (inner - 4) / CELL);

		List<java.util.Map.Entry<String, ClanSession.ClanMaterial>> rows =
			new java.util.ArrayList<>(s.materials.entrySet());
		// Unfinished first, biggest remainder at the front: the top-left of the grid is
		// always the work that matters most, not whatever order the hub sent.
		rows.sort((a, b) -> {
			int ra = s.remaining(a.getKey());
			int rb = s.remaining(b.getKey());
			if ((ra == 0) != (rb == 0)) {
				return ra == 0 ? 1 : -1;
			}
			return Integer.compare(rb, ra);
		});

		this.materialIds = new java.util.ArrayList<>(rows.size());
		for (var e : rows) {
			this.materialIds.add(e.getKey());
		}
		this.materialGridPerRow = perRow;

		ChestGuiStyle.drawGridTray(graphics, left, top, contentW, bottom - top);
		int gridLeft = left + 3;
		int gridTop = top + 3;
		this.materialGridLeft = gridLeft;
		int totalRows = (rows.size() + perRow - 1) / perRow;
		this.materialScroll.layout(gridLeft, gridTop, inner - 2, bottom - 3, CELL, totalRows);

		if (rows.isEmpty()) {
			ChestGuiStyle.drawCentered(
				graphics, this.font,
				Component.translatable("screen.chestmemory.clan.no_materials").getString(),
				left + contentW / 2, top + Math.max(0, (bottom - top) / 2 - 4),
				0xFF505050
			);
			return;
		}

		String me = this.minecraft != null ? ClanSessionManager.localUuid(this.minecraft) : "";
		int hoverIdx = materialAt(this.hoverX, this.hoverY);
		for (int r = this.materialScroll.firstVisible(); r < this.materialScroll.lastVisible(); r++) {
			int y = this.materialScroll.rowY(r);
			for (int c = 0; c < perRow; c++) {
				int i = r * perRow + c;
				if (i >= rows.size()) {
					break;
				}
				var e = rows.get(i);
				ClanSession.ClanMaterial m = e.getValue();
				int x = gridLeft + c * CELL;
				int remaining = s.remaining(e.getKey());
				boolean done = remaining <= 0;
				boolean mine = m.claimedBy != null && m.claimedBy.equals(me);
				boolean taken = m.claimedBy != null && !m.claimedBy.isBlank() && !mine;

				ChestGuiStyle.drawSlot(graphics, x, y);
				graphics.item(icon(e.getKey()), x + 1, y + 1);

				// Tint over the icon, as the chest panel marks its own build items. Alpha is
				// low enough that the item stays recognisable — the point is the state, and
				// the count colour below carries it too.
				int countColour = 0xFFFFFFFF;
				if (done) {
					graphics.fill(x + 1, y + 1, x + 17, y + 17, 0x4430E060);
					countColour = 0xFF80FFA0;
				} else if (mine) {
					graphics.fill(x + 1, y + 1, x + 17, y + 17, 0x55FFAA20);
					countColour = 0xFFFFEE66;
				} else if (taken) {
					graphics.fill(x + 1, y + 1, x + 17, y + 17, 0x66101010);
					countColour = 0xFFBBBBBB;
				}
				if (i == hoverIdx) {
					graphics.fill(x + 1, y + 1, x + 17, y + 17, 0x66FFFFFF);
				}

				if (!done) {
					ChestGuiStyle.drawSlotCount(
						graphics, this.font, ChestGuiStyle.formatCount(remaining), x, y, countColour
					);
				}
				// Claimer's initial, top-left — same badge the chest panel uses, so a glance
				// tells you who is on what without reading a roster.
				if (mine || taken) {
					String badge = m.claimedName == null || m.claimedName.isBlank()
						? "?" : m.claimedName.substring(0, 1).toUpperCase(java.util.Locale.ROOT);
					int bc = mine ? 0xFFFFEE88 : 0xFFFFAAFF;
					graphics.text(this.font, badge, x + 2, y + 1, 0xE0000000, false);
					graphics.text(this.font, badge, x + 1, y, bc, false);
				}
			}
		}
		this.materialScroll.drawScrollbar(graphics);

		// Hovered item, named under the tray: a slot cannot carry a label, and a grid of
		// unfamiliar blocks is unreadable without one.
		int hy = this.panelTop + this.panelH - 46;
		if (hoverIdx >= 0 && hoverIdx < rows.size()) {
			var e = rows.get(hoverIdx);
			ClanSession.ClanMaterial m = e.getValue();
			int remaining = s.remaining(e.getKey());
			String name = com.chestmemory.client.data.ChestMemoryStorage.itemDisplayName(e.getKey());
			String detail;
			int colour;
			if (remaining <= 0) {
				detail = Component.translatable("screen.chestmemory.clan.mat_done").getString();
				colour = ChestGuiStyle.LAMP_ONLINE;
			} else if (m.claimedBy != null && m.claimedBy.equals(me)) {
				detail = Component.translatable("screen.chestmemory.clan.mat_yours_hint").getString();
				colour = ChestGuiStyle.TEXT_GOLD;
			} else if (m.claimedBy != null && !m.claimedBy.isBlank()) {
				detail = Component.translatable(
					"screen.chestmemory.clan.mat_taken_by", m.claimedName != null ? m.claimedName : "?"
				).getString();
				colour = ChestGuiStyle.TEXT_MUTED;
			} else {
				detail = Component.translatable("screen.chestmemory.clan.mat_take_hint").getString();
				colour = ChestGuiStyle.TEXT_MUTED;
			}
			ChestGuiStyle.drawCentered(
				graphics, this.font,
				ChestGuiStyle.ellipsize(this.font,
					name + " · " + Component.translatable(
						"screen.chestmemory.clan.mat_progress",
						Math.max(0, m.delivered), Math.max(0, m.need)
					).getString(), contentW),
				left + contentW / 2, hy, ChestGuiStyle.TEXT_TITLE
			);
			ChestGuiStyle.drawCentered(
				graphics, this.font, ChestGuiStyle.ellipsize(this.font, detail, contentW),
				left + contentW / 2, hy + 10, colour
			);
		} else {
			// Standing summary when nothing is hovered, so the space is never dead.
			int free = 0;
			for (var e : s.materials.entrySet()) {
				ClanSession.ClanMaterial m = e.getValue();
				if (s.remaining(e.getKey()) > 0 && (m.claimedBy == null || m.claimedBy.isBlank())) {
					free++;
				}
			}
			ChestGuiStyle.drawCentered(
				graphics, this.font,
				Component.translatable(
					"screen.chestmemory.clan.mat_legend", rows.size(), free
				).getString(),
				left + contentW / 2, hy + 5, ChestGuiStyle.TEXT_MUTED
			);
		}
	}

	/** Index of the material slot under the pointer, or -1. */
	private int materialAt(double mx, double my) {
		if (this.tab != TAB_MATERIALS || this.materialGridPerRow <= 0) {
			return -1;
		}
		int row = this.materialScroll.rowAt(mx, my, CELL);
		if (row < 0) {
			return -1;
		}
		int col = (int) ((mx - this.materialGridLeft) / CELL);
		if (col < 0 || col >= this.materialGridPerRow) {
			return -1;
		}
		int idx = row * this.materialGridPerRow + col;
		return idx < this.materialIds.size() ? idx : -1;
	}



	/**
	 * Members tab: one plank per player with what they reserved and how much they brought in.
	 * <p>
	 * This is the view that used to be missing — the roster only ever showed names, so there
	 * was no way to tell who was on the glass and who had already delivered.
	 */
	private void drawMembers(GuiGraphicsExtractor graphics, ClanSession s, int left, int contentW) {
		int top = this.tabsY + 22;
		int bottom = this.panelTop + this.panelH - 30;
		int rowH = 20;

		if (s.members.isEmpty()) {
			ChestGuiStyle.drawCentered(
				graphics, this.font,
				Component.translatable("screen.chestmemory.clan.no_members").getString(),
				left + contentW / 2, top + Math.max(0, (bottom - top) / 2 - 4),
				ChestGuiStyle.TEXT_MUTED
			);
			return;
		}

		// Scrolls now: the roster used to stop at the panel edge and print "+3 more", which
		// named a number the player had no way to reach.
		this.memberScroll.layout(left, top, contentW, bottom, rowH + 2, s.members.size());
		int rowW = this.memberScroll.rowWidth();
		for (int i = this.memberScroll.firstVisible(); i < this.memberScroll.lastVisible(); i++) {
			ClanSession.ClanMember m = s.members.get(i);
			int y = this.memberScroll.rowY(i);
			boolean host = m.uuid != null && m.uuid.equalsIgnoreCase(s.hostUuid);
			boolean away = s.isMemberAway(m);

			// What this member is holding, and how much of it already reached the warehouse.
			String claimItem = null;
			String claimId = null;
			int claimDone = 0;
			int claimNeed = 0;
			for (var e : s.materials.entrySet()) {
				ClanSession.ClanMaterial mat = e.getValue();
				if (mat.claimedBy != null && m.uuid != null && mat.claimedBy.equalsIgnoreCase(m.uuid)) {
					claimItem = ChestMemoryStorage.itemDisplayName(e.getKey());
					claimId = e.getKey();
					claimDone = Math.max(0, mat.delivered);
					claimNeed = Math.max(0, mat.need);
					break;
				}
			}

			int accent = away
				? ChestGuiStyle.TEXT_ON_WOOD_MUTED
				: (host ? ChestGuiStyle.LATCH : (claimItem != null ? 0xFF5FD068 : ChestGuiStyle.WOOD_LIGHT));
			ChestGuiStyle.drawMemberRow(graphics, left, y, rowW, rowH, accent, away);
			if (claimId != null) {
				// The item they are on, as an icon: faster to read than its name in the text.
				graphics.item(icon(claimId), left + rowW - 20, y + 2);
			}

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
			// Reserve the icon's width too, or a long name runs underneath it.
			int iconW = claimId != null ? 20 : 0;
			graphics.text(
				this.font,
				ChestGuiStyle.ellipsize(this.font, name, rowW - rightW - 18 - iconW),
				left + 7, textY, nameColour, false
			);
			graphics.text(
				this.font, right, left + rowW - 6 - iconW - rightW, textY, rightColour, false
			);
		}
		this.memberScroll.drawScrollbar(graphics);
	}

	/** Feed tab: recent claims, deliveries and arrivals, newest first. */
	private void drawFeed(GuiGraphicsExtractor graphics, int left, int contentW) {
		int top = this.tabsY + 22;
		int bottom = this.panelTop + this.panelH - 30;
		int lineH = 11;

		// The whole log, scrolled — it used to show only as many entries as happened to fit,
		// and the rest were simply unreachable.
		List<ClanEventLog.Entry> events = ClanEventLog.all();
		// Recessed panel behind the feed. Without it the light text sat on the light panel at
		// 1.19:1 contrast — the "barely visible" the user reported. On this backing it is 10.8:1.
		graphics.fill(left - 2, top - 3, left + contentW + 2, bottom + 1, ChestGuiStyle.WOOD_DARK);
		graphics.fill(left - 1, top - 2, left + contentW + 1, bottom, ChestGuiStyle.ROW_WOOD);
		if (events.isEmpty()) {
			ChestGuiStyle.drawCentered(
				graphics, this.font,
				Component.translatable("screen.chestmemory.clan.no_events").getString(),
				left + contentW / 2, top + Math.max(0, (bottom - top) / 2 - 4),
				ChestGuiStyle.TEXT_ON_WOOD_MUTED
			);
			return;
		}

		this.feedScroll.layout(left, top, contentW, bottom, lineH, events.size());
		int rowW = this.feedScroll.rowWidth();
		long now = System.currentTimeMillis();
		for (int i = this.feedScroll.firstVisible(); i < this.feedScroll.lastVisible(); i++) {
			ClanEventLog.Entry e = events.get(i);
			int y = this.feedScroll.rowY(i);
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
				this.font, e.text().getString(), rowW - 10 - ageW - 6
			);
			graphics.text(this.font, text, left + 8, y, ChestGuiStyle.TEXT_LIGHT, false);
			graphics.text(
				this.font, age, left + rowW - ageW, y,
				ChestGuiStyle.TEXT_ON_WOOD_MUTED, false
			);
		}
		this.feedScroll.drawScrollbar(graphics);
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
		// Set by init() from the actual button positions; the old hard-coded offset was right
		// in a session and wrong outside one, which is how the caption ended up on a button.
		int bottom = this.listBottom > 0 ? this.listBottom : this.panelTop + this.panelH - 52;
		int rowH = 20;

		List<com.chestmemory.client.clan.ClanRoster.Entry> entries =
			com.chestmemory.client.clan.ClanRoster.all();
		this.listRowsTop = y;
		this.listRowH = rowH + 2;
		this.gatherScroll.layout(left, y, contentW, bottom, rowH + 2, entries.size());
		int rowW = this.gatherScroll.rowWidth();
		this.listCodes = new java.util.ArrayList<>();

		if (entries.isEmpty()) {
			// Centred in the space the list would have used, so it reads as an empty area
			// rather than a stray line of text sitting on the controls below.
			ChestGuiStyle.drawCentered(
				graphics, this.font,
				Component.translatable("screen.chestmemory.clan.no_gathers").getString(),
				left + contentW / 2, y + Math.max(0, (bottom - y) / 2 - 8),
				ChestGuiStyle.TEXT_MUTED
			);
			ChestGuiStyle.drawCentered(
				graphics, this.font,
				Component.translatable("screen.chestmemory.clan.no_gathers_hint").getString(),
				left + contentW / 2, y + Math.max(0, (bottom - y) / 2 + 4),
				ChestGuiStyle.TEXT_ON_WOOD_MUTED
			);
			return;
		}

		String pending = ClanSessionManager.switchingTo();
		for (int i = this.gatherScroll.firstVisible(); i < this.gatherScroll.lastVisible(); i++) {
			com.chestmemory.client.clan.ClanRoster.Entry e = entries.get(i);
			y = this.gatherScroll.rowY(i);
			boolean active = current != null && e.code().equalsIgnoreCase(current.code);
			// The row being switched to is marked while the hub answers. Without it the click
			// produced no visible change at all, and the switch landed as a sudden jump.
			boolean loading = pending != null && pending.equalsIgnoreCase(e.code());
			// Active gather gets the gold marker; the rest read as available to switch to.
			int accent = loading
				? ChestGuiStyle.TEXT_GOLD
				: (active ? ChestGuiStyle.LATCH : ChestGuiStyle.WOOD_LIGHT);
			ChestGuiStyle.drawMemberRow(graphics, left, y, rowW, rowH, accent, !active && !loading);

			boolean iAmHost = active && this.minecraft != null && ClanSessionManager.isHost(this.minecraft);
			// Mark ownership: only the creator can delete a gather, so it should be visible
			// which of them are yours.
			String name = (loading ? "» " : active ? "▶ " : "") + (iAmHost ? "★ " : "") + e.code();
			String right = loading
				? Component.translatable("screen.chestmemory.clan.switching").getString()
				: e.need() > 0
				? Component.translatable("screen.chestmemory.clan.list_progress", e.percent()).getString()
				: "";
			int rightW = right.isEmpty() ? 0 : this.font.width(right);
			int textY = y + (rowH - this.font.lineHeight) / 2 + 1;

			// Code, then the build, then who runs it: a list of bare codes told the player
			// nothing about which gather was which.
			String label = e.label();
			String main = label.isBlank() ? name : name + " · " + label;
			if (!e.host().isBlank()) {
				main = main + " · " + e.host();
			}
			graphics.text(
				this.font,
				ChestGuiStyle.ellipsize(this.font, main, rowW - rightW - 16),
				left + 7, textY,
				active || loading ? ChestGuiStyle.TEXT_GOLD : ChestGuiStyle.TEXT_LIGHT,
				false
			);
			if (rightW > 0) {
				graphics.text(
					this.font, right, left + rowW - 6 - rightW, textY,
					active || loading ? ChestGuiStyle.TEXT_GOLD : ChestGuiStyle.TEXT_ON_WOOD_MUTED, false
				);
			}
			this.listCodes.add(e.code());
		}
		this.gatherScroll.drawScrollbar(graphics);
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
