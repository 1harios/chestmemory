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
	/**
	 * The working tab: what to collect, as a grid — for the clan gather when in one, for the
	 * player's own schematic when not. The separate Materials tab is gone; splitting "the
	 * gather" from "what the gather needs" made players hop between two tabs to do one job.
	 */
	private static final int TAB_GATHER = 0;
	/** Identity, progress and the facts — off the working tab, so the grid gets the room. */
	private static final int TAB_INFO = 1;
	private static final int TAB_MEMBERS = 2;
	private static final int TAB_FEED = 3;
	private static final int TAB_LIST = 4;
	private static final String[] TAB_KEYS = {
		"screen.chestmemory.clan.tab_gather",
		"screen.chestmemory.clan.tab_info",
		"screen.chestmemory.clan.tab_members",
		"screen.chestmemory.clan.tab_feed",
		"screen.chestmemory.clan.tab_list"
	};

	/** What the gather tab is looking at. Decides the grid's data source and the buttons. */
	enum GatherMode {
		/** In a hub session: shared list, claims, attribution. */
		CLAN,
		/** No session, but Litematica has a material list: the player's own build. */
		SOLO,
		/** Nothing to collect from: no session, no schematic. */
		EMPTY
	}

	/**
	 * Tabs visible right now, as indices into {@link #TAB_KEYS}. Members and feed describe a
	 * session, so outside one they are not offered at all instead of being shown empty.
	 */
	private int[] visibleTabs() {
		if (ClanSessionManager.isInSession()) {
			return new int[]{TAB_GATHER, TAB_INFO, TAB_MEMBERS, TAB_FEED, TAB_LIST};
		}
		if (gatherMode() == GatherMode.SOLO) {
			return new int[]{TAB_GATHER, TAB_INFO, TAB_LIST};
		}
		return new int[]{TAB_GATHER, TAB_LIST};
	}

	/** Current gather-tab mode; the grid, the header and the buttons all follow it. */
	private GatherMode gatherMode() {
		if (ClanSessionManager.isInSession()) {
			return GatherMode.CLAN;
		}
		boolean hasList = LitematicaAccess.isAvailable()
			&& com.chestmemory.client.litematica.LitematicaCompat.hasActiveMaterialListSafe();
		// A parked or running solo gather keeps the tab alive even while Litematica is
		// mid-reload (portal trips drop the list for a moment).
		if (hasList || com.chestmemory.client.litematica.BuildGatherSession.isActive()) {
			return GatherMode.SOLO;
		}
		return GatherMode.EMPTY;
	}

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
	private EditBox codeBox;
	private String status = "";
	/** Two-step guard for "say in chat": the code is readable by everyone on the server. */
	private boolean sayCodeArmed;
	/** Host settings view on the gather tab (rename, claims reset, close). */
	private boolean hostSettings;
	/** Rename draft, kept across widget rebuilds exactly like the code draft. */
	private String renameDraft = "";
	private @org.jspecify.annotations.Nullable EditBox renameBox;
	/** Two-step guards for the destructive settings rows. */
	private boolean releaseArmed;
	private boolean closeArmed;
	/** Member armed for a kick (host clicked their row once), or null. */
	private @org.jspecify.annotations.Nullable String kickArmUuid;
	/**
	 * Tick countdowns for the armed confirms above — 5 seconds, like the chest panel's
	 * Clear. Armed used to mean armed forever: a host who clicked «Сказать код» once,
	 * browsed other tabs and came back minutes later broadcast the session code to the
	 * whole server with what looked like a first click.
	 */
	private static final int ARM_TIMEOUT_TICKS = 100;
	private int sayCodeArmTicks;
	private int releaseArmTicks;
	private int closeArmTicks;
	private int kickArmTicks;
	/** Search over the gather grid; kept across rebuilds like the code draft. */
	private String gatherQuery = "";
	private @org.jspecify.annotations.Nullable EditBox gatherSearchBox;
	/** Whether the working tab carries the search row this build (shifts the grid down). */
	private boolean searchOnGatherTab;
	/** General-memory items matching the search, appended after the gather cells. */
	private java.util.List<com.chestmemory.client.data.ItemSummary> externalRows = java.util.List.of();
	/** Ids of those appended cells — clicked, they glow chests instead of claiming. */
	private java.util.Set<String> externalIds = java.util.Set.of();
	/** Selected tab; kept across rebuildWidgets so polling does not snap you back. */
	private int tab = TAB_GATHER;
	private int hoveredTab = -1;
	/** Tab strip geometry, filled while rendering and used for hit-testing. */
	private int tabsY = -1;
	private int tabsLeft;
	private int tabsWidth;
	/** Live chest stock per item, briefly cached — the grid asks per cell per frame. */
	private final java.util.Map<String, Integer> stockCache = new java.util.HashMap<>();
	private long stockCacheAt;
	/** Y where the material grid must stop, set by init() next to the controls below it. */
	private int gridBottom = -1;
	/**
	 * Y where the gather list has to stop, set by init() next to the controls it must clear.
	 * Hard-coding the offset in the drawing code is what let the empty-list caption land on
	 * top of the buttons.
	 */
	private int listBottom = -1;
	/**
	 * Y of the kick hint under the host-settings rows, recorded by initHostSettings().
	 * It used to be re-derived in the drawing code as a chain of hard-coded offsets that
	 * had to mirror the row layout by hand — adding one settings row put the hint on top
	 * of a toggle.
	 */
	private int hostSettingsHintY;
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
	/** Solo gather state the buttons were built for: start/next/stop swap with it. */
	private boolean builtForSoloActive;
	private @org.jspecify.annotations.Nullable GatherMode builtForMode;

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
		// Armed confirms disarm themselves, mirroring the chest panel's Clear countdown —
		// see ARM_TIMEOUT_TICKS for the stale-arm broadcast this prevents.
		boolean disarmed = false;
		if (this.sayCodeArmed && --this.sayCodeArmTicks <= 0) {
			this.sayCodeArmed = false;
			disarmed = true;
		}
		if (this.releaseArmed && --this.releaseArmTicks <= 0) {
			this.releaseArmed = false;
			disarmed = true;
		}
		if (this.closeArmed && --this.closeArmTicks <= 0) {
			this.closeArmed = false;
			disarmed = true;
		}
		if (this.kickArmUuid != null && --this.kickArmTicks <= 0) {
			this.kickArmUuid = null;
			disarmed = true;
		}
		if (disarmed) {
			// The standing hint («нажмите ещё раз…») described the armed state; it must
			// not outlive it and keep promising a second click that now arms again.
			this.status = "";
		}
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
		boolean soloActive = com.chestmemory.client.litematica.BuildGatherSession.isActive();
		GatherMode mode = gatherMode();
		if (!java.util.Objects.equals(switching, this.builtForSwitching)
			|| busy != this.builtForBusy
			|| !java.util.Objects.equals(code, this.builtForCode)
			|| hasCode != this.builtForHasCode
			|| hub != this.builtForHub
			|| soloActive != this.builtForSoloActive
			|| mode != this.builtForMode
			// A dropped arm changes button labels («…точно?» back to the plain caption),
			// and those are baked at build time.
			|| disarmed) {
			// A rebuild recreates the EditBox, and typing the first character triggers one —
			// so without this the box loses focus after a single keystroke and the player has
			// to click it again for every letter of the code.
			boolean wasTyping = this.codeBox != null && this.codeBox.isFocused();
			boolean wasRenaming = this.renameBox != null && this.renameBox.isFocused();
			boolean wasSearching = this.gatherSearchBox != null && this.gatherSearchBox.isFocused();
			this.rebuildWidgets();
			if (wasTyping && this.codeBox != null) {
				this.setFocused(this.codeBox);
				this.codeBox.setFocused(true);
				this.codeBox.moveCursorToEnd(false);
			}
			if (wasRenaming && this.renameBox != null) {
				this.setFocused(this.renameBox);
				this.renameBox.setFocused(true);
				this.renameBox.moveCursorToEnd(false);
			}
			if (wasSearching && this.gatherSearchBox != null) {
				this.setFocused(this.gatherSearchBox);
				this.gatherSearchBox.setFocused(true);
				this.gatherSearchBox.moveCursorToEnd(false);
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
		this.builtForSoloActive = com.chestmemory.client.litematica.BuildGatherSession.isActive();
		this.builtForMode = gatherMode();
		// Every per-tab EditBox field is dropped up front. A rebuild that did not rebuild
		// a box left the field aimed at the detached widget, still marked focused — so
		// after a tab switch, tick()'s focus restore handed real focus back to that
		// orphan, and keystrokes edited an invisible box (silently changing codeDraft).
		this.codeBox = null;
		this.renameBox = null;
		this.gatherSearchBox = null;
		this.searchOnGatherTab = false;
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
		// no URL, no token. The manual field appears only for a build without one.
		this.hubBox = null;
		if (ClanDefaults.hasBakedHub()) {
			// A corner lamp instead of a full-width strip: the strip spent a whole row on
			// one word. The word lives in the tooltip now, the colour is read live every
			// frame, and clicking the lamp re-asks the hub — it doubles as the retry.
			ClanSessionManager.HubState hubNow = ClanSessionManager.hubState();
			Component lampTip = switch (hubNow) {
				case ONLINE -> Component.translatable("screen.chestmemory.clan.hub_lamp_online");
				case OFFLINE -> Component.translatable("screen.chestmemory.clan.hub_lamp_offline");
				case UNKNOWN -> Component.translatable("screen.chestmemory.clan.hub_checking");
			};
			this.addRenderableWidget(new HubLampButton(
				this.panelLeft + 8, this.panelTop + 12, 10, lampTip,
				() -> switch (ClanSessionManager.hubState()) {
					case ONLINE -> ChestGuiStyle.LAMP_ONLINE;
					case OFFLINE -> ChestGuiStyle.LAMP_OFFLINE;
					case UNKNOWN -> ChestGuiStyle.LAMP_CHECKING;
				},
				this::retryHubCheck
			));
			if (this.minecraft != null) {
				ClanSessionManager.checkHubAsync(this.minecraft, null);
			}
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
					// Only the URL: this callback outlived the token box (see above) and
					// kept dereferencing it, so «Сохранить хаб» crashed every build
					// without a baked hub.
					ModSettings.get().setClanHubUrl(this.hubBox.getValue());
					this.status = Component.translatable("screen.chestmemory.clan.hub_saved").getString();
				}
			));
			y += rowH + gap + 2;
		}

		// A tab that is not offered right now falls back to the working tab: members and
		// feed describe a session, so outside one they are not just empty — they are gone.
		boolean tabVisible = false;
		for (int t : visibleTabs()) {
			if (t == this.tab) {
				tabVisible = true;
				break;
			}
		}
		if (!tabVisible) {
			this.tab = TAB_GATHER;
		}

		// The gather's own settings sit behind a corner pencil — the same place the main
		// screen keeps its gear, so the hand already knows where to look. Host only: the
		// view behind it renames, resets and closes the shared session.
		if (!this.hostSettings && gatherMode() == GatherMode.CLAN
			&& this.minecraft != null && ClanSessionManager.isHost(this.minecraft)) {
			this.addRenderableWidget(new PencilIconButton(
				this.panelLeft + this.panelW - 16 - 6, this.panelTop + 9, 16,
				Component.translatable("screen.chestmemory.clan.settings_icon_tip"),
				() -> {
					ClanSession cur = ClanSessionManager.session();
					this.renameDraft = cur != null && cur.schemaName != null ? cur.schemaName : "";
					this.hostSettings = true;
					this.releaseArmed = false;
					this.closeArmed = false;
					this.tab = TAB_GATHER;
					this.status = "";
					this.rebuildWidgets();
				}
			));
		}

		// The tab strip is always there: the Gathers list must stay reachable outside a
		// session, and the gather tab now has a solo life of its own.
		this.tabsLeft = left;
		this.tabsWidth = w;
		this.tabsY = y;
		y += 20;

		boolean in = ClanSessionManager.isInSession();
		int half = (w - gap) / 2;

		if (this.tab == TAB_LIST) {
			// One reading line for joining: код → вставить → вступить, left to right in a
			// single row. The join button used to live a row below its own code box.
			int rowTop = this.panelTop + this.panelH - 48;
			this.listBottom = rowTop - 6;
			boolean switching = ClanSessionManager.switchingTo() != null;
			boolean canCreate = LitematicaAccess.isAvailable()
				&& com.chestmemory.client.litematica.LitematicaCompat.hasActiveMaterialListSafe();
			int pasteW = 64;
			int joinW = 96;
			int codeW = w - pasteW - joinW - 2 * gap;
			this.codeBox = new EditBox(
				this.font, left, rowTop, codeW, rowH,
				Component.translatable("screen.chestmemory.clan.code")
			);
			this.codeBox.setMaxLength(16);
			this.codeBox.setHint(Component.literal("CM-XXXX"));
			this.codeBox.setValue(this.codeDraft);
			this.codeBox.setResponder(v -> this.codeDraft = v);
			this.addRenderableWidget(this.codeBox);
			SettingRowButton pasteBtn = new SettingRowButton(
				left + codeW + gap, rowTop, pasteW, rowH,
				Component.translatable("screen.chestmemory.clan.paste_code"),
				this::pasteCodeFromClipboard
			);
			pasteBtn.active = !switching;
			this.addRenderableWidget(pasteBtn);
			SettingRowButton joinBtn = new SettingRowButton(
				left + codeW + pasteW + 2 * gap, rowTop, joinW, rowH,
				Component.translatable("screen.chestmemory.clan.join"),
				() -> {
					saveHubQuiet();
					if (this.minecraft == null || this.codeBox == null) {
						return;
					}
					this.status = Component.translatable("screen.chestmemory.clan.working").getString();
					if (ClanSessionManager.isInSession()) {
						ClanSessionManager.switchToAsync(this.minecraft, this.codeBox.getValue(), this::rebuildWidgets);
					} else {
						ClanSessionManager.joinAsync(this.minecraft, this.codeBox.getValue(), this::rebuildWidgets);
					}
				}
			);
			joinBtn.active = !switching && !this.codeDraft.isBlank();
			this.addRenderableWidget(joinBtn);

			// Bottom row: create on the left, back on the right — deleting a gather is a
			// host-settings action (за карандашом), and the lamp owns the hub retry.
			SettingRowButton createBtn = new SettingRowButton(
				left, this.panelTop + this.panelH - 26, half, rowH,
				Component.translatable(in
					? "screen.chestmemory.clan.create_more"
					: "screen.chestmemory.clan.create"),
				() -> {
					saveHubQuiet();
					if (this.minecraft == null) {
						return;
					}
					this.status = Component.translatable("screen.chestmemory.clan.working").getString();
					ClanSessionManager.createAsync(this.minecraft, this::rebuildWidgets);
				}
			);
			createBtn.active = canCreate && !switching;
			createBtn.setTooltip(net.minecraft.client.gui.components.Tooltip.create(
				canCreate
					? Component.translatable(in
						? "screen.chestmemory.clan.create_more_tip"
						: "screen.chestmemory.clan.create_tip")
					: Component.translatable("screen.chestmemory.clan.create_need_list")
			));
			this.addRenderableWidget(createBtn);
			this.addRenderableWidget(new SettingRowButton(
				left + half + gap, this.panelTop + this.panelH - 26, half, rowH,
				Component.translatable("screen.chestmemory.clan.back"),
				this::onClose
			));
			return;
		}

		if (this.tab == TAB_INFO) {
			// The facts tab also carries the session tools that used to crowd the grid:
			GatherMode infoMode = gatherMode();
			int infoRow2 = this.panelTop + this.panelH - 26;
			if (infoMode == GatherMode.CLAN) {
				ClanSession s = ClanSessionManager.session();
				String code = s != null ? s.code : "?";
				int infoRow0 = infoRow2 - 2 * (rowH + gap);
				int infoRow1 = infoRow2 - (rowH + gap);
				this.addRenderableWidget(new SettingRowButton(
					left, infoRow0, half, rowH, stagingButtonLabel(), this::toggleStagingPick
				));
				SettingRowButton clearStaging = new SettingRowButton(
					left + half + gap, infoRow0, half, rowH,
					Component.translatable("screen.chestmemory.clan.staging_clear"),
					this::clearStagingChests
				);
				clearStaging.active = ChestMemoryStorage.get().stagingCount() > 0
					|| com.chestmemory.client.data.StagingPickMode.isActive();
				this.addRenderableWidget(clearStaging);
				int sayW = w - gap - 96;
				this.addRenderableWidget(new SettingRowButton(
					left, infoRow1, sayW, rowH,
					this.sayCodeArmed
						? Component.translatable("screen.chestmemory.clan.say_code_confirm")
						: Component.translatable("screen.chestmemory.clan.say_code", code),
					() -> {
						if (this.minecraft == null || this.minecraft.player == null || s == null) {
							return;
						}
						// Public chat: anyone on the server can read the code and join the
						// session, so require a second click instead of firing on the first.
						if (!this.sayCodeArmed) {
							this.sayCodeArmed = true;
							this.sayCodeArmTicks = ARM_TIMEOUT_TICKS;
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
					left + sayW + gap, infoRow1, 96, rowH,
					Component.translatable("screen.chestmemory.clan.copy_code"),
					() -> {
						if (this.minecraft != null && s != null) {
							this.minecraft.keyboardHandler.setClipboard(s.code);
							this.status = Component.translatable("screen.chestmemory.clan.copied", s.code).getString();
						}
					}
				));
			} else if (infoMode == GatherMode.SOLO) {
				int infoRow1 = infoRow2 - (rowH + gap);
				this.addRenderableWidget(new SettingRowButton(
					left, infoRow1, half, rowH, stagingButtonLabel(), this::toggleStagingPick
				));
				SettingRowButton clearStaging = new SettingRowButton(
					left + half + gap, infoRow1, half, rowH,
					Component.translatable("screen.chestmemory.clan.staging_clear"),
					this::clearStagingChests
				);
				clearStaging.active = ChestMemoryStorage.get().stagingCount() > 0
					|| com.chestmemory.client.data.StagingPickMode.isActive();
				this.addRenderableWidget(clearStaging);
			}
			this.addRenderableWidget(new SettingRowButton(
				left, infoRow2, w, rowH,
				Component.translatable("screen.chestmemory.clan.back"),
				this::onClose
			));
			return;
		}

		if (this.tab == TAB_MEMBERS || this.tab == TAB_FEED) {
			// Members / feed: no controls, just the back row at the bottom.
			this.addRenderableWidget(new SettingRowButton(
				left, this.panelTop + this.panelH - 26, w, rowH,
				Component.translatable("screen.chestmemory.clan.back"),
				this::onClose
			));
			return;
		}

		// ── the working tab: search, the grid, and only the controls that must live here ──
		GatherMode mode = gatherMode();
		int row2 = this.panelTop + this.panelH - 26;
		int row1 = row2 - rowH - gap;

		if (mode == GatherMode.CLAN) {
			ClanSession s = ClanSessionManager.session();
			boolean host = this.minecraft != null && ClanSessionManager.isHost(this.minecraft);
			if (this.hostSettings && host) {
				initHostSettings(left, w, half, gap, rowH, row2);
				return;
			}
			this.hostSettings = false;
			addGatherSearch(left, y, w);
			// One row of controls: warehouse and code tools moved to Инфо, and the hover
			// facts ride a vanilla tooltip — the grid gets everything above this line.
			this.gridBottom = row2 - 16;
			if (host) {
				this.addRenderableWidget(new SettingRowButton(
					left, row2, w, rowH,
					Component.translatable("screen.chestmemory.clan.back"),
					this::onClose
				));
			} else {
				this.addRenderableWidget(new SettingRowButton(
					left, row2, half, rowH,
					Component.translatable("screen.chestmemory.clan.leave"),
					() -> {
						if (this.minecraft == null) {
							return;
						}
						this.status = Component.translatable("screen.chestmemory.clan.working").getString();
						ClanSessionManager.leaveAsync(this.minecraft, this::rebuildWidgets);
					}
				));
				this.addRenderableWidget(new SettingRowButton(
					left + half + gap, row2, half, rowH,
					Component.translatable("screen.chestmemory.clan.back"),
					this::onClose
				));
			}
			return;
		}

		if (mode == GatherMode.SOLO) {
			addGatherSearch(left, y, w);
			// Start/next/stop stay: they ARE the gathering. The warehouse row moved to Инфо.
			this.gridBottom = row1 - 26;
			boolean soloActive = com.chestmemory.client.litematica.BuildGatherSession.isActive();
			if (soloActive) {
				this.addRenderableWidget(new SettingRowButton(
					left, row1, half, rowH,
					Component.translatable("screen.chestmemory.clan.solo_next"),
					() -> {
						if (this.minecraft != null) {
							com.chestmemory.client.litematica.BuildGatherSession.skipCurrentItem(this.minecraft);
							this.rebuildWidgets();
						}
					}
				));
				this.addRenderableWidget(new SettingRowButton(
					left + half + gap, row1, half, rowH,
					Component.translatable("screen.chestmemory.clan.solo_stop"),
					() -> {
						com.chestmemory.client.litematica.BuildGatherSession.clear();
						this.status = Component.translatable("screen.chestmemory.clan.solo_stopped").getString();
						this.rebuildWidgets();
					}
				));
			} else {
				SettingRowButton start = new SettingRowButton(
					left, row1, w, rowH,
					Component.translatable("screen.chestmemory.clan.solo_start"),
					() -> {
						if (this.minecraft != null) {
							com.chestmemory.client.litematica.BuildGatherSession.startQueue(
								this.minecraft, null, List.of()
							);
							this.rebuildWidgets();
						}
					}
				);
				start.active = LitematicaAccess.isAvailable()
					&& com.chestmemory.client.litematica.LitematicaCompat.hasActiveMaterialListSafe();
				start.setTooltip(net.minecraft.client.gui.components.Tooltip.create(
					Component.translatable("screen.chestmemory.clan.solo_start_tip")
				));
				this.addRenderableWidget(start);
			}
			this.addRenderableWidget(new SettingRowButton(
				left, row2, w, rowH,
				Component.translatable("screen.chestmemory.clan.back"),
				this::onClose
			));
			return;
		}

		// EMPTY: nothing to collect from — the body explains the two ways to get a gather,
		// the buttons take you there.
		this.gridBottom = -1;
		this.addRenderableWidget(new SettingRowButton(
			left, row2, half, rowH,
			Component.translatable("screen.chestmemory.clan.goto_list"),
			() -> {
				this.tab = TAB_LIST;
				this.status = "";
				this.rebuildWidgets();
			}
		));
		this.addRenderableWidget(new SettingRowButton(
			left + half + gap, row2, half, rowH,
			Component.translatable("screen.chestmemory.clan.back"),
			this::onClose
		));
	}

	/**
	 * Host settings for the gather: rename, reset every claim, close the session.
	 * Kicking lives on the Members tab (click a row) — the roster is already there.
	 */
	private void initHostSettings(int left, int w, int half, int gap, int rowH, int row2) {
		this.gridBottom = -1;
		int y = this.tabsY + 20 + 18;
		int renameBtnW = 96;
		this.renameBox = new EditBox(
			this.font, left, y, w - renameBtnW - gap, rowH,
			Component.translatable("screen.chestmemory.clan.rename_hint")
		);
		this.renameBox.setMaxLength(48);
		this.renameBox.setHint(Component.translatable("screen.chestmemory.clan.rename_hint"));
		this.renameBox.setValue(this.renameDraft);
		this.renameBox.setResponder(v -> this.renameDraft = v);
		this.addRenderableWidget(this.renameBox);
		ClanSession cur = ClanSessionManager.session();
		String currentName = cur != null && cur.schemaName != null ? cur.schemaName : "";
		SettingRowButton rename = new SettingRowButton(
			left + w - renameBtnW, y, renameBtnW, rowH,
			Component.translatable("screen.chestmemory.clan.rename_btn"),
			() -> {
				if (this.minecraft == null) {
					return;
				}
				this.status = Component.translatable("screen.chestmemory.clan.working").getString();
				ClanSessionManager.renameAsync(this.minecraft, this.renameDraft, this::rebuildWidgets);
			}
		);
		rename.active = !ClanSessionManager.isBusy();
		this.addRenderableWidget(rename);
		y += rowH + 6;

		this.addRenderableWidget(new SettingRowButton(
			left, y, w, rowH,
			this.releaseArmed
				? Component.translatable("screen.chestmemory.clan.release_all_confirm")
				: Component.translatable("screen.chestmemory.clan.release_all"),
			() -> {
				if (this.minecraft == null) {
					return;
				}
				// Someone's evening of mining hangs off these claims — ask twice.
				if (!this.releaseArmed) {
					this.releaseArmed = true;
					this.releaseArmTicks = ARM_TIMEOUT_TICKS;
					this.rebuildWidgets();
					return;
				}
				this.releaseArmed = false;
				this.status = Component.translatable("screen.chestmemory.clan.working").getString();
				ClanSessionManager.releaseClaimsAsync(this.minecraft, this::rebuildWidgets);
			}
		));
		y += rowH + 4;

		this.addRenderableWidget(new SettingRowButton(
			left, y, w, rowH,
			this.closeArmed
				? Component.translatable("screen.chestmemory.clan.close_confirm")
				: Component.translatable("screen.chestmemory.clan.close_session"),
			() -> {
				if (this.minecraft == null) {
					return;
				}
				if (!this.closeArmed) {
					this.closeArmed = true;
					this.closeArmTicks = ARM_TIMEOUT_TICKS;
					this.status = Component.translatable("screen.chestmemory.clan.delete_hint").getString();
					this.rebuildWidgets();
					return;
				}
				this.closeArmed = false;
				this.status = Component.translatable("screen.chestmemory.clan.working").getString();
				ClanSessionManager.leaveAsync(this.minecraft, this::rebuildWidgets);
			}
		));
		y += rowH + 6;

		// The two gather toggles players reach for mid-build, surfaced here so the host
		// does not have to leave for the main gear to flip them.
		this.addRenderableWidget(new ToggleSwitchRow(
			left, y, w, rowH,
			Component.translatable("screen.chestmemory.clan.set_chat"),
			() -> ModSettings.get().gatherChatMessages(),
			() -> ModSettings.get().setGatherChatMessages(!ModSettings.get().gatherChatMessages())
		));
		y += rowH + 4;
		this.addRenderableWidget(new ToggleSwitchRow(
			left, y, w, rowH,
			Component.translatable("screen.chestmemory.clan.set_autonext"),
			() -> ModSettings.get().gatherAutoAdvance(),
			() -> ModSettings.get().setGatherAutoAdvance(!ModSettings.get().gatherAutoAdvance())
		));
		// The kick hint is painted by drawHostSettings; recording the row cursor here is
		// what keeps the two in lockstep when a settings row is added or reordered.
		this.hostSettingsHintY = y + rowH + 8;

		this.addRenderableWidget(new SettingRowButton(
			left, row2, half, rowH,
			Component.translatable("screen.chestmemory.clan.back_to_gather"),
			() -> {
				this.hostSettings = false;
				this.releaseArmed = false;
				this.closeArmed = false;
				this.status = "";
				this.rebuildWidgets();
			}
		));
		this.addRenderableWidget(new SettingRowButton(
			left + half + gap, row2, half, rowH,
			Component.translatable("screen.chestmemory.clan.back"),
			this::onClose
		));
	}

	/**
	 * Append general-memory items matching the search after the gather cells, dimmed:
	 * grey count = chest stock, no tint, no ring. Clicking one glows its chests — the
	 * whole point of finding it — instead of trying to claim what is not in the gather.
	 */
	private void appendExternalMatches(List<MatCell> cells, String q, java.util.Set<String> gatherIds) {
		this.externalRows = java.util.List.of();
		this.externalIds = java.util.Set.of();
		if (q.isEmpty() || this.minecraft == null) {
			return;
		}
		java.util.List<com.chestmemory.client.data.ItemSummary> ext = new java.util.ArrayList<>();
		java.util.Set<String> ids = new java.util.HashSet<>();
		String dim = this.minecraft.level != null
			? ChestMemoryStorage.dimensionId(this.minecraft.level) : null;
		net.minecraft.world.phys.Vec3 pos = this.minecraft.player != null
			? this.minecraft.player.position() : null;
		for (com.chestmemory.client.data.ItemSummary sum : ChestMemoryStorage.get().listItems(
			q,
			com.chestmemory.client.data.ContainerFilter.ALL,
			com.chestmemory.client.data.DimensionChoice.ALL,
			com.chestmemory.client.data.ListScope.WORLD_TOTAL,
			dim, pos, 0,
			com.chestmemory.client.data.SortMode.COUNT
		)) {
			if (gatherIds.contains(sum.itemId())) {
				continue;
			}
			ext.add(sum);
			ids.add(sum.itemId());
			cells.add(new MatCell(sum.itemId(), sum.totalCount(), 0, 0xFF9E9E9E, null, 0, 0));
			if (ext.size() >= 34) {
				break;
			}
		}
		this.externalRows = ext;
		this.externalIds = ids;
	}

	/** Search over the grid — and over the whole chest memory, appended dimmed. */
	private void addGatherSearch(int left, int y, int w) {
		this.gatherSearchBox = new EditBox(
			this.font, left, y, w, 18,
			Component.translatable("screen.chestmemory.clan.search")
		);
		this.gatherSearchBox.setMaxLength(64);
		this.gatherSearchBox.setHint(Component.translatable("screen.chestmemory.clan.search_hint"));
		this.gatherSearchBox.setTextColor(0xFFFFFFFF);
		this.gatherSearchBox.setValue(this.gatherQuery);
		// The grid reads the query per frame, so typing filters live — no rebuilds.
		this.gatherSearchBox.setResponder(v -> this.gatherQuery = v);
		this.addRenderableWidget(this.gatherSearchBox);
		this.searchOnGatherTab = true;
	}

	/** True when the item's id or display name contains the query. */
	private static boolean matchesQuery(String itemId, String q) {
		if (itemId.toLowerCase(java.util.Locale.ROOT).contains(q)) {
			return true;
		}
		return ChestMemoryStorage.itemDisplayName(itemId)
			.toLowerCase(java.util.Locale.ROOT).contains(q);
	}

	/** A found-by-search memory item: no claim, no route queue — just glow its chests. */
	private void externalHighlight(String itemId) {
		com.chestmemory.client.highlight.ChestHighlighter.highlightItem(
			itemId, ModSettings.get().highlightDurationMs()
		);
		if (this.minecraft != null && this.minecraft.player != null) {
			this.minecraft.player.sendSystemMessage(Component.translatable(
				"message.chestmemory.clan_external_glow",
				ChestMemoryStorage.itemDisplayName(itemId)
			));
		}
		this.onClose();
	}

	/** «Склад: N» — toggle the pick-warehouse mode shared with the scanner. */
	private Component stagingButtonLabel() {
		if (com.chestmemory.client.data.StagingPickMode.isActive()) {
			return Component.translatable("screen.chestmemory.clan.staging_picking");
		}
		return Component.translatable(
			"screen.chestmemory.clan.staging_btn",
			ChestMemoryStorage.get().stagingCount()
		);
	}

	/**
	 * Enter or leave warehouse-pick mode. Entering closes the screen: the chests to be
	 * marked stand in the world, and every one the player opens while the mode is on
	 * becomes staging (shared with the clan when in a session).
	 */
	private void toggleStagingPick() {
		boolean nowActive = com.chestmemory.client.data.StagingPickMode.toggle();
		if (nowActive) {
			this.status = "";
			this.onClose();
			return;
		}
		this.status = Component.translatable(
			"screen.chestmemory.clan.staging_saved",
			ChestMemoryStorage.get().stagingCount()
		).getString();
		this.rebuildWidgets();
	}

	/** Drop every staging mark — locally, and on the hub when a session shares them. */
	private void clearStagingChests() {
		com.chestmemory.client.data.StagingPickMode.stop(false);
		ChestMemoryStorage.get().clearStaging();
		if (this.minecraft != null && ClanSessionManager.isInSession()) {
			// Replace the hub's shared list with the now-empty local one, so the old
			// warehouse stops glowing for every member, not only for whoever cleared it.
			ClanSessionManager.pushStagingKeysAsync(this.minecraft, true);
		}
		this.status = Component.translatable("screen.chestmemory.clan.staging_cleared").getString();
		this.rebuildWidgets();
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
		boolean mine = m != null && m.claimedBy != null && m.claimedBy.equals(me);
		net.minecraft.client.Minecraft mc = this.minecraft;
		if (mine) {
			// Giving the item back also drops it as the gather target — AFTER the hub
			// confirms. Refocusing immediately read the stale session, still saw this
			// claim as ours, and re-targeted the very item that was just released.
			ClanSessionManager.claimToggleAsync(mc, itemId, () -> {
				if (itemId.equals(com.chestmemory.client.litematica.BuildGatherSession.currentItemId())) {
					com.chestmemory.client.litematica.BuildGatherSession.dropCurrentClaimFocus(mc);
				}
				this.rebuildWidgets();
			});
			return;
		}
		// Taking an item aims the gather at it the moment the hub confirms — unless the
		// player is already working one of their own claims. Glass claimed first stays
		// the job; wool claimed second queues behind it in click order.
		String cur = com.chestmemory.client.litematica.BuildGatherSession.currentItemId();
		boolean keepCurrent = com.chestmemory.client.litematica.BuildGatherSession.isActive()
			&& cur != null && ClanSessionManager.isClaimedByMe(mc, cur);
		if (!keepCurrent) {
			com.chestmemory.client.litematica.BuildGatherSession.setPendingClaimFocus(itemId);
		}
		ClanSessionManager.claimToggleAsync(mc, itemId, () -> {
			if (!com.chestmemory.client.litematica.BuildGatherSession.isActive()) {
				com.chestmemory.client.litematica.BuildGatherSession.startQueue(mc, itemId, List.of());
			} else if (!keepCurrent) {
				com.chestmemory.client.litematica.BuildGatherSession.focusClaimed(mc, itemId);
			}
			this.rebuildWidgets();
		});
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

	/** Type-to-search on the working tab: keys land in the box without clicking it. */
	@Override
	public boolean charTyped(net.minecraft.client.input.CharacterEvent event) {
		if (this.tab == TAB_GATHER && this.gatherSearchBox != null && !this.hostSettings) {
			if (this.getFocused() != this.gatherSearchBox) {
				this.setFocused(this.gatherSearchBox);
				this.gatherSearchBox.setFocused(true);
			}
			return this.gatherSearchBox.charTyped(event);
		}
		return super.charTyped(event);
	}

	@Override
	public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
		// Backspace / arrows reach the search box even if focus drifted to a button —
		// the same affordance the main panel gives its own search.
		if (this.tab == TAB_GATHER && this.gatherSearchBox != null && !this.hostSettings
			&& this.getFocused() != this.gatherSearchBox) {
			int key = event.key();
			boolean searchKey = key == org.lwjgl.glfw.GLFW.GLFW_KEY_BACKSPACE
				|| key == org.lwjgl.glfw.GLFW.GLFW_KEY_DELETE
				|| key == org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT
				|| key == org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT;
			if (searchKey) {
				this.setFocused(this.gatherSearchBox);
				this.gatherSearchBox.setFocused(true);
				return this.gatherSearchBox.keyPressed(event);
			}
		}
		return super.keyPressed(event);
	}

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		// Every list scrolls. Before this the rows simply stopped when they ran out of room,
		// so a long roster or a big material list was unreachable past the first screenful.
		ScrollList active = switch (this.tab) {
			case TAB_GATHER -> this.materialScroll;
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
		// The painted hit-tests below receive every mouse button, unlike real widgets
		// (which filter through isValidClickButton) — so a right-click on a roster row
		// armed a kick and a right-click on a cell claimed it. Anything but the left
		// button goes straight to the widgets.
		if (event.button() != 0) {
			return super.mouseClicked(event, doubleClick);
		}
		// Tabs are painted, not widgets, so they are hit-tested here — before the default
		// handling, which would otherwise swallow the click on the panel background.
		if (this.tab == TAB_MEMBERS && this.minecraft != null
			&& ClanSessionManager.isHost(this.minecraft)) {
			ClanSession sess = ClanSessionManager.session();
			int idx = this.memberScroll.rowAt(event.x(), event.y(), 20);
			if (sess != null && idx >= 0 && idx < sess.members.size()) {
				ClanSession.ClanMember target = sess.members.get(idx);
				String me = ClanSessionManager.localUuid(this.minecraft);
				if (target.uuid != null && !target.uuid.equalsIgnoreCase(me)) {
					// Two clicks: the first arms, the second kicks. A roster row is too
					// easy to hit for a one-click removal.
					if (target.uuid.equalsIgnoreCase(this.kickArmUuid)) {
						this.kickArmUuid = null;
						this.status = Component.translatable("screen.chestmemory.clan.working").getString();
						ClanSessionManager.kickAsync(
							this.minecraft, target.uuid,
							target.name == null ? "?" : target.name,
							this::rebuildWidgets
						);
					} else {
						this.kickArmUuid = target.uuid;
						this.kickArmTicks = ARM_TIMEOUT_TICKS;
						this.status = Component.translatable(
							"screen.chestmemory.clan.kick_confirm",
							target.name == null || target.name.isBlank() ? "?" : target.name
						).getString();
					}
					return true;
				}
			}
		}
		if (this.tab == TAB_GATHER && this.minecraft != null) {
			int idx = materialAt(event.x(), event.y());
			if (idx >= 0 && idx < this.materialIds.size()) {
				// The screen stays open: taking one item used to close it, so reserving three
				// meant opening the panel three times. Solo, the same click aims the gather.
				String clicked = this.materialIds.get(idx);
				if (this.externalIds.contains(clicked)) {
					externalHighlight(clicked);
					return true;
				}
				GatherMode mode = gatherMode();
				if (mode == GatherMode.CLAN) {
					// Shift peeks without promising: glow the chests, claim nothing.
					if (event.hasShiftDown()) {
						externalHighlight(clicked);
						return true;
					}
					claimFromList(clicked);
				} else if (mode == GatherMode.SOLO) {
					soloClickMaterial(clicked);
				}
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
				this.kickArmUuid = null;
				this.releaseArmed = false;
				this.closeArmed = false;
				// This reset used to skip sayCodeArmed, so the arm survived a trip through
				// the other tabs and the return click posted the code to public chat.
				this.sayCodeArmed = false;
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
		// broke the moment the list could scroll: a per-frame list of the visible rows only,
		// indexed from zero, mapped every click on a scrolled list to the wrong gather.
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
		int[] vis = visibleTabs();
		Component[] labels = new Component[vis.length];
		for (int i = 0; i < vis.length; i++) {
			labels[i] = Component.translatable(TAB_KEYS[vis[i]]);
		}
		int idx = ChestGuiStyle.tabIndexAt(
			this.font, labels, this.tabsLeft, this.tabsWidth, this.tabsY, mx, my
		);
		return idx < 0 ? -1 : vis[idx];
	}

	private void saveHubQuiet() {
		if (this.hubBox != null) {
			ModSettings.get().setClanHubUrl(this.hubBox.getValue());
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
		} else if (gatherMode() == GatherMode.SOLO) {
			String list = com.chestmemory.client.litematica.BuildGatherSession.listName();
			if (list == null || list.isBlank()) {
				list = LitematicaAccess.activeListName();
			}
			subtitle = Component.translatable(
				"screen.chestmemory.clan.header_solo",
				list == null || list.isBlank()
					? Component.translatable("screen.chestmemory.clan.unnamed_build").getString()
					: list
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
			int[] vis = visibleTabs();
			Component[] labels = new Component[vis.length];
			int selected = 0;
			int hovered = -1;
			for (int i = 0; i < vis.length; i++) {
				labels[i] = Component.translatable(TAB_KEYS[vis[i]]);
				if (vis[i] == this.tab) {
					selected = i;
				}
				if (vis[i] == this.hoveredTab) {
					hovered = i;
				}
			}
			ChestGuiStyle.drawTabs(
				graphics, this.font, labels,
				this.tabsLeft, this.tabsY, this.tabsWidth, selected, hovered
			);
		}
		ClanSession s = ClanSessionManager.session();
		switch (this.tab) {
			case TAB_INFO -> drawInfoBody(graphics, s, left, centerX, contentW);
			case TAB_MEMBERS -> {
				if (s != null) {
					drawMembers(graphics, s, left, contentW);
				}
			}
			case TAB_FEED -> drawFeed(graphics, left, contentW);
			case TAB_LIST -> drawGatherList(graphics, s, left, contentW);
			default -> drawGatherBody(graphics, s, left, centerX, contentW);
		}


		// Status line last, so a fresh message always wins over the standing hints.
		String line;
		if (!this.status.isBlank()) {
			line = this.status;
		} else if (ClanSessionManager.isInSession()) {
			line = "";
		} else if (gatherMode() == GatherMode.SOLO) {
			line = Component.translatable("screen.chestmemory.clan.status_solo").getString();
		} else if (!ClanSessionManager.isConfigured()) {
			line = Component.translatable("screen.chestmemory.clan.status_need_hub").getString();
		} else {
			line = Component.translatable("screen.chestmemory.clan.status_ready").getString();
		}
		if (!line.isEmpty()) {
			// Below the panel, like the chest screen's footer. Inside it the sentence fought
			// the buttons for the same rows; out here it has the whole width and cannot
			// collide with anything. The shared helper pulls it back inside the panel on
			// windows too short for a footer — at the minimum scaled height the panel ends
			// at the screen edge, and this line is the only feedback most actions give.
			ChestGuiStyle.drawStatusLine(
				graphics,
				this.font,
				line,
				centerX,
				this.panelTop + this.panelH + 6,
				this.panelW,
				this.height,
				ChestGuiStyle.TEXT_MUTED
			);
		}
	}

	private net.minecraft.world.item.ItemStack icon(String itemId) {
		return this.iconCache.computeIfAbsent(
			itemId, com.chestmemory.client.data.ItemStackKeys::toStack
		);
	}

	/**
	 * Slot pitch, shared with the main screen's item grid so the two look alike. The gap
	 * belongs in the pitch: this grid used to pack bare 18px cells edge to edge, so its
	 * slot sprite borders collapsed into each other while the chest panel's stayed apart.
	 */
	private static final int CELL = ChestGuiStyle.GRID_SLOT + ChestGuiStyle.GRID_GAP;

	/** One cell of the material grid — the id, the number to show, and how to paint it. */
	private record MatCell(
		String itemId,
		int count,
		int tint,
		int countColour,
		@org.jspecify.annotations.Nullable String badge,
		int badgeColour,
		int border
	) {
	}

	/**
	 * The working tab body. One place answers "what do I bring, and how is it going" for
	 * both kinds of gather: the clan session when in one, the player's own schematic when
	 * not. The separate Materials tab is gone — this grid is the tab now.
	 */
	private void drawGatherBody(
		GuiGraphicsExtractor graphics,
		ClanSession s,
		int left,
		int centerX,
		int contentW
	) {
		// No grid until a mode draws one; stale geometry must not eat clicks.
		this.materialGridPerRow = 0;
		this.materialIds = java.util.List.of();
		GatherMode mode = gatherMode();
		if (mode == GatherMode.CLAN && s != null) {
			if (this.hostSettings) {
				drawHostSettings(graphics, s, left, centerX, contentW);
				return;
			}
			drawClanGather(graphics, s, left, centerX, contentW);
		} else if (mode == GatherMode.SOLO) {
			drawSoloGather(graphics, left, centerX, contentW);
		} else {
			drawEmptyGather(graphics, centerX, contentW);
		}
	}

	/** Clan mode: the claimable grid, wall to wall — identity and facts live on Инфо. */
	private void drawClanGather(
		GuiGraphicsExtractor graphics,
		ClanSession s,
		int left,
		int centerX,
		int contentW
	) {
		int y = this.tabsY + (this.searchOnGatherTab ? 44 : 22);

		// Ordered the way a gatherer scans: ready to hand in first (chests cover the
		// whole remainder), then partial stock, then nothing anywhere, done last —
		// biggest remainder first inside each band.
		List<java.util.Map.Entry<String, ClanSession.ClanMaterial>> rows =
			new java.util.ArrayList<>(s.materials.entrySet());
		rows.sort((a, b) -> {
			int band = Integer.compare(clanBand(s, a.getKey()), clanBand(s, b.getKey()));
			if (band != 0) {
				return band;
			}
			return Integer.compare(s.remaining(b.getKey()), s.remaining(a.getKey()));
		});

		// Search filters the gather; general-memory matches are appended dimmed below.
		String q = this.gatherQuery.trim().toLowerCase(java.util.Locale.ROOT);
		if (!q.isEmpty()) {
			rows.removeIf(e -> !matchesQuery(e.getKey(), q));
		}

		String me = this.minecraft != null ? ClanSessionManager.localUuid(this.minecraft) : "";
		List<MatCell> cells = new java.util.ArrayList<>(rows.size());
		for (var e : rows) {
			ClanSession.ClanMaterial m = e.getValue();
			int remaining = s.remaining(e.getKey());
			boolean done = remaining <= 0;
			boolean mine = m.claimedBy != null && m.claimedBy.equals(me);
			boolean taken = m.claimedBy != null && !m.claimedBy.isBlank() && !mine;
			// Traffic-light stock states — green means GO: the chests can close this item
			// right now. Yellow is partial, red is nothing anywhere, and a finished item
			// dims out with a green check instead of glowing. Claims ride the rim. The
			// colours are the shared palette: this grid and the chest panel had drifted
			// apart (yellow here, orange there) for the very same state.
			int stock = done ? 0 : chestStock(e.getKey());
			int tint = done ? ChestGuiStyle.STOCK_DONE
				: stock >= remaining ? ChestGuiStyle.STOCK_READY
				: stock > 0 ? ChestGuiStyle.STOCK_PARTIAL
				: ChestGuiStyle.STOCK_NONE;
			int countColour = done ? 0xFFC8C8C8
				: stock >= remaining ? 0xFF7FE08A
				: stock > 0 ? 0xFFFFE066
				: 0xFFFF9090;
			int border = mine ? 0xFFFFD56A : taken ? 0xFFB48CB4 : 0;
			String badge = done ? "✓" : null;
			int badgeColour = done ? 0xFF7FE08A : 0;
			if (!done && (mine || taken)) {
				// Claimer's initial, same badge the chest panel uses — a glance tells who
				// is on what without opening the roster.
				badge = m.claimedName == null || m.claimedName.isBlank()
					? "?" : m.claimedName.substring(0, 1).toUpperCase(java.util.Locale.ROOT);
				badgeColour = mine ? ChestGuiStyle.CLAIM_MINE : ChestGuiStyle.CLAIM_OTHER;
			}
			cells.add(new MatCell(
				e.getKey(), done ? 0 : remaining, tint, countColour, badge, badgeColour, border
			));
		}
		appendExternalMatches(cells, q, s.materials.keySet());
		int hoverIdx = drawMaterialGrid(graphics, cells, left, y, contentW);

		// The hovered cell explains itself in a vanilla tooltip at the cursor — the same
		// reading gesture as the main panel. The bottom line keeps the standing summary.
		if (hoverIdx >= 0 && hoverIdx < rows.size()) {
			var e = rows.get(hoverIdx);
			pushCellTooltip(graphics, clanCellTooltip(s, e.getKey(), e.getValue(), me));
		} else if (hoverIdx >= rows.size() && hoverIdx - rows.size() < this.externalRows.size()) {
			pushCellTooltip(graphics, externalCellTooltip(this.externalRows.get(hoverIdx - rows.size())));
		}

		int online = 0;
		for (ClanSession.ClanMember m : s.members) {
			if (!s.isMemberAway(m)) {
				online++;
			}
		}
		int free = 0;
		int doneItems = 0;
		for (var e : s.materials.entrySet()) {
			ClanSession.ClanMaterial m = e.getValue();
			if (s.remaining(e.getKey()) <= 0) {
				doneItems++;
			} else if (m.claimedBy == null || m.claimedBy.isBlank()) {
				free++;
			}
		}
		ChestGuiStyle.drawCentered(
			graphics, this.font,
			ChestGuiStyle.ellipsize(
				this.font,
				Component.translatable(
					"screen.chestmemory.clan.legend",
					s.materials.size(), doneItems, free, online, s.members.size()
				).getString(),
				contentW
			),
			centerX, this.gridBottom + 4, ChestGuiStyle.TEXT_MUTED
		);
	}

	/**
	 * Solo mode: the player's own schematic through the same grid. Progress counts what
	 * the backpack and the staging chests already cover; a click aims the gather (routes
	 * and glow) at that material, a second click on the target stops it.
	 */
	private void drawSoloGather(GuiGraphicsExtractor graphics, int left, int centerX, int contentW) {
		int y = this.tabsY + (this.searchOnGatherTab ? 44 : 22);
		// Filter-independent list: the Ё-panel filter is that panel's state, and hiding
		// rows here because of it would look like lost materials.
		List<com.chestmemory.client.data.ItemSummary> rows = this.minecraft == null
			? java.util.List.of()
			: com.chestmemory.client.litematica.BuildGatherSession.buildPanelList(
				this.minecraft, "",
				com.chestmemory.client.data.ListScope.WORLD_TOTAL,
				com.chestmemory.client.data.DimensionChoice.ALL,
				0,
				com.chestmemory.client.litematica.BuildFilter.ALL
			);
		int doneItems = 0;
		int stocked = 0;
		for (var r : rows) {
			int missing = Math.max(0, r.neededForBuild());
			if (missing <= 0) {
				doneItems++;
			} else if (r.totalCount() > 0) {
				stocked++;
			}
		}
		String focus = com.chestmemory.client.litematica.BuildGatherSession.currentItemId();

		// Search filters the schematic list; memory matches are appended dimmed below.
		java.util.Set<String> gatherIds = new java.util.HashSet<>();
		for (var r : rows) {
			gatherIds.add(r.itemId());
		}
		String q = this.gatherQuery.trim().toLowerCase(java.util.Locale.ROOT);
		if (!q.isEmpty()) {
			rows.removeIf(r -> !matchesQuery(r.itemId(), q));
		}

		List<MatCell> cells = new java.util.ArrayList<>(rows.size());
		for (var r : rows) {
			int missing = Math.max(0, r.neededForBuild());
			boolean done = missing <= 0;
			boolean isFocus = r.itemId().equals(focus);
			// Traffic light, same as the clan grid: green GO, yellow partial, red none,
			// done dims out with a check. The gold ring marks the current target.
			int stock = r.totalCount();
			int tint = done ? ChestGuiStyle.STOCK_DONE
				: stock >= missing ? ChestGuiStyle.STOCK_READY
				: stock > 0 ? ChestGuiStyle.STOCK_PARTIAL
				: ChestGuiStyle.STOCK_NONE;
			int countColour = done ? 0xFFC8C8C8
				: stock >= missing ? 0xFF7FE08A
				: stock > 0 ? 0xFFFFE066
				: 0xFFFF9090;
			int border = isFocus ? 0xFFFFD56A : 0;
			cells.add(new MatCell(
				r.itemId(), done ? 0 : missing, tint, countColour,
				done ? "✓" : null, done ? 0xFF7FE08A : 0, border
			));
		}
		appendExternalMatches(cells, q, gatherIds);
		int hoverIdx = drawMaterialGrid(graphics, cells, left, y, contentW);

		if (hoverIdx >= 0 && hoverIdx < rows.size()) {
			pushCellTooltip(graphics, soloCellTooltip(rows.get(hoverIdx), focus));
		} else if (hoverIdx >= rows.size() && hoverIdx - rows.size() < this.externalRows.size()) {
			pushCellTooltip(graphics, externalCellTooltip(this.externalRows.get(hoverIdx - rows.size())));
		}
		ChestGuiStyle.drawCentered(
			graphics, this.font,
			ChestGuiStyle.ellipsize(
				this.font,
				Component.translatable(
					"screen.chestmemory.clan.solo_legend",
					rows.size(), doneItems, stocked
				).getString(),
				contentW
			),
			centerX, this.gridBottom + 4, ChestGuiStyle.TEXT_MUTED
		);
		ChestGuiStyle.drawCentered(
			graphics, this.font,
			ChestGuiStyle.ellipsize(
				this.font,
				Component.translatable("screen.chestmemory.clan.solo_hint_share").getString(),
				contentW
			),
			centerX, this.gridBottom + 14, ChestGuiStyle.TEXT_MUTED
		);
	}

	/** Neither a session nor a schematic: say so, and name both ways to get a gather. */
	private void drawEmptyGather(GuiGraphicsExtractor graphics, int centerX, int contentW) {
		int top = this.tabsY + 22;
		int bottom = this.panelTop + this.panelH - 56;
		int mid = top + Math.max(0, (bottom - top) / 2 - 18);
		ChestGuiStyle.drawCentered(
			graphics, this.font,
			Component.translatable("screen.chestmemory.clan.empty_title").getString(),
			centerX, mid, ChestGuiStyle.TEXT_TITLE
		);
		ChestGuiStyle.drawCentered(
			graphics, this.font,
			ChestGuiStyle.ellipsize(
				this.font,
				Component.translatable("screen.chestmemory.clan.empty_solo_hint").getString(),
				contentW
			),
			centerX, mid + 14, ChestGuiStyle.TEXT_MUTED
		);
		ChestGuiStyle.drawCentered(
			graphics, this.font,
			ChestGuiStyle.ellipsize(
				this.font,
				Component.translatable("screen.chestmemory.clan.empty_clan_hint").getString(),
				contentW
			),
			centerX, mid + 25, ChestGuiStyle.TEXT_MUTED
		);
	}

	/** 0 ready · 1 partial · 2 none · 3 done — the scan order of the clan grid. */
	private int clanBand(ClanSession s, String itemId) {
		int remaining = s.remaining(itemId);
		if (remaining <= 0) {
			return 3;
		}
		int stock = chestStock(itemId);
		if (stock >= remaining) {
			return 0;
		}
		return stock > 0 ? 1 : 2;
	}

	/** Live chest stock, briefly cached — the grid asks for it per cell per frame. */
	private int chestStock(String itemId) {
		long now = System.currentTimeMillis();
		if (now - this.stockCacheAt > 500L) {
			this.stockCache.clear();
			this.stockCacheAt = now;
		}
		return this.stockCache.computeIfAbsent(
			itemId, com.chestmemory.client.litematica.BuildGatherSession::countInChestsLive
		);
	}

	/** Push a vanilla tooltip for the hovered cell — the panel's own reading gesture. */
	private void pushCellTooltip(GuiGraphicsExtractor graphics, List<Component> lines) {
		graphics.setTooltipForNextFrame(
			this.font, lines, java.util.Optional.empty(), this.hoverX, this.hoverY
		);
	}

	/** Name styled exactly as vanilla would (rarity colours, renamed italics), then a gap. */
	private List<Component> tooltipHead(String itemId) {
		List<Component> lines = new java.util.ArrayList<>();
		if (com.chestmemory.client.data.ItemStackKeys.isKnown(itemId)) {
			lines.add(icon(itemId).getStyledHoverName());
		} else {
			lines.add(Component.literal(itemId).withStyle(net.minecraft.ChatFormatting.WHITE));
		}
		lines.add(Component.empty());
		return lines;
	}

	/** The clan cell: progress, live stock, who holds it, and what a click will do. */
	private List<Component> clanCellTooltip(
		ClanSession s,
		String itemId,
		ClanSession.ClanMaterial m,
		String me
	) {
		List<Component> lines = tooltipHead(itemId);
		int remaining = s.remaining(itemId);
		lines.add(Component.translatable(
			"screen.chestmemory.tooltip.gather_delivered",
			Math.max(0, m.delivered), Math.max(0, m.need)
		).withStyle(net.minecraft.ChatFormatting.GRAY));
		if (remaining > 0) {
			lines.add(Component.translatable(
				"screen.chestmemory.tooltip.gather_left", remaining
			).withStyle(net.minecraft.ChatFormatting.GOLD));
		} else {
			lines.add(Component.translatable("screen.chestmemory.clan.mat_done")
				.withStyle(net.minecraft.ChatFormatting.GREEN));
		}
		lines.add(Component.literal(stockLine(itemId))
			.withStyle(net.minecraft.ChatFormatting.GRAY));
		addStockDetail(lines, itemId, chestStock(itemId));
		lines.add(Component.empty());
		boolean mine = m.claimedBy != null && m.claimedBy.equals(me);
		boolean taken = m.claimedBy != null && !m.claimedBy.isBlank() && !mine;
		boolean ready = remaining > 0 && chestStock(itemId) >= remaining;
		if (mine) {
			lines.add(Component.translatable("screen.chestmemory.clan.mat_yours_hint")
				.withStyle(net.minecraft.ChatFormatting.GOLD));
		} else if (taken) {
			lines.add(Component.translatable(
				"screen.chestmemory.clan.mat_taken_by",
				m.claimedName != null ? m.claimedName : "?"
			).withStyle(net.minecraft.ChatFormatting.LIGHT_PURPLE));
		} else if (remaining > 0) {
			if (ready) {
				lines.add(Component.translatable("screen.chestmemory.clan.mat_ready_hint")
					.withStyle(net.minecraft.ChatFormatting.GREEN));
			}
			lines.add(Component.translatable("screen.chestmemory.clan.mat_take_hint")
				.withStyle(net.minecraft.ChatFormatting.GRAY));
		}
		lines.add(Component.translatable("screen.chestmemory.tooltip.gather_shift")
			.withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
		return lines;
	}

	/** The solo cell: schematic progress, stock with the backpack, and the click action. */
	private List<Component> soloCellTooltip(
		com.chestmemory.client.data.ItemSummary r,
		@org.jspecify.annotations.Nullable String focus
	) {
		List<Component> lines = tooltipHead(r.itemId());
		int missing = Math.max(0, r.neededForBuild());
		int total = Math.max(0, r.schematicTotal());
		lines.add(Component.translatable(
			"screen.chestmemory.tooltip.gather_collected",
			Math.max(0, total - missing), total
		).withStyle(net.minecraft.ChatFormatting.GRAY));
		if (missing > 0) {
			lines.add(Component.translatable(
				"screen.chestmemory.tooltip.gather_left", missing
			).withStyle(net.minecraft.ChatFormatting.GOLD));
		}
		String dist = r.hasDistance()
			? Component.translatable(
				"screen.chestmemory.clan.dist_m", (int) Math.round(r.nearestDistance())
			).getString()
			: "—";
		lines.add(Component.translatable(
			"screen.chestmemory.clan.hover_stock_solo",
			r.totalCount(), dist, Math.max(0, r.inPlayer())
		).withStyle(net.minecraft.ChatFormatting.GRAY));
		addStockDetail(lines, r.itemId(), r.totalCount());
		lines.add(Component.empty());
		if (missing <= 0) {
			lines.add(Component.translatable("screen.chestmemory.clan.solo_hover_done")
				.withStyle(net.minecraft.ChatFormatting.GREEN));
		} else if (r.itemId().equals(focus)) {
			lines.add(Component.translatable("screen.chestmemory.clan.solo_hover_focus")
				.withStyle(net.minecraft.ChatFormatting.GOLD));
		} else if (r.totalCount() >= missing) {
			lines.add(Component.translatable("screen.chestmemory.clan.solo_hover_ready")
				.withStyle(net.minecraft.ChatFormatting.GREEN));
		} else if (r.totalCount() > 0) {
			lines.add(Component.translatable(
				"screen.chestmemory.clan.solo_hover_route", r.totalCount()
			).withStyle(net.minecraft.ChatFormatting.GRAY));
		} else {
			lines.add(Component.translatable("screen.chestmemory.clan.solo_hover_craft")
				.withStyle(net.minecraft.ChatFormatting.GRAY));
		}
		return lines;
	}

	/**
	 * Stock detail: full stacks, the shulker-box equivalent (27 stacks to a box — 1728
	 * of a 64-stack item IS one shulker), and how much already sits inside shulkers.
	 */
	private void addStockDetail(List<Component> lines, String itemId, int stock) {
		int per = Math.max(1, icon(itemId).getMaxStackSize());
		if (stock >= per) {
			int stacks = stock / per;
			int rem = stock % per;
			lines.add((rem > 0
				? Component.translatable("screen.chestmemory.tooltip.gather_stacks", stacks, rem)
				: Component.translatable("screen.chestmemory.tooltip.gather_stacks_even", stacks))
				.withStyle(net.minecraft.ChatFormatting.GRAY));
		}
		int boxCap = per * 27;
		if (stock >= boxCap) {
			lines.add(Component.translatable(
				"screen.chestmemory.tooltip.gather_boxes", formatBoxes(stock, boxCap)
			).withStyle(net.minecraft.ChatFormatting.GRAY));
		}
		int inShulkers = com.chestmemory.client.data.WorldBreakdown.shulkerCount(
			ChestMemoryStorage.get().liveContainersSnapshot(), itemId
		);
		if (inShulkers > 0) {
			lines.add(Component.translatable(
				"screen.chestmemory.tooltip.gather_shulkers", inShulkers
			).withStyle(net.minecraft.ChatFormatting.GRAY));
		}
		// Ender holdings are no longer "chest stock" (no metres to something that is
		// with you) — say where they are instead, in the vanilla ender line.
		int inEnder = com.chestmemory.client.data.WorldBreakdown.enderCount(
			ChestMemoryStorage.get().liveContainersSnapshot(), itemId
		);
		if (inEnder > 0) {
			lines.add(Component.translatable(
				"screen.chestmemory.tooltip.ender", inEnder
			).withStyle(net.minecraft.ChatFormatting.GRAY));
		}
	}

	/** «1», «1.5», «12» — shulker boxes, one decimal while it still matters. */
	private static String formatBoxes(int stock, int boxCap) {
		double v = stock / (double) boxCap;
		if (v >= 10) {
			return String.valueOf(Math.round(v));
		}
		String s = String.format(java.util.Locale.ROOT, "%.1f", v);
		return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
	}

	/** A found-by-search memory item: where it lies and that a click only glows chests. */
	private List<Component> externalCellTooltip(com.chestmemory.client.data.ItemSummary sum) {
		List<Component> lines = tooltipHead(sum.itemId());
		lines.add(Component.translatable(
			"screen.chestmemory.clan.external_line",
			sum.totalCount(), sum.containerCount()
		).withStyle(net.minecraft.ChatFormatting.GRAY));
		lines.add(Component.literal(stockLine(sum.itemId()))
			.withStyle(net.minecraft.ChatFormatting.GRAY));
		lines.add(Component.empty());
		lines.add(Component.translatable("screen.chestmemory.clan.external_hint")
			.withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
		return lines;
	}

	/** «В сундуках: N · этот мир: M · ближайший: 35м» — the numbers a player gathers by. */
	private String stockLine(String itemId) {
		int total = chestStock(itemId);
		if (total <= 0) {
			return Component.translatable("screen.chestmemory.clan.hover_stock_none").getString();
		}
		String dim = this.minecraft != null && this.minecraft.level != null
			? ChestMemoryStorage.dimensionId(this.minecraft.level)
			: null;
		int here = com.chestmemory.client.litematica.BuildGatherSession.countInChestsLive(
			itemId, com.chestmemory.client.data.DimensionChoice.CURRENT, dim
		);
		double dist = com.chestmemory.client.litematica.BuildGatherSession
			.nearestChestDistance(this.minecraft, itemId);
		String distLabel = dist >= 0
			? Component.translatable("screen.chestmemory.clan.dist_m", (int) Math.round(dist)).getString()
			: "—";
		return Component.translatable(
			"screen.chestmemory.clan.hover_stock", total, here, distLabel
		).getString();
	}

	/** Info tab: identity, progress and the facts — everything the grid tab gave up. */
	private void drawInfoBody(
		GuiGraphicsExtractor graphics,
		ClanSession s,
		int left,
		int centerX,
		int contentW
	) {
		GatherMode mode = gatherMode();
		if (mode == GatherMode.CLAN && s != null) {
			drawClanInfo(graphics, s, left, centerX, contentW);
		} else if (mode == GatherMode.SOLO) {
			drawSoloInfo(graphics, left, centerX, contentW);
		}
	}

	/** One fact line; the cursor advances itself, so two rows can never share a y. */
	private int infoRow(
		GuiGraphicsExtractor graphics,
		int left,
		int contentW,
		int y,
		String label,
		String value,
		int colour
	) {
		graphics.text(this.font, label, left + 2, y, ChestGuiStyle.TEXT_MUTED, false);
		String v = ChestGuiStyle.ellipsize(this.font, value, contentW - this.font.width(label) - 14);
		graphics.text(this.font, v, left + contentW - 2 - this.font.width(v), y, colour, false);
		return y + 12;
	}

	private void drawClanInfo(
		GuiGraphicsExtractor graphics,
		ClanSession s,
		int left,
		int centerX,
		int contentW
	) {
		int y = this.tabsY + 22;
		String schema = s.schemaName == null || s.schemaName.isBlank()
			? Component.translatable("screen.chestmemory.clan.unnamed_build").getString()
			: s.schemaName;
		drawIdentityPlate(
			graphics, left, y, contentW,
			Component.translatable("screen.chestmemory.clan.mode_clan").getString(),
			ChestGuiStyle.LATCH, schema
		);
		y += 20;
		int need = s.totalNeed();
		int delivered = s.totalDelivered();
		float f = need > 0 ? delivered / (float) need : 0F;
		drawProgressWithLabel(
			graphics, left, y, contentW, f,
			Component.translatable(
				"screen.chestmemory.clan.progress",
				delivered, need, need > 0 ? (int) (f * 100) : 0
			).getString()
		);
		y += 20;

		long now = System.currentTimeMillis();
		boolean iAmHost = this.minecraft != null && ClanSessionManager.isHost(this.minecraft);
		String host = (s.hostName == null || s.hostName.isBlank() ? "?" : s.hostName)
			+ (iAmHost ? " " + Component.translatable("screen.chestmemory.clan.you_marker").getString() : "");
		int online = 0;
		for (ClanSession.ClanMember m : s.members) {
			if (!s.isMemberAway(m)) {
				online++;
			}
		}
		int free = 0;
		int doneItems = 0;
		int claimed = 0;
		for (var e : s.materials.entrySet()) {
			ClanSession.ClanMaterial m = e.getValue();
			if (m.claimedBy != null && !m.claimedBy.isBlank()) {
				claimed++;
			}
			if (s.remaining(e.getKey()) <= 0) {
				doneItems++;
			} else if (m.claimedBy == null || m.claimedBy.isBlank()) {
				free++;
			}
		}
		String warehouse = s.stagingKeys == null || s.stagingKeys.isEmpty()
			? Component.translatable("screen.chestmemory.clan.detail_no_warehouse").getString()
			: Component.translatable("screen.chestmemory.clan.detail_chests", s.stagingKeys.size()).getString();

		y = infoRow(graphics, left, contentW, y, Component.translatable("screen.chestmemory.clan.info_code").getString(), s.code, ChestGuiStyle.TEXT_TITLE);
		y = infoRow(graphics, left, contentW, y, Component.translatable("screen.chestmemory.clan.info_host").getString(), host, ChestGuiStyle.TEXT_TITLE);
		y = infoRow(
			graphics, left, contentW, y, Component.translatable("screen.chestmemory.clan.info_created").getString(),
			s.createdAt > 0
				? Component.translatable("screen.chestmemory.clan.ago", ageLabel(now - s.createdAt)).getString()
				: "—",
			ChestGuiStyle.TEXT_TITLE
		);
		y = infoRow(
			graphics, left, contentW, y, Component.translatable("screen.chestmemory.clan.info_updated").getString(),
			s.updatedAt > 0
				? Component.translatable("screen.chestmemory.clan.ago", ageLabel(now - s.updatedAt)).getString()
				: "—",
			ChestGuiStyle.TEXT_TITLE
		);
		y = infoRow(
			graphics, left, contentW, y, Component.translatable("screen.chestmemory.clan.info_items").getString(),
			Component.translatable(
				"screen.chestmemory.clan.info_items_value",
				s.materials.size(), doneItems, free
			).getString(),
			ChestGuiStyle.TEXT_TITLE
		);
		y = infoRow(
			graphics, left, contentW, y, Component.translatable("screen.chestmemory.clan.info_members").getString(),
			Component.translatable(
				"screen.chestmemory.clan.info_members_value",
				online, s.members.size(), claimed
			).getString(),
			online > 0 ? ChestGuiStyle.TEXT_TITLE : ChestGuiStyle.TEXT_MUTED
		);
		y = infoRow(
			graphics, left, contentW, y, Component.translatable("screen.chestmemory.clan.info_warehouse").getString(), warehouse,
			s.stagingKeys == null || s.stagingKeys.isEmpty() ? 0xFFA04030 : ChestGuiStyle.TEXT_TITLE
		);

		// The last delivery, straight from the feed — who is actually hauling right now.
		String lastDelivery = null;
		long lastAt = 0;
		for (com.chestmemory.client.clan.ClanEventLog.Entry e
			: com.chestmemory.client.clan.ClanEventLog.all()) {
			if (e.kind() == com.chestmemory.client.clan.ClanEventLog.Kind.DELIVER) {
				lastDelivery = e.text().getString();
				lastAt = e.at();
				break;
			}
		}
		y = infoRow(
			graphics, left, contentW, y, Component.translatable("screen.chestmemory.clan.info_last_delivery").getString(),
			lastDelivery == null
				? Component.translatable("screen.chestmemory.clan.info_none").getString()
				: lastDelivery + " · " + Component.translatable(
					"screen.chestmemory.clan.ago", ageLabel(now - lastAt)
				).getString(),
			lastDelivery == null ? ChestGuiStyle.TEXT_MUTED : ChestGuiStyle.TEXT_TITLE
		);

		ChestGuiStyle.drawCentered(
			graphics, this.font,
			ChestGuiStyle.ellipsize(
				this.font,
				Component.translatable("screen.chestmemory.clan.info_hint").getString(),
				contentW
			),
			centerX, y + 6, ChestGuiStyle.TEXT_MUTED
		);
	}

	private void drawSoloInfo(GuiGraphicsExtractor graphics, int left, int centerX, int contentW) {
		int y = this.tabsY + 22;
		String listLabel = com.chestmemory.client.litematica.BuildGatherSession.listName();
		if (listLabel == null || listLabel.isBlank()) {
			listLabel = LitematicaAccess.activeListName();
		}
		if (listLabel == null || listLabel.isBlank()) {
			listLabel = Component.translatable("screen.chestmemory.clan.unnamed_build").getString();
		}
		drawIdentityPlate(
			graphics, left, y, contentW,
			Component.translatable("screen.chestmemory.clan.mode_solo").getString(),
			ChestGuiStyle.LAMP_ONLINE, listLabel
		);
		y += 20;

		List<com.chestmemory.client.data.ItemSummary> rows = this.minecraft == null
			? java.util.List.of()
			: com.chestmemory.client.litematica.BuildGatherSession.buildPanelList(
				this.minecraft, "",
				com.chestmemory.client.data.ListScope.WORLD_TOTAL,
				com.chestmemory.client.data.DimensionChoice.ALL,
				0,
				com.chestmemory.client.litematica.BuildFilter.ALL
			);
		long total = 0;
		long collected = 0;
		int doneItems = 0;
		int stocked = 0;
		for (var r : rows) {
			int t = Math.max(0, r.schematicTotal());
			int missing = Math.max(0, r.neededForBuild());
			total += t;
			collected += Math.max(0, t - missing);
			if (missing <= 0) {
				doneItems++;
			} else if (r.totalCount() > 0) {
				stocked++;
			}
		}
		float f = total > 0 ? collected / (float) total : 0F;
		drawProgressWithLabel(
			graphics, left, y, contentW, f,
			Component.translatable(
				"screen.chestmemory.clan.solo_progress",
				collected, total, total > 0 ? (int) (f * 100) : 0
			).getString()
		);
		y += 20;

		boolean soloActive = com.chestmemory.client.litematica.BuildGatherSession.isActive();
		String focus = com.chestmemory.client.litematica.BuildGatherSession.currentItemId();
		String phaseLabel = !soloActive
			? Component.translatable("screen.chestmemory.clan.solo_idle_short").getString()
			: Component.translatable(
				com.chestmemory.client.litematica.BuildGatherSession.phase()
					== com.chestmemory.client.litematica.BuildGatherSession.GatherPhase.CHESTS
					? "hud.chestmemory.phase_chests"
					: "hud.chestmemory.phase_craft"
			).getString();

		y = infoRow(graphics, left, contentW, y, Component.translatable("screen.chestmemory.clan.info_schematic").getString(), listLabel, ChestGuiStyle.TEXT_TITLE);
		y = infoRow(
			graphics, left, contentW, y, Component.translatable("screen.chestmemory.clan.info_items").getString(),
			Component.translatable(
				"screen.chestmemory.clan.info_items_value_solo",
				rows.size(), doneItems, Math.max(0, rows.size() - doneItems)
			).getString(),
			ChestGuiStyle.TEXT_TITLE
		);
		y = infoRow(graphics, left, contentW, y, Component.translatable("screen.chestmemory.clan.info_stock").getString(), String.valueOf(stocked), ChestGuiStyle.TEXT_TITLE);
		y = infoRow(graphics, left, contentW, y, Component.translatable("screen.chestmemory.clan.info_phase").getString(), phaseLabel, ChestGuiStyle.TEXT_TITLE);
		y = infoRow(
			graphics, left, contentW, y, Component.translatable("screen.chestmemory.clan.info_target").getString(),
			focus != null
				? ChestMemoryStorage.itemDisplayName(focus)
				: Component.translatable("screen.chestmemory.clan.info_none").getString(),
			focus != null ? ChestGuiStyle.TEXT_GOLD : ChestGuiStyle.TEXT_MUTED
		);
		y = infoRow(
			graphics, left, contentW, y, Component.translatable("screen.chestmemory.clan.info_warehouse").getString(),
			Component.translatable(
				"screen.chestmemory.clan.detail_chests",
				ChestMemoryStorage.get().stagingCount()
			).getString(),
			ChestGuiStyle.TEXT_TITLE
		);

		ChestGuiStyle.drawCentered(
			graphics, this.font,
			ChestGuiStyle.ellipsize(
				this.font,
				Component.translatable("screen.chestmemory.clan.info_hint").getString(),
				contentW
			),
			centerX, y + 6, ChestGuiStyle.TEXT_MUTED
		);
	}

	/** Settings body: the plate for context, captions for the rows init() built. */
	private void drawHostSettings(
		GuiGraphicsExtractor graphics,
		ClanSession s,
		int left,
		int centerX,
		int contentW
	) {
		int y = this.tabsY + 22;
		String schema = s.schemaName == null || s.schemaName.isBlank()
			? Component.translatable("screen.chestmemory.clan.unnamed_build").getString()
			: s.schemaName;
		drawIdentityPlate(
			graphics, left, y - 2, contentW,
			Component.translatable("screen.chestmemory.clan.settings_chip").getString(),
			ChestGuiStyle.TEXT_GOLD, schema
		);
		// The rows are widgets; what needs text is the one action that is NOT here. The y
		// comes from initHostSettings' own row cursor — a hand-summed offset chain here
		// drifted the moment a settings row was added, landing the hint on a toggle.
		ChestGuiStyle.drawCentered(
			graphics, this.font,
			ChestGuiStyle.ellipsize(
				this.font,
				Component.translatable("screen.chestmemory.clan.settings_kick_hint").getString(),
				contentW
			),
			centerX, this.hostSettingsHintY, ChestGuiStyle.TEXT_MUTED
		);
	}

	/** Identity plate: a coloured mode chip on the left, the build's name centred. */
	private void drawIdentityPlate(
		GuiGraphicsExtractor graphics,
		int left,
		int y,
		int contentW,
		String chip,
		int chipColour,
		String title
	) {
		graphics.fill(left, y, left + contentW, y + 16, ChestGuiStyle.WOOD_DARK);
		graphics.fill(left + 1, y + 1, left + contentW - 1, y + 15, 0xFF2E2E2E);
		graphics.fill(left + 1, y + 1, left + contentW - 1, y + 2, ChestGuiStyle.withAlpha(0xFFFFFF, 0.18F));
		int chipW = this.font.width(chip) + 8;
		graphics.fill(left + 3, y + 3, left + 3 + chipW, y + 13, chipColour);
		graphics.text(this.font, chip, left + 7, y + 5, 0xFF1C1C1C, false);
		ChestGuiStyle.drawCentered(
			graphics, this.font,
			ChestGuiStyle.ellipsize(this.font, title, contentW - 2 * (chipW + 10)),
			left + contentW / 2, y + 5, ChestGuiStyle.TEXT_GOLD
		);
	}

	/** Progress bar with its caption inside the track, where contrast is guaranteed. */
	private void drawProgressWithLabel(
		GuiGraphicsExtractor graphics,
		int left,
		int y,
		int contentW,
		float fraction,
		String label
	) {
		ChestGuiStyle.drawProgressBar(graphics, left, y, contentW, 13, fraction);
		String text = ChestGuiStyle.ellipsize(this.font, label, contentW - 12);
		int tx = left + (contentW - this.font.width(text)) / 2;
		graphics.text(this.font, text, tx + 1, y + 3, 0xCC000000, false);
		graphics.text(this.font, text, tx, y + 2, ChestGuiStyle.TEXT_LIGHT, false);
	}

	/**
	 * The material grid: 18px slots at the chest panel's 19px pitch, on the shared tray,
	 * scaled counts, a tint for state — the same pieces AND the same layout as the chest
	 * panel, so the two read as one mod.
	 * Records geometry for click mapping and returns the hovered cell index, or -1.
	 */
	private int drawMaterialGrid(
		GuiGraphicsExtractor graphics,
		List<MatCell> cells,
		int left,
		int top,
		int contentW
	) {
		int bottom = this.gridBottom > 0 ? this.gridBottom : this.panelTop + this.panelH - 50;
		// The tray has a 2px border, and the scrollbar lives inside it.
		int inner = contentW - 4;
		// Columns must fit the width clicks can actually reach: the scroll region is
		// inner - 2 wide and its rowAt refuses the 6px scrollbar strip at the right edge,
		// so a column drawn past that line would render but never respond. n slots span
		// n·CELL minus the trailing gap, hence the gap comes back before dividing.
		int usable = inner - 2 - 6;
		int perRow = Math.max(1, (usable + ChestGuiStyle.GRID_GAP) / CELL);

		this.materialIds = new java.util.ArrayList<>(cells.size());
		for (MatCell c : cells) {
			this.materialIds.add(c.itemId());
		}
		this.materialGridPerRow = perRow;

		ChestGuiStyle.drawGridTray(graphics, left, top, contentW, bottom - top);
		int gridLeft = left + 3;
		int gridTop = top + 3;
		this.materialGridLeft = gridLeft;
		int totalRows = (cells.size() + perRow - 1) / perRow;
		this.materialScroll.layout(gridLeft, gridTop, inner - 2, bottom - 3, CELL, totalRows);

		if (cells.isEmpty()) {
			ChestGuiStyle.drawCentered(
				graphics, this.font,
				Component.translatable("screen.chestmemory.clan.no_materials").getString(),
				left + contentW / 2, top + Math.max(0, (bottom - top) / 2 - 4),
				0xFF505050
			);
			return -1;
		}

		int hoverIdx = materialAt(this.hoverX, this.hoverY);
		for (int r = this.materialScroll.firstVisible(); r < this.materialScroll.lastVisible(); r++) {
			int y = this.materialScroll.rowY(r);
			for (int c = 0; c < perRow; c++) {
				int i = r * perRow + c;
				if (i >= cells.size()) {
					break;
				}
				MatCell cell = cells.get(i);
				int x = gridLeft + c * CELL;
				ChestGuiStyle.drawSlot(graphics, x, y);
				graphics.item(icon(cell.itemId()), x + 1, y + 1);
				// Claim/target ring: state about PEOPLE sits on the rim, state about STOCK
				// tints the face — the two never fight over the same pixels.
				if (cell.border() != 0) {
					graphics.fill(x, y, x + 18, y + 1, cell.border());
					graphics.fill(x, y + 17, x + 18, y + 18, cell.border());
					graphics.fill(x, y + 1, x + 1, y + 17, cell.border());
					graphics.fill(x + 17, y + 1, x + 18, y + 17, cell.border());
				}
				// Tint over the icon: alpha low enough that the item stays recognisable —
				// the point is the state, and the count colour carries it too.
				if (cell.tint() != 0) {
					graphics.fill(x + 1, y + 1, x + 17, y + 17, cell.tint());
				}
				if (i == hoverIdx) {
					graphics.fill(x + 1, y + 1, x + 17, y + 17, 0x66FFFFFF);
				}
				if (cell.count() > 0) {
					ChestGuiStyle.drawSlotCount(
						graphics, this.font, ChestGuiStyle.formatCount(cell.count()), x, y, cell.countColour()
					);
				}
				if (cell.badge() != null) {
					graphics.text(this.font, cell.badge(), x + 2, y + 1, 0xE0000000, false);
					graphics.text(this.font, cell.badge(), x + 1, y, cell.badgeColour(), false);
				}
			}
		}
		this.materialScroll.drawScrollbar(graphics);
		return hoverIdx;
	}

	/**
	 * Solo grid click: aim the gather at this material — routes and glow, the same flow
	 * the chest panel starts — or stop when the clicked material is already the target.
	 * Mirrors the claim-toggle click of the clan mode, so one gesture works everywhere.
	 */
	private void soloClickMaterial(String itemId) {
		if (this.minecraft == null || itemId == null) {
			return;
		}
		boolean active = com.chestmemory.client.litematica.BuildGatherSession.isActive();
		String focus = com.chestmemory.client.litematica.BuildGatherSession.currentItemId();
		if (active && itemId.equals(focus)) {
			com.chestmemory.client.litematica.BuildGatherSession.clear();
			this.status = Component.translatable("screen.chestmemory.clan.solo_stopped").getString();
			this.rebuildWidgets();
			return;
		}
		com.chestmemory.client.litematica.BuildGatherSession.startQueue(this.minecraft, itemId, List.of());
		// startQueue may pick a different target (craft-only clicks stay in the chests
		// phase); report the item actually focused, not the one clicked.
		String focusNow = com.chestmemory.client.litematica.BuildGatherSession.currentItemId();
		String name = ChestMemoryStorage.itemDisplayName(focusNow != null ? focusNow : itemId);
		List<com.chestmemory.client.litematica.ChestRoute.Stop> route =
			com.chestmemory.client.litematica.BuildGatherSession.currentRoute();
		if (!route.isEmpty()) {
			int totalM = (int) Math.max(
				0, Math.round(com.chestmemory.client.litematica.ChestRoute.totalLength(route))
			);
			this.status = Component.translatable(
				"screen.chestmemory.clan.solo_route", name, route.size(), totalM
			).getString();
		} else {
			this.status = Component.translatable("screen.chestmemory.clan.solo_route_none", name).getString();
		}
		this.rebuildWidgets();
	}

	/** Index of the material slot under the pointer, or -1. */
	private int materialAt(double mx, double my) {
		if (this.tab != TAB_GATHER || this.materialGridPerRow <= 0) {
			return -1;
		}
		// The pitch leaves one gap pixel per row that belongs to no slot; rowAt treats the
		// body as inclusive, so the slot's last pixel is GRID_SLOT - 1.
		int row = this.materialScroll.rowAt(mx, my, ChestGuiStyle.GRID_SLOT - 1);
		if (row < 0) {
			return -1;
		}
		int localX = (int) (mx - this.materialGridLeft);
		int col = localX / CELL;
		if (col < 0 || col >= this.materialGridPerRow) {
			return -1;
		}
		// Same for the column: a click on the 1px seam between two slots must hit neither,
		// not silently claim the cell to its left.
		if (localX % CELL >= ChestGuiStyle.GRID_SLOT) {
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
		this.gatherScroll.layout(left, y, contentW, bottom, rowH + 2, entries.size());
		int rowW = this.gatherScroll.rowWidth();

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
				: "";
			// A number said "34%"; the bar shows a third at a glance.
			int barW = !loading && e.need() > 0 ? 40 : 0;
			int rightW = right.isEmpty() ? barW : this.font.width(right);
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
			if (!right.isEmpty()) {
				graphics.text(
					this.font, right, left + rowW - 6 - rightW, textY,
					active || loading ? ChestGuiStyle.TEXT_GOLD : ChestGuiStyle.TEXT_ON_WOOD_MUTED, false
				);
			} else if (barW > 0) {
				ChestGuiStyle.drawProgressBar(
					graphics, left + rowW - 6 - barW, y + 7, barW, 6,
					e.delivered() / (float) e.need()
				);
			}
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
