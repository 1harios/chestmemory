package com.chestmemory.client.gui;

import com.chestmemory.client.data.ChestMemoryStorage;
import com.chestmemory.client.data.ContainerFilter;
import com.chestmemory.client.data.ContainerRecord;
import com.chestmemory.client.data.DimensionChoice;
import com.chestmemory.client.data.ItemSummary;
import com.chestmemory.client.data.ListScope;
import com.chestmemory.client.data.ModSettings;
import com.chestmemory.client.data.NearbyRange;
import com.chestmemory.client.data.SortMode;
import com.chestmemory.client.data.WorldTab;
import com.chestmemory.client.highlight.ChestHighlighter;
import com.chestmemory.client.litematica.BuildFilter;
import com.chestmemory.client.litematica.BuildGatherSession;
import com.chestmemory.client.litematica.LitematicaAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Chest panel with openable dropdown lists for world/sort, compact grid, persistent settings.
 */
public class ChestMemoryScreen extends Screen {
	private EditBox searchBox;
	private ItemGridWidget itemGrid;

	private DropdownWidget<WorldTab> worldDropdown;
	private DropdownWidget<ListScope> scopeDropdown;
	private DropdownWidget<NearbyRange> rangeDropdown;
	private DropdownWidget<DimensionChoice> dimensionDropdown;
	private DropdownWidget<SortMode> sortDropdown;
	private MultiSelectTypeDropdown typeDropdown;

	private final List<DropdownWidget<?>> dropdowns = new ArrayList<>();

	/** Multi-select: chests + barrels + hoppers… */
	private java.util.EnumSet<ContainerFilter> typeFilters = ModSettings.get().typeFilters();
	private DimensionChoice dimensionFilter = ModSettings.get().resolveDimensionChoice();
	private ListScope scope = ModSettings.get().listScope();
	private NearbyRange nearbyRange = ModSettings.get().nearbyRangeEnum();
	private SortMode sortMode = ModSettings.get().sortMode();
	private String statusLine = "";
	private String lastQuery = ModSettings.get().lastSearch();
	private List<WorldTab> worldTabs = List.of();
	private List<DimensionChoice> dimensionChoices = List.of(DimensionChoice.ALL, DimensionChoice.CURRENT);
	/** When true, grid shows Litematica material-list items to gather from chests. */
	private boolean litematicaBuildMode = false;
	/** Profile / scope / dim / sort filters — from settings, collapsed by default. */
	private boolean filtersExpanded = com.chestmemory.client.data.ModSettings.get().filtersExpanded();
	private Button filtersToggleButton;
	private Button litematicaButton;
	private Button leftBarButton;
	private Button rightBarButton;
	private Button buildFilterButton;
	private Button stagingMarkButton;
	private Button stagingClearButton;
	private ClearMemoryIconButton clearMemoryIcon;

	/** First click on Clear — wait for second confirm click. */
	private boolean clearConfirmPending;

	private int panelLeft;
	private int panelTop;
	private int panelW;
	private int panelH;

	public ChestMemoryScreen() {
		super(Component.translatable("screen.chestmemory.title"));
		// Always open with empty search (don't restore previous query)
		this.lastQuery = "";
		ModSettings.get().setLastSearch("");
		// If gather is already running, open on the materials (сборка) tab
		if (BuildGatherSession.isActive()) {
			this.litematicaBuildMode = true;
		}
	}

	@Override
	protected void init() {
		this.dropdowns.clear();
		this.clearConfirmPending = false;

		if (this.minecraft != null) {
			ChestMemoryStorage.get().ensureLoaded(this.minecraft);
			if (ChestMemoryStorage.get().liveWorldId() != null) {
				ChestMemoryStorage.get().setViewingWorld(ChestMemoryStorage.get().liveWorldId());
			}
		}

		// Do not force materials UI just because a gather session is running —
		// «Назад» can leave the materials list while HUD/route continue.

		// Same panel size for normal and scheme modes (no resize jump)
		this.panelW = Math.min(340, Math.max(260, this.width - 24));
		this.panelH = Math.min(300, Math.max(230, this.height - 32));
		this.panelLeft = (this.width - this.panelW) / 2;
		this.panelTop = (this.height - this.panelH) / 2;

		// Header icons: clear memory (left) + settings gear (right), slightly below title bar edge
		int iconSize = 16;
		int iconGap = 3;
		int gearX = this.panelLeft + this.panelW - iconSize - 6;
		int gearY = this.panelTop + 11;
		int clearX = gearX - iconSize - iconGap;

		this.clearMemoryIcon = new ClearMemoryIconButton(
			clearX, gearY, iconSize,
			Component.translatable("screen.chestmemory.clear_memory.tooltip"),
			this::onClearMemoryIconClick
		);
		// Only for normal list (not gather materials UI) — still can clear when session runs in bg
		this.clearMemoryIcon.visible = !this.litematicaBuildMode;
		this.clearMemoryIcon.active = !this.litematicaBuildMode;
		this.clearMemoryIcon.setConfirmMode(false);
		this.addRenderableWidget(this.clearMemoryIcon);

		this.addRenderableWidget(new SettingsIconButton(
			gearX, gearY, iconSize,
			Component.translatable("screen.chestmemory.settings.tooltip"),
			() -> {
				if (this.minecraft != null) {
					com.chestmemory.client.util.ClientScreens.set(
						this.minecraft,
						new ChestMemorySettingsScreen(this)
					);
				}
			}
		));

		int left = this.panelLeft + 12;
		int w = this.panelW - 24;
		// Leave room for title + "Сейчас: …" header (no overlap with first dropdown)
		int y = this.panelTop + ChestGuiStyle.HEADER_H + 6;
		int rowH = 18;
		int gap = 3;
		int half = (w - gap) / 2;

		this.worldTabs = ChestMemoryStorage.get().listWorldTabs();
		if (this.worldTabs.isEmpty()) {
			this.worldTabs = List.of(new WorldTab("?", "?", true, 0));
		}
		WorldTab selectedTab = this.worldTabs.getFirst();
		String viewing = ChestMemoryStorage.get().viewingWorldId();
		for (WorldTab tab : this.worldTabs) {
			if (tab.id().equals(viewing) || (viewing == null && tab.live())) {
				selectedTab = tab;
				break;
			}
		}
		rebuildDimensionChoices();
		if (!this.dimensionChoices.contains(this.dimensionFilter)) {
			this.dimensionFilter = DimensionChoice.CURRENT;
		}

		// One toggle: show/hide all profile & filter dropdowns
		this.filtersToggleButton = Button.builder(
			filtersToggleLabel(),
			btn -> {
				this.filtersExpanded = !this.filtersExpanded;
				ModSettings.get().setFiltersExpanded(this.filtersExpanded);
				this.rebuildWidgets();
			}
		).bounds(left, y, w, rowH).build();
		this.addRenderableWidget(this.filtersToggleButton);
		y += rowH + gap;

		this.worldDropdown = null;
		this.scopeDropdown = null;
		this.rangeDropdown = null;
		this.dimensionDropdown = null;
		this.sortDropdown = null;
		this.typeDropdown = null; // multi-select type filter set below when filters expanded

		if (this.filtersExpanded) {
			// Server profile
			this.worldDropdown = dropdown(
				left, y, w, rowH,
				Component.translatable("screen.chestmemory.world_tab"),
				Component.translatable("screen.chestmemory.prefix.profile"),
				this.worldTabs,
				selectedTab,
				WorldTab::buttonLabel,
				value -> {
					ChestMemoryStorage.get().setViewingWorld(value.id());
					if (!value.live()) {
						this.scope = ListScope.WORLD_TOTAL;
						ModSettings.get().setListScope(this.scope);
						if (this.scopeDropdown != null) {
							this.scopeDropdown.setSelected(ListScope.WORLD_TOTAL);
						}
					}
					rebuildDimensionChoices();
					if (this.dimensionDropdown != null) {
						this.dimensionDropdown.setOptions(this.dimensionChoices, this.dimensionFilter);
					}
					this.refreshList(this.searchBox != null ? this.searchBox.getValue() : "");
				}
			);
			y += rowH + gap;

			// Scope + range
			this.scopeDropdown = dropdown(
				left, y, half, rowH,
				Component.translatable("screen.chestmemory.scope"),
				Component.translatable("screen.chestmemory.prefix.scope"),
				List.of(ListScope.values()),
				this.scope,
				ListScope::label,
				value -> {
					if (value == ListScope.NEARBY && !ChestMemoryStorage.get().isViewingLive()) {
						this.scope = ListScope.WORLD_TOTAL;
						if (this.scopeDropdown != null) {
							this.scopeDropdown.setSelected(ListScope.WORLD_TOTAL);
						}
						ModSettings.get().setListScope(this.scope);
						this.statusLine = Component.translatable("screen.chestmemory.status.nearby_only_live").getString();
						this.refreshList(this.searchBox != null ? this.searchBox.getValue() : "");
						return;
					}
					this.scope = value;
					ModSettings.get().setListScope(value);
					this.refreshList(this.searchBox != null ? this.searchBox.getValue() : "");
				}
			);

			this.rangeDropdown = dropdown(
				left + half + gap, y, half, rowH,
				Component.translatable("screen.chestmemory.range"),
				Component.translatable("screen.chestmemory.prefix.range"),
				List.of(NearbyRange.values()),
				this.nearbyRange,
				NearbyRange::label,
				value -> {
					this.nearbyRange = value;
					ModSettings.get().setNearbyRange(value);
					this.refreshList(this.searchBox != null ? this.searchBox.getValue() : "");
				}
			);
			y += rowH + gap;

			// Dimension
			this.dimensionDropdown = dropdown(
				left, y, w, rowH,
				Component.translatable("screen.chestmemory.dimension"),
				Component.translatable("screen.chestmemory.prefix.world"),
				this.dimensionChoices,
				this.dimensionFilter,
				DimensionChoice::label,
				value -> {
					this.dimensionFilter = value;
					ModSettings.get().setDimensionChoice(value);
					this.refreshList(this.searchBox != null ? this.searchBox.getValue() : "");
				}
			);
			y += rowH + gap;

			// Sort + type
			this.sortDropdown = dropdown(
				left, y, half, rowH,
				Component.translatable("screen.chestmemory.sort"),
				Component.translatable("screen.chestmemory.prefix.sort"),
				List.of(SortMode.values()),
				this.sortMode,
				SortMode::label,
				value -> {
					this.sortMode = value;
					ModSettings.get().setSortMode(value);
					this.refreshList(this.searchBox != null ? this.searchBox.getValue() : "");
				}
			);

			this.typeDropdown = new MultiSelectTypeDropdown(
				this.minecraft,
				left + half + gap, y, half, rowH,
				Component.translatable("screen.chestmemory.prefix.type"),
				this.typeFilters,
				set -> {
					this.typeFilters = set;
					ModSettings.get().setTypeFilters(set);
					this.refreshList(this.searchBox != null ? this.searchBox.getValue() : "");
				}
			);
			this.addRenderableWidget(this.typeDropdown);
			y += rowH + gap;
		}

		// Search + clan (always visible)
		int clanBtnW = 52;
		this.searchBox = new EditBox(this.font, left, y, w - clanBtnW - gap, rowH, Component.translatable("screen.chestmemory.search"));
		this.searchBox.setMaxLength(128);
		this.searchBox.setHint(Component.translatable("screen.chestmemory.search_hint"));
		this.searchBox.setTextColor(ChestGuiStyle.TEXT_BODY);
		this.searchBox.setTextColorUneditable(ChestGuiStyle.TEXT_MUTED);
		this.searchBox.setValue(this.lastQuery);
		this.searchBox.setResponder(this::onSearchChanged);
		this.addRenderableWidget(this.searchBox);
		String clanBtnText = com.chestmemory.client.clan.ClanSessionManager.isInSession()
			? Component.translatable("screen.chestmemory.clan.btn_short_in").getString()
			: Component.translatable("screen.chestmemory.clan.btn_short").getString();
		this.addRenderableWidget(Button.builder(
			Component.literal(clanBtnText),
			btn -> {
				if (this.minecraft != null) {
					com.chestmemory.client.util.ClientScreens.set(this.minecraft, new ClanGatherScreen(this));
				}
			}
		).bounds(left + w - clanBtnW, y, clanBtnW, rowH).build());
		y += rowH + gap;

		// Scheme tools: one compact row (same panel height as normal Ё)
		this.buildFilterButton = null;
		this.stagingMarkButton = null;
		this.stagingClearButton = null;
		if (this.litematicaBuildMode) {
			// 4 columns: filter | warehouse | clear warehouse | clan
			int colGap = gap;
			int colW = (w - 3 * colGap) / 4;
			this.buildFilterButton = Button.builder(
				filterButtonLabel(),
				btn -> {
					BuildGatherSession.cycleFilter();
					btn.setMessage(filterButtonLabel());
					this.refreshList(this.searchBox != null ? this.searchBox.getValue() : "");
				}
			).bounds(left, y, colW, rowH).build();
			this.addRenderableWidget(this.buildFilterButton);

			this.stagingMarkButton = Button.builder(
				stagingMarkLabel(),
				btn -> {
					com.chestmemory.client.data.StagingPickMode.toggle();
					btn.setMessage(stagingMarkLabel());
					if (this.stagingClearButton != null) {
						this.stagingClearButton.setMessage(stagingClearLabel());
					}
					if (com.chestmemory.client.data.StagingPickMode.isActive()) {
						this.statusLine = Component.translatable("screen.chestmemory.status.staging_pick_on").getString();
						this.onClose();
					} else {
						this.statusLine = Component.translatable(
							"screen.chestmemory.status.staging_pick_off",
							ChestMemoryStorage.get().stagingCount()
						).getString();
					}
				}
			).bounds(left + colW + colGap, y, colW, rowH).build();
			this.addRenderableWidget(this.stagingMarkButton);

			this.stagingClearButton = Button.builder(
				stagingClearLabel(),
				btn -> {
					com.chestmemory.client.data.StagingPickMode.stop(false);
					ChestMemoryStorage.get().clearStaging();
					this.statusLine = Component.translatable("screen.chestmemory.status.staging_cleared").getString();
					btn.setMessage(stagingClearLabel());
					if (this.stagingMarkButton != null) {
						this.stagingMarkButton.setMessage(stagingMarkLabel());
					}
					this.refreshList(this.searchBox != null ? this.searchBox.getValue() : "");
				}
			).bounds(left + 2 * (colW + colGap), y, colW, rowH).build();
			this.addRenderableWidget(this.stagingClearButton);

			String clanLabel = com.chestmemory.client.clan.ClanSessionManager.isInSession()
				? Component.translatable(
					"screen.chestmemory.clan.btn_in",
					com.chestmemory.client.clan.ClanSessionManager.code()
				).getString()
				: Component.translatable("screen.chestmemory.clan.btn").getString();
			this.addRenderableWidget(Button.builder(
				Component.literal(clanLabel),
				btn -> {
					if (this.minecraft != null) {
						com.chestmemory.client.util.ClientScreens.set(
							this.minecraft,
							new ClanGatherScreen(this)
						);
					}
				}
			).bounds(left + 3 * (colW + colGap), y, colW, rowH).build());
			y += rowH + gap;
		}

		// Bottom buttons + grid: fill exactly to the button bar (full rows only)
		int buttonBarH = 22;
		int buttonY = this.panelTop + this.panelH - buttonBarH - 8;
		int gridBottom = buttonY - 6;
		// Stretch grid plate to bottom; ItemGridWidget only draws complete rows
		int gridH = Math.max(ItemGridWidget.SLOT + 4, gridBottom - y);
		this.itemGrid = new ItemGridWidget(this.minecraft, left, y, w, gridH, this::onItemSelected);
		this.addRenderableWidget(this.itemGrid);
		int bw = (w - 8) / 3;

		// Bottom bar always 3 equal buttons:
		// normal: «Снять свет» | «Сбор» | «Закрыть»
		// gather materials: HUD | «Назад» | «Завершить»
		this.leftBarButton = Button.builder(
			leftBarLabel(),
			btn -> this.onLeftBarClick()
		).bounds(left, buttonY, bw, 18).build();
		this.addRenderableWidget(this.leftBarButton);

		this.litematicaButton = Button.builder(
			litematicaButtonLabel(),
			btn -> this.onMiddleBarClick()
		).bounds(left + bw + 4, buttonY, bw, 18).build();
		updateLitematicaButtonState();
		this.addRenderableWidget(this.litematicaButton);

		this.rightBarButton = Button.builder(
			rightBarLabel(),
			btn -> this.onRightBarClick()
		).bounds(left + 2 * (bw + 4), buttonY, bw, 18).build();
		this.addRenderableWidget(this.rightBarButton);

		// Dropdowns last among widgets = drawn later; open list still needs overlay pass
		for (DropdownWidget<?> d : this.dropdowns) {
			this.addRenderableWidget(d);
		}

		// Immediate typing into search (no need to click the field)
		this.searchBox.setCanLoseFocus(true);
		this.setInitialFocus(this.searchBox);
		this.searchBox.setFocused(true);
		this.setFocused(this.searchBox);
		this.refreshList(this.lastQuery);
	}

	/** Type-to-search: route keys to the search box unless a dropdown is open. */
	@Override
	public boolean charTyped(net.minecraft.client.input.CharacterEvent event) {
		if (this.searchBox != null && !isAnyDropdownOpen()) {
			if (this.getFocused() != this.searchBox) {
				this.setFocused(this.searchBox);
				this.searchBox.setFocused(true);
			}
			return this.searchBox.charTyped(event);
		}
		return super.charTyped(event);
	}

	@Override
	public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
		// Esc closes an open dropdown first; only a second Esc closes the panel.
		if (event.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE && isAnyDropdownOpen()) {
			for (DropdownWidget<?> d : this.dropdowns) {
				d.close();
			}
			if (this.typeDropdown != null) {
				this.typeDropdown.close();
			}
			return true;
		}
		// Backspace / arrows in search even if focus drifted to a button
		if (this.searchBox != null && !isAnyDropdownOpen()) {
			int key = event.key();
			boolean searchKey = key == org.lwjgl.glfw.GLFW.GLFW_KEY_BACKSPACE
				|| key == org.lwjgl.glfw.GLFW.GLFW_KEY_DELETE
				|| key == org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT
				|| key == org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT
				|| key == org.lwjgl.glfw.GLFW.GLFW_KEY_HOME
				|| key == org.lwjgl.glfw.GLFW.GLFW_KEY_END
				|| (key == org.lwjgl.glfw.GLFW.GLFW_KEY_V && event.hasControlDown());
			if (searchKey || this.getFocused() == this.searchBox) {
				if (this.getFocused() != this.searchBox) {
					this.setFocused(this.searchBox);
					this.searchBox.setFocused(true);
				}
				if (this.searchBox.keyPressed(event)) {
					return true;
				}
			}
		}
		return super.keyPressed(event);
	}

	private boolean isAnyDropdownOpen() {
		for (DropdownWidget<?> d : this.dropdowns) {
			if (d != null && d.isOpen()) {
				return true;
			}
		}
		return this.typeDropdown != null && this.typeDropdown.isOpen();
	}

	private boolean isGatherUi() {
		return this.litematicaBuildMode;
	}

	private boolean isGatherRunning() {
		return BuildGatherSession.isActive();
	}

	private Component leftBarLabel() {
		// HUD toggle while gather session is running (even if browsing full list)
		if (isGatherUi() || isGatherRunning()) {
			return Component.translatable(
				ModSettings.get().showGatherHud()
					? "screen.chestmemory.hud_toggle_on"
					: "screen.chestmemory.hud_toggle_off"
			);
		}
		return Component.translatable("screen.chestmemory.clear_highlight");
	}

	private Component rightBarLabel() {
		if (isGatherUi()) {
			return Component.translatable("screen.chestmemory.finish_gather");
		}
		return Component.translatable("screen.chestmemory.close");
	}

	/** Header trash icon: clear item memory (two-click confirm). */
	private void onClearMemoryIconClick() {
		if (this.litematicaBuildMode) {
			return;
		}
		if (!ChestMemoryStorage.get().isViewingLive()) {
			this.statusLine = Component.translatable("screen.chestmemory.status.clear_only_live").getString();
			this.clearConfirmPending = false;
			if (this.clearMemoryIcon != null) {
				this.clearMemoryIcon.setConfirmMode(false);
			}
			return;
		}
		if (!this.clearConfirmPending) {
			this.clearConfirmPending = true;
			if (this.clearMemoryIcon != null) {
				this.clearMemoryIcon.setConfirmMode(true);
			}
			this.statusLine = Component.translatable("screen.chestmemory.status.clear_confirm_hint").getString();
			return;
		}
		// Confirmed
		this.clearConfirmPending = false;
		if (this.clearMemoryIcon != null) {
			this.clearMemoryIcon.setConfirmMode(false);
		}
		ChestMemoryStorage.get().clearAll();
		ChestHighlighter.clear();
		this.refreshList(this.searchBox != null ? this.searchBox.getValue() : "");
		this.statusLine = Component.translatable("screen.chestmemory.status.world_cleared").getString();
	}

	private void onLeftBarClick() {
		if (isGatherUi() || isGatherRunning()) {
			ModSettings.get().toggleShowGatherHud();
			if (this.leftBarButton != null) {
				this.leftBarButton.setMessage(leftBarLabel());
			}
			this.statusLine = Component.translatable(
				ModSettings.get().showGatherHud()
					? "screen.chestmemory.status.hud_on"
					: "screen.chestmemory.status.hud_off"
			).getString();
			return;
		}
		ChestHighlighter.clear();
		this.statusLine = Component.translatable("screen.chestmemory.status.highlight_cleared").getString();
	}

	/**
	 * Middle:
	 * - full list → «Сбор» open materials list (+ auto-start if needed)
	 * - materials list → «Назад» full item list, keep session running
	 */
	private void onMiddleBarClick() {
		if (this.litematicaBuildMode) {
			// Back to ALL remembered items — do NOT end gather
			leaveGatherListKeepSession();
			return;
		}
		enterGatherMode();
	}

	private void onRightBarClick() {
		if (isGatherUi()) {
			// Materials list — finish gather session
			finishGatherMode();
			return;
		}
		// Normal list — close panel
		this.onClose();
	}

	/**
	 * «Назад»: show full chest-memory item list, keep gather session + HUD running.
	 */
	private void leaveGatherListKeepSession() {
		this.litematicaBuildMode = false;
		this.clearConfirmPending = false;
		// Do NOT call BuildGatherSession.clear()
		this.statusLine = Component.translatable("screen.chestmemory.status.gather_back_items").getString();
		this.rebuildWidgets();
	}

	/** Open gather materials tab and start smart queue. */
	private void enterGatherMode() {
		if (!LitematicaAccess.isAvailable()) {
			this.statusLine = Component.translatable("screen.chestmemory.status.litematica_missing").getString();
			updateLitematicaButtonState();
			return;
		}
		if (!LitematicaAccess.hasActiveMaterialList()) {
			this.statusLine = Component.translatable("screen.chestmemory.status.litematica_no_list").getString();
			updateLitematicaButtonState();
			return;
		}
		this.litematicaBuildMode = true;
		this.clearConfirmPending = false;
		String name = LitematicaAccess.activeListName();
		this.statusLine = Component.translatable(
			"screen.chestmemory.status.litematica_on",
			name != null ? name : "?"
		).getString();
		this.rebuildWidgets();
		// After rebuild, start collection from materials list
		autoStartGatherFromList();
	}

	/** «Завершить»: stop session and return to full chest memory list. */
	private void finishGatherMode() {
		this.litematicaBuildMode = false;
		BuildGatherSession.clear();
		ChestHighlighter.clear();
		this.clearConfirmPending = false;
		this.statusLine = Component.translatable("screen.chestmemory.status.gather_finished").getString();
		this.rebuildWidgets();
	}

	/** Start gather queue from current panel materials (smart order). */
	private void autoStartGatherFromList() {
		if (this.minecraft == null || this.minecraft.player == null) {
			return;
		}
		ListScope effectiveScope = this.scope == ListScope.NEARBY ? ListScope.NEARBY : ListScope.WORLD_TOTAL;
		BuildFilter saved = BuildGatherSession.filter();
		BuildGatherSession.setFilter(BuildFilter.ALL);
		List<ItemSummary> panel = BuildGatherSession.buildPanelList(
			this.minecraft,
			"",
			effectiveScope,
			this.dimensionFilter,
			rangeBlocks()
		);
		BuildGatherSession.setFilter(saved);
		List<String> ordered = new ArrayList<>();
		for (ItemSummary s : panel) {
			if (s.neededForBuild() > 0) {
				ordered.add(s.itemId());
			}
		}
		if (ordered.isEmpty()) {
			BuildGatherSession.setActive(true);
			this.statusLine = Component.translatable("screen.chestmemory.status.litematica_complete").getString();
			return;
		}
		BuildGatherSession.startQueue(this.minecraft, ordered.getFirst(), ordered);
	}

	private <T> DropdownWidget<T> dropdown(
		int x, int y, int w, int h,
		Component title,
		Component prefix,
		List<T> options,
		T selected,
		java.util.function.Function<T, Component> labeler,
		java.util.function.Consumer<T> onChanged
	) {
		DropdownWidget<T> d = new DropdownWidget<>(
			this.minecraft, x, y, w, h, title, prefix, options, selected, labeler,
			value -> {
				// Close all after selection
				for (DropdownWidget<?> other : dropdowns) {
					if (other != null) {
						other.close();
					}
				}
				onChanged.accept(value);
			}
		);
		this.dropdowns.add(d);
		return d;
	}

	private void rebuildDimensionChoices() {
		this.dimensionChoices = ChestMemoryStorage.get().listDimensionChoices(playerDimension());
		if (this.dimensionChoices.isEmpty()) {
			this.dimensionChoices = List.of(DimensionChoice.ALL, DimensionChoice.CURRENT);
		}
	}

	private void onSearchChanged(String query) {
		if (query.equals(this.lastQuery)) {
			return;
		}
		this.lastQuery = query;
		ModSettings.get().setLastSearch(query);
		this.refreshList(query);
	}

	private String playerDimension() {
		if (this.minecraft == null || this.minecraft.level == null) {
			return null;
		}
		return ChestMemoryStorage.dimensionId(this.minecraft.level);
	}

	private Vec3 playerPos() {
		if (this.minecraft == null || this.minecraft.player == null) {
			return null;
		}
		return this.minecraft.player.position();
	}

	private double rangeBlocks() {
		return this.nearbyRange.blocks();
	}

	/** Material List open in Litematica — required to turn scheme gather on. */
	private boolean canEnableSchemeMode() {
		return LitematicaAccess.isAvailable() && LitematicaAccess.hasActiveMaterialList();
	}

	/**
	 * Middle «Сбор» / «Назад»:
	 * - materials UI → always can go back to all items
	 * - full list → need Material List (or active session) to open materials again
	 */
	private void updateLitematicaButtonState() {
		if (this.litematicaButton == null) {
			return;
		}
		// Do NOT force litematicaBuildMode=true just because session is active —
		// user may be browsing full list while gather continues in background.
		boolean clickable;
		if (this.litematicaBuildMode) {
			clickable = true; // Назад always works
		} else {
			clickable = canEnableSchemeMode() || isGatherRunning();
		}
		this.litematicaButton.active = clickable;
		this.litematicaButton.setMessage(litematicaButtonLabel());
		if (this.leftBarButton != null) {
			this.leftBarButton.setMessage(leftBarLabel());
		}
		if (this.rightBarButton != null) {
			this.rightBarButton.setMessage(rightBarLabel());
		}
	}

	/** Short label for the middle bar button. */
	private Component litematicaButtonLabel() {
		if (!LitematicaAccess.isAvailable() && !isGatherRunning()) {
			return Component.translatable("screen.chestmemory.litematica.missing");
		}
		// Materials list open → «Назад» to all items (session keeps running)
		if (this.litematicaBuildMode) {
			return Component.translatable("screen.chestmemory.back_to_list");
		}
		// Full list: open materials / resume materials UI
		if (!LitematicaAccess.hasActiveMaterialList() && !isGatherRunning()) {
			return Component.translatable("screen.chestmemory.litematica.btn_disabled");
		}
		return Component.translatable("screen.chestmemory.litematica.btn_off");
	}

	private Component filterButtonLabel() {
		// Compact — one third of scheme toolbar
		return Component.translatable(
			"screen.chestmemory.build_filter.button_short",
			BuildGatherSession.filter().label().getString()
		);
	}

	private Component stagingMarkLabel() {
		int n = ChestMemoryStorage.get().stagingCount();
		if (com.chestmemory.client.data.StagingPickMode.isActive()) {
			return Component.translatable("screen.chestmemory.staging.pick_on", n);
		}
		return Component.translatable("screen.chestmemory.staging.pick_off", n);
	}

	private Component stagingClearLabel() {
		return Component.translatable("screen.chestmemory.staging.clear");
	}

	private Component filtersToggleLabel() {
		if (this.filtersExpanded) {
			return Component.translatable("screen.chestmemory.filters.hide");
		}
		// Compact summary of current filter state
		String scopeShort = this.scope == ListScope.NEARBY
			? Component.translatable("screen.chestmemory.scope.nearby_short", this.nearbyRange.blocks()).getString()
			: Component.translatable("screen.chestmemory.scope.world_total_short").getString();
		String dimShort = this.dimensionFilter.label().getString();
		return Component.translatable("screen.chestmemory.filters.show", scopeShort, dimShort);
	}

	private void refreshList(String query) {
		if (this.minecraft == null || this.itemGrid == null) {
			return;
		}
		ChestMemoryStorage.get().ensureLoaded(this.minecraft);
		rebuildDimensionChoices();
		if (this.dimensionDropdown != null) {
			// Keep current selection if still present
			if (!this.dimensionChoices.contains(this.dimensionFilter)) {
				this.dimensionFilter = DimensionChoice.CURRENT;
				ModSettings.get().setDimensionChoice(this.dimensionFilter);
			}
			this.dimensionDropdown.setOptions(this.dimensionChoices, this.dimensionFilter);
		}

		ListScope effectiveScope = this.scope;
		if (effectiveScope == ListScope.NEARBY && !ChestMemoryStorage.get().isViewingLive()) {
			effectiveScope = ListScope.WORLD_TOTAL;
		}

		List<ItemSummary> items;
		if (this.litematicaBuildMode && LitematicaAccess.isAvailable()) {
			items = BuildGatherSession.buildPanelList(
				this.minecraft,
				query,
				effectiveScope,
				this.dimensionFilter,
				rangeBlocks()
			);
			if (items.isEmpty()) {
				if (!LitematicaAccess.hasActiveMaterialList()) {
					this.statusLine = Component.translatable("screen.chestmemory.status.litematica_no_list").getString();
				} else {
					// Empty for this filter — not necessarily fully done
					BuildFilter f = BuildGatherSession.filter();
					this.statusLine = Component.translatable(
						"screen.chestmemory.status.litematica_filter_empty",
						f.label().getString()
					).getString();
				}
			}
		} else {
			items = ChestMemoryStorage.get().listItems(
				query,
				this.typeFilters,
				this.dimensionFilter,
				effectiveScope,
				playerDimension(),
				playerPos(),
				rangeBlocks(),
				this.sortMode
			);
		}
		this.itemGrid.setItems(items);

		if (this.buildFilterButton != null) {
			this.buildFilterButton.visible = this.litematicaBuildMode;
			this.buildFilterButton.active = this.litematicaBuildMode;
			this.buildFilterButton.setMessage(filterButtonLabel());
		}

		int chests = ChestMemoryStorage.get().containerCount(
			this.typeFilters,
			this.dimensionFilter,
			effectiveScope,
			playerDimension(),
			playerPos(),
			rangeBlocks()
		);
		int totalQty = items.stream().mapToInt(ItemSummary::totalCount).sum();
		String scopeLabel = effectiveScope == ListScope.NEARBY
			? Component.translatable("screen.chestmemory.scope.nearby_short", this.nearbyRange.blocks()).getString()
			: Component.translatable("screen.chestmemory.scope.world_total_short").getString();
		// Show raw dimension id for CURRENT so farm vs build is clear
		String dimLabel;
		if (this.dimensionFilter.kind() == DimensionChoice.Kind.CURRENT) {
			String raw = playerDimension();
			dimLabel = Component.translatable(
				"screen.chestmemory.dimension.current_detail",
				DimensionChoice.displayHere(raw),
				raw != null ? raw : "?"
			).getString();
		} else {
			dimLabel = this.dimensionFilter.label().getString();
		}

		if (this.litematicaBuildMode && !items.isEmpty()) {
			int stillNeedTypes = (int) items.stream().filter(s -> s.neededForBuild() > 0).count();
			int readyTypes = (int) items.stream()
				.filter(s -> s.neededForBuild() > 0 && s.totalCount() > 0 && s.stillShort() <= 0)
				.count();
			int partialTypes = (int) items.stream()
				.filter(s -> s.neededForBuild() > 0 && s.totalCount() > 0 && s.stillShort() > 0)
				.count();
			int noneTypes = (int) items.stream()
				.filter(s -> s.neededForBuild() > 0 && s.totalCount() <= 0)
				.count();
			int doneTypes = (int) items.stream().filter(s -> s.neededForBuild() <= 0).count();
			int needSum = items.stream().mapToInt(ItemSummary::neededForBuild).sum();
			String listName = LitematicaAccess.activeListName();
			String filterName = BuildGatherSession.filter().label().getString();
			this.statusLine = Component.translatable(
				"screen.chestmemory.status.litematica_summary",
				listName != null ? listName : "?",
				filterName,
				items.size(),
				stillNeedTypes,
				readyTypes,
				partialTypes,
				noneTypes,
				doneTypes,
				needSum
			).getString();
		} else if (!this.litematicaBuildMode) {
			this.statusLine = Component.translatable(
				"screen.chestmemory.status.summary_short",
				items.size(),
				totalQty,
				chests,
				dimLabel,
				scopeLabel
			).getString();
		}

		updateLitematicaButtonState();
	}

	private void onItemSelected(ItemSummary summary) {
		Minecraft client = this.minecraft;
		if (client == null || client.player == null || client.level == null) {
			this.onClose();
			return;
		}

		if (!ChestMemoryStorage.get().isViewingLive()) {
			client.player.sendSystemMessage(Component.translatable(
				"message.chestmemory.browse_info",
				ChestMemoryStorage.itemDisplayName(summary.itemId()),
				summary.totalCount(),
				summary.containerCount(),
				ChestMemoryStorage.get().viewingDisplayName()
			));
			this.onClose();
			return;
		}

		// Build mode: start gather queue (clicked item first, then smart order of the rest)
		if (this.litematicaBuildMode && summary.isBuildNeed()) {
			if (summary.neededForBuild() <= 0) {
				// Already have enough in inventory — don't start a useless queue
				client.player.sendSystemMessage(Component.translatable(
					"message.chestmemory.build_item_done",
					ChestMemoryStorage.itemDisplayName(summary.itemId())
				));
				return;
			}
			// Clan: skip items claimed by others; claim free item for self
			if (com.chestmemory.client.clan.ClanSessionManager.isInSession()) {
				if (com.chestmemory.client.clan.ClanSessionManager.isClaimedByOther(client, summary.itemId())) {
					String who = com.chestmemory.client.clan.ClanSessionManager.claimName(summary.itemId());
					client.player.sendSystemMessage(Component.translatable(
						"message.chestmemory.clan_taken",
						who != null ? who : "?",
						ChestMemoryStorage.itemDisplayName(summary.itemId())
					));
					return;
				}
				if (!com.chestmemory.client.clan.ClanSessionManager.isClaimedByMe(client, summary.itemId())) {
					com.chestmemory.client.clan.ClanSessionManager.claimToggleAsync(client, summary.itemId(), null);
				}
			}
			// Queue order from ALL filter so path is always: ready → partial → none
			// (ignore current UI filter for the queue contents after the clicked item)
			BuildFilter saved = BuildGatherSession.filter();
			BuildGatherSession.setFilter(BuildFilter.ALL);
			ListScope effectiveScope = this.scope == ListScope.NEARBY ? ListScope.NEARBY : ListScope.WORLD_TOTAL;
			List<ItemSummary> panel = BuildGatherSession.buildPanelList(
				client, "",
				effectiveScope, this.dimensionFilter, rangeBlocks()
			);
			BuildGatherSession.setFilter(saved);
			List<String> ordered = new ArrayList<>();
			for (ItemSummary s : panel) {
				if (s.neededForBuild() > 0) {
					if (com.chestmemory.client.clan.ClanSessionManager.isClaimedByOther(client, s.itemId())) {
						continue;
					}
					ordered.add(s.itemId());
				}
			}
			BuildGatherSession.startQueue(client, summary.itemId(), ordered);
			this.onClose();
			return;
		}

		// Other item while scheme is active (e.g. craft ingredients) — keep scheme session,
		// but pause auto-retarget so this highlight is not overwritten.
		if (BuildGatherSession.isActive()) {
			BuildGatherSession.pauseSchemeHighlight();
		}

		ListScope effectiveScope = this.scope == ListScope.NEARBY ? ListScope.NEARBY : ListScope.WORLD_TOTAL;
		String dimension = ChestMemoryStorage.dimensionId(client.level);
		Vec3 pos = client.player.position();

		long duration = ModSettings.get().highlightDurationMs();
		ChestHighlighter.highlightItem(summary.itemId(), duration);

		List<ContainerRecord> matches = ChestMemoryStorage.get().findContainersWithItem(
			summary.itemId(),
			this.typeFilters,
			this.dimensionFilter,
			effectiveScope,
			dimension,
			pos,
			rangeBlocks()
		);

		List<ContainerRecord> worldMatches = matches.stream()
			.filter(r -> r.isWorldBlock() || r.hasHighlightPos())
			.sorted(Comparator.comparingDouble(r -> ChestMemoryStorage.distanceTo(r, pos, dimension)))
			.collect(Collectors.toList());

		List<ContainerRecord> virtualMatches = matches.stream()
			.filter(r -> r.isVirtual() && !r.hasHighlightPos())
			.toList();

		if (!worldMatches.isEmpty()) {
			ContainerRecord nearest = worldMatches.getFirst();
			int dist = (int) Math.max(0, ChestMemoryStorage.distanceTo(nearest, pos, dimension));
			int nx = nearest.isWorldBlock() ? nearest.x() : nearest.highlightX();
			int ny = nearest.isWorldBlock() ? nearest.y() : nearest.highlightY();
			int nz = nearest.isWorldBlock() ? nearest.z() : nearest.highlightZ();
			client.player.sendSystemMessage(Component.translatable(
				"message.chestmemory.highlight",
				ChestMemoryStorage.itemDisplayName(summary.itemId()),
				summary.totalCount(),
				worldMatches.size(),
				nx, ny, nz,
				dist,
				(int) (duration / 1000)
			));
		}

		if (!virtualMatches.isEmpty()) {
			int virtCount = virtualMatches.stream().mapToInt(r -> r.countOf(summary.itemId())).sum();
			client.player.sendSystemMessage(Component.translatable(
				"message.chestmemory.virtual_hits",
				virtCount,
				virtualMatches.size()
			));
		}

		if (worldMatches.isEmpty() && virtualMatches.isEmpty()) {
			client.player.sendSystemMessage(Component.translatable(
				effectiveScope == ListScope.NEARBY
					? "message.chestmemory.none_nearby"
					: "message.chestmemory.none"
			));
		}

		this.onClose();
	}

	/** Close every other dropdown when one opens. */
	private void exclusiveOpen(DropdownWidget<?> opened) {
		for (DropdownWidget<?> d : dropdowns) {
			if (d != opened) {
				d.close();
			}
		}
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		// Multi type filter expanded list first
		if (this.typeDropdown != null
			&& this.typeDropdown.isOpen()
			&& this.typeDropdown.isInExpandedArea(event.x(), event.y())) {
			return this.typeDropdown.mouseClicked(event, doubleClick);
		}

		// Open single-select dropdowns first (list is outside widget height)
		for (int i = dropdowns.size() - 1; i >= 0; i--) {
			DropdownWidget<?> d = dropdowns.get(i);
			if (d.isOpen() && d.isInExpandedArea(event.x(), event.y())) {
				boolean handled = d.mouseClicked(event, doubleClick);
				if (handled && d.isOpen()) {
					exclusiveOpen(d);
				}
				return true;
			}
		}

		// Closed bars — toggle open, make exclusive
		if (this.typeDropdown != null && this.typeDropdown.isInExpandedArea(event.x(), event.y())) {
			boolean wasOpen = this.typeDropdown.isOpen();
			boolean handled = this.typeDropdown.mouseClicked(event, doubleClick);
			if (handled) {
				if (this.typeDropdown.isOpen() && !wasOpen) {
					for (DropdownWidget<?> d : dropdowns) {
						d.close();
					}
				}
				return true;
			}
		}

		for (int i = dropdowns.size() - 1; i >= 0; i--) {
			DropdownWidget<?> d = dropdowns.get(i);
			if (d.isInExpandedArea(event.x(), event.y())) {
				boolean wasOpen = d.isOpen();
				boolean handled = d.mouseClicked(event, doubleClick);
				if (handled) {
					if (d.isOpen() && !wasOpen) {
						exclusiveOpen(d);
						if (this.typeDropdown != null) {
							this.typeDropdown.close();
						}
					}
					return true;
				}
			}
		}

		// Click outside any dropdown → close all
		boolean wasAnyOpen = isAnyDropdownOpen();
		for (DropdownWidget<?> d : dropdowns) {
			d.close();
		}
		if (this.typeDropdown != null) {
			this.typeDropdown.close();
		}
		if (wasAnyOpen) {
			// Dismissing an open list is the whole action. Passing the click on would let
			// it land on whatever the list was covering — usually the item grid, which
			// starts a highlight and closes the panel.
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		if (this.typeDropdown != null && this.typeDropdown.isOpen()
			&& this.typeDropdown.mouseScrolled(x, y, scrollX, scrollY)) {
			return true;
		}
		for (DropdownWidget<?> d : dropdowns) {
			if (d.isOpen() && d.mouseScrolled(x, y, scrollX, scrollY)) {
				return true;
			}
		}
		return super.mouseScrolled(x, y, scrollX, scrollY);
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		graphics.fill(0, 0, this.width, this.height, ChestGuiStyle.VIGNETTE);
		ChestGuiStyle.drawChestPanel(graphics, this.panelLeft, this.panelTop, this.panelW, this.panelH);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		super.extractRenderState(graphics, mouseX, mouseY, a);

		int centerX = this.panelLeft + this.panelW / 2;
		int maxTitleW = this.panelW - 24;

		// Title — single line, no strip behind it
		String titleText = ChestGuiStyle.ellipsize(this.font, this.title.getString(), maxTitleW);
		ChestGuiStyle.drawCentered(
			graphics,
			this.font,
			titleText,
			centerX,
			this.panelTop + 8,
			ChestGuiStyle.TEXT_TITLE
		);

		// "Сейчас: …" on its own line under the title (no overlap)
		String dimId = playerDimension();
		if (dimId != null) {
			String here = DimensionChoice.displayHere(dimId);
			Component hereComp = Component.translatable("screen.chestmemory.you_are_here", here);
			String hereText = ChestGuiStyle.ellipsize(this.font, hereComp.getString(), maxTitleW);
			ChestGuiStyle.drawCentered(
				graphics,
				this.font,
				hereText,
				centerX,
				this.panelTop + 20,
				ChestGuiStyle.TEXT_MUTED
			);
		}

		if (this.statusLine != null && !this.statusLine.isEmpty()) {
			String line = ChestGuiStyle.ellipsize(this.font, this.statusLine, this.panelW + 80);
			ChestGuiStyle.drawCentered(
				graphics,
				this.font,
				line,
				centerX,
				this.panelTop + this.panelH + 6,
				ChestGuiStyle.TEXT_LIGHT
			);
		}

		// Open dropdown lists on top of everything (including grid / other bars)
		for (DropdownWidget<?> d : dropdowns) {
			if (d.isOpen()) {
				d.renderOverlay(graphics, mouseX, mouseY);
			}
		}
		if (this.typeDropdown != null && this.typeDropdown.isOpen()) {
			this.typeDropdown.renderOverlay(graphics, mouseX, mouseY);
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
