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
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

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
	/** Profile / scope / dim / sort filters — from settings, collapsed by default. */
	private boolean filtersExpanded = com.chestmemory.client.data.ModSettings.get().filtersExpanded();
	private SettingRowButton filtersToggleButton;
	private SettingRowButton litematicaButton;
	private SettingRowButton leftBarButton;
	private SettingRowButton rightBarButton;
	private ClearMemoryIconButton clearMemoryIcon;

	/** First click on Clear — wait for second confirm click. */
	private boolean clearConfirmPending;
	/**
	 * Countdown for the armed Clear confirmation. Without it the armed state persisted for
	 * as long as the panel stayed open, so a click minutes later wiped the profile.
	 */
	private int clearConfirmTicks;
	/** 5 seconds at 20 tps. */
	private static final int CLEAR_CONFIRM_TICKS = 100;

	/**
	 * Pending search text, applied a few ticks after the last keystroke.
	 * Rebuilding the list walks every container and re-sorts, so doing it per character
	 * made fast typing stutter on large profiles.
	 */
	private @Nullable String pendingQuery;
	private int searchDebounceTicks;
	/** ~150 ms at 20 tps — below the threshold where the delay is noticeable. */
	private static final int SEARCH_DEBOUNCE_TICKS = 3;

	private int panelLeft;
	private int panelTop;
	private int panelW;
	private int panelH;

	public ChestMemoryScreen() {
		super(Component.translatable("screen.chestmemory.title"));
		// Always open with empty search (don't restore previous query)
		this.lastQuery = "";
		ModSettings.get().setLastSearch("");
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
		this.panelW = ChestGuiStyle.panelWidth(this.width);
		this.panelH = ChestGuiStyle.panelHeight(this.height);
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
		this.clearMemoryIcon.visible = true;
		this.clearMemoryIcon.active = true;
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

		// One toggle: show/hide all profile & filter dropdowns.
		// Label + right-aligned value, so the current filter state reads as data,
		// not as a caption glued into one long string.
		this.filtersToggleButton = new SettingRowButton(
			left, y, w, rowH,
			Component.translatable("screen.chestmemory.filters.label"),
			() -> {
				this.filtersExpanded = !this.filtersExpanded;
				ModSettings.get().setFiltersExpanded(this.filtersExpanded);
				this.rebuildWidgets();
			});
		this.filtersToggleButton.setValue(filtersToggleValue());
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

		// Search gets the whole row: the gather button lives in the bottom bar alone now —
		// two buttons named «Сбор» on one panel read as a mistake.
		this.searchBox = new EditBox(this.font, left, y, w, rowH, Component.translatable("screen.chestmemory.search"));
		this.searchBox.setMaxLength(128);
		this.searchBox.setHint(Component.translatable("screen.chestmemory.search_hint"));
		// The box paints a black background, so the dark TEXT_BODY was nearly invisible.
		// Typed text is white; the "Поиск…" hint keeps the muted tone until you type.
		this.searchBox.setTextColor(0xFFFFFFFF);
		this.searchBox.setTextColorUneditable(ChestGuiStyle.TEXT_MUTED);
		this.searchBox.setValue(this.lastQuery);
		this.searchBox.setResponder(this::onSearchChanged);
		this.addRenderableWidget(this.searchBox);
		y += rowH + gap;

		// Bottom buttons + grid: fill exactly to the button bar (full rows only)
		int buttonBarH = 22;
		int buttonY = this.panelTop + this.panelH - buttonBarH - 8;
		int gridBottom = buttonY - 6;
		// Stretch grid plate to bottom; ItemGridWidget only draws complete rows
		int gridH = Math.max(ItemGridWidget.SLOT + 4, gridBottom - y);
		this.itemGrid = new ItemGridWidget(this.minecraft, left, y, w, gridH, this::onItemSelected);
		this.addRenderableWidget(this.itemGrid);
		int bw = (w - 8) / 3;

		// Bottom bar, three equal buttons: «Снять свет» | «Сбор» | «Закрыть».
		// The middle one is the single entry to gathering — the gather screen carries
		// solo and clan alike, so the panel keeps none of that UI itself.
		this.leftBarButton = new SettingRowButton(left, buttonY, bw, 18, leftBarLabel(), () -> this.onLeftBarClick());
		this.addRenderableWidget(this.leftBarButton);

		this.litematicaButton = new SettingRowButton(left + bw + 4, buttonY, bw, 18, gatherButtonLabel(), () -> {
			if (this.minecraft != null) {
				com.chestmemory.client.util.ClientScreens.set(this.minecraft, new ClanGatherScreen(this));
			}
		});
		this.litematicaButton.active = true;
		this.addRenderableWidget(this.litematicaButton);

		this.rightBarButton = new SettingRowButton(left + 2 * (bw + 4), buttonY, bw, 18, Component.translatable("screen.chestmemory.close"), () -> this.onClose());
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

	private boolean isGatherRunning() {
		return BuildGatherSession.isActive();
	}

	private Component leftBarLabel() {
		// HUD toggle while a gather runs (the HUD is the gather's face on the screen);
		// otherwise the button clears chest highlights.
		if (isGatherRunning()) {
			return Component.translatable(
				ModSettings.get().showGatherHud()
					? "screen.chestmemory.hud_toggle_on"
					: "screen.chestmemory.hud_toggle_off"
			);
		}
		return Component.translatable("screen.chestmemory.clear_highlight");
	}

	/** Middle bar: the one gather entry. Named for the session when one is live. */
	private Component gatherButtonLabel() {
		String code = com.chestmemory.client.clan.ClanSessionManager.code();
		if (code != null && !code.isBlank()) {
			return Component.translatable("screen.chestmemory.clan.btn_in", code);
		}
		if (isGatherRunning()) {
			return Component.translatable("screen.chestmemory.clan.btn_short_in");
		}
		return Component.translatable("screen.chestmemory.clan.btn");
	}

	/** Header trash icon: clear item memory (two-click confirm). */
	private void onClearMemoryIconClick() {
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
			this.clearConfirmTicks = CLEAR_CONFIRM_TICKS;
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
		if (isGatherRunning()) {
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

	/** Export the list exactly as filtered on screen, then reveal the file. */
	public void onExportCsv() {
		List<ItemSummary> items = ChestMemoryStorage.get().listItems(
			this.lastQuery,
			this.typeFilters,
			this.dimensionFilter,
			this.scope == ListScope.NEARBY ? ListScope.NEARBY : ListScope.WORLD_TOTAL,
			playerDimension(),
			playerPos(),
			rangeBlocks(),
			this.sortMode
		);
		java.nio.file.Path out = ChestMemoryStorage.get().exportCsv(items, this.typeFilters, this.lastQuery);
		if (out == null) {
			this.statusLine = Component.translatable("screen.chestmemory.status.export_failed").getString();
			return;
		}
		this.statusLine = Component.translatable(
			"screen.chestmemory.status.exported",
			out.getFileName().toString()
		).getString();
		// Open the folder so the file is actually findable without hunting through .minecraft
		net.minecraft.util.Util.getPlatform().openPath(out.getParent());
	}

	private void onSearchChanged(String query) {
		if (query.equals(this.lastQuery)) {
			return;
		}
		this.lastQuery = query;
		ModSettings.get().setLastSearch(query);
		// Defer the rebuild — see pendingQuery. Clearing the box applies at once so the
		// full list comes back instantly.
		if (query.isEmpty()) {
			this.pendingQuery = null;
			this.searchDebounceTicks = 0;
			this.refreshList(query);
			return;
		}
		this.pendingQuery = query;
		this.searchDebounceTicks = SEARCH_DEBOUNCE_TICKS;
	}

	@Override
	public void onClose() {
		// Let go of any profile being browsed: it is a second full container map held in the
		// storage singleton, and it used to survive until the next tab switch, so one glance at
		// another server doubled the mod's footprint for the rest of the session.
		ChestMemoryStorage.get().releaseViewingProfile();
		super.onClose();
	}

	@Override
	public void tick() {
		super.tick();
		if (this.clearConfirmPending && --this.clearConfirmTicks <= 0) {
			this.clearConfirmPending = false;
			if (this.clearMemoryIcon != null) {
				this.clearMemoryIcon.setConfirmMode(false);
			}
			this.statusLine = "";
		}
		if (this.pendingQuery != null && --this.searchDebounceTicks <= 0) {
			String q = this.pendingQuery;
			this.pendingQuery = null;
			this.refreshList(q);
		}
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

	private Component filtersToggleValue() {
		if (this.filtersExpanded) {
			return Component.translatable("screen.chestmemory.filters.value.hide");
		}
		// Compact summary of current filter state
		String scopeShort = this.scope == ListScope.NEARBY
			? Component.translatable("screen.chestmemory.scope.nearby_short", this.nearbyRange.blocks()).getString()
			: Component.translatable("screen.chestmemory.scope.world_total_short").getString();
		String dimShort = this.dimensionFilter.label().getString();
		return Component.translatable("screen.chestmemory.filters.value.show", scopeShort, dimShort);
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

		List<ItemSummary> items = ChestMemoryStorage.get().listItems(
			query,
			this.typeFilters,
			this.dimensionFilter,
			effectiveScope,
			playerDimension(),
			playerPos(),
			rangeBlocks(),
			this.sortMode
		);
		this.itemGrid.setItems(items);

		if (this.filtersToggleButton != null) {
			this.filtersToggleButton.setValue(filtersToggleValue());
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

		this.statusLine = Component.translatable(
			"screen.chestmemory.status.summary_short",
			items.size(),
			totalQty,
			chests,
			dimLabel,
			scopeLabel
		).getString();

		if (this.litematicaButton != null) {
			this.litematicaButton.setMessage(gatherButtonLabel());
		}
		if (this.leftBarButton != null) {
			this.leftBarButton.setMessage(leftBarLabel());
		}
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

		// World blocks only: the ender chest (virtual, with a remembered glow position)
		// must not become "ближайший на 320м" in chat — it is reachable anywhere.
		List<ContainerRecord> worldMatches = matches.stream()
			.filter(r -> r.isWorldBlock())
			.sorted(Comparator.comparingDouble(r -> ChestMemoryStorage.distanceTo(r, pos, dimension)))
			.collect(Collectors.toList());

		List<ContainerRecord> virtualMatches = matches.stream()
			.filter(r -> r.isVirtual() && !r.hasHighlightPos())
			.toList();

		// Multiworld reality check: whatever the panel filter says, where does this item
		// actually lie relative to the world the player is standing in? On a multiworld
		// server "I clicked it and nothing glows" almost always means "it is in the other
		// world at these same coordinates" — say that out loud instead of staying silent.
		List<com.chestmemory.client.data.WorldBreakdown.Entry> whereGroups = List.of();
		int hereCount = 0;
		int elsewhereCount = 0;
		if (ModSettings.get().notifyOtherWorld()) {
			String currentTag = com.chestmemory.client.data.WorldFingerprint.current(client);
			whereGroups = com.chestmemory.client.data.WorldBreakdown.of(
				ChestMemoryStorage.get().liveContainersSnapshot(), summary.itemId(), dimension, currentTag
			);
			hereCount = com.chestmemory.client.data.WorldBreakdown.hereCount(whereGroups);
			elsewhereCount = com.chestmemory.client.data.WorldBreakdown.elsewhereCount(whereGroups);
		}
		boolean onlyElsewhere = hereCount <= 0 && elsewhereCount > 0;

		if (!worldMatches.isEmpty() && !onlyElsewhere) {
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
			if (elsewhereCount > 0) {
				// Some of it is here, the rest in other worlds — one quiet extra line.
				client.player.sendSystemMessage(Component.translatable(
					"message.chestmemory.other_world_also",
					elsewhereCount,
					formatWhereList(whereGroups, 2)
				));
			}
		}

		if (!virtualMatches.isEmpty()) {
			int virtCount = virtualMatches.stream().mapToInt(r -> r.countOf(summary.itemId())).sum();
			client.player.sendSystemMessage(Component.translatable(
				"message.chestmemory.virtual_hits",
				virtCount,
				virtualMatches.size()
			));
		}

		if (onlyElsewhere) {
			// Everything this player remembers of the item is in another world / dimension.
			client.player.sendSystemMessage(Component.translatable(
				"message.chestmemory.other_world_only",
				ChestMemoryStorage.itemDisplayName(summary.itemId()),
				formatWhereList(whereGroups, 3)
			));
		} else if (worldMatches.isEmpty() && virtualMatches.isEmpty()) {
			client.player.sendSystemMessage(Component.translatable(
				effectiveScope == ListScope.NEARBY
					? "message.chestmemory.none_nearby"
					: "message.chestmemory.none"
			));
		}

		this.onClose();
	}

	/** "«Мир ферм» ×250 (3 сунд.), «Ад» ×12 (1 сунд.)" — the elsewhere part of a breakdown. */
	private static String formatWhereList(List<com.chestmemory.client.data.WorldBreakdown.Entry> groups, int limit) {
		StringBuilder sb = new StringBuilder();
		int shown = 0;
		for (com.chestmemory.client.data.WorldBreakdown.Entry e : groups) {
			if (e.here()) {
				continue;
			}
			if (shown >= limit) {
				sb.append(", …");
				break;
			}
			if (shown > 0) {
				sb.append(", ");
			}
			sb.append(Component.translatable(
				"chestmemory.world.entry",
				ItemGridWidget.worldLabel(e).getString(),
				e.count(),
				e.containers()
			).getString());
			shown++;
		}
		return sb.toString();
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
			// Normally the status sits just under the panel. On a short screen the panel
			// already reaches the bottom edge, and the line was drawn off-screen — which
			// hid every explanation the panel gives ("nearby is live-only", "clear only
			// works on live", item totals). Fall back to inside the panel there.
			int belowPanel = this.panelTop + this.panelH + 6;
			boolean fits = belowPanel + this.font.lineHeight <= this.height;
			int statusY = fits ? belowPanel : this.panelTop + this.panelH - 12;
			int maxWidth = fits ? this.panelW + 80 : this.panelW - 24;
			String line = ChestGuiStyle.ellipsize(this.font, this.statusLine, maxWidth);
			if (!fits) {
				// Inside the panel the text needs its own backdrop to stay readable.
				int halfW = this.font.width(line) / 2 + 3;
				graphics.fill(
					centerX - halfW, statusY - 2,
					centerX + halfW, statusY + this.font.lineHeight,
					0xC0000000
				);
			}
			ChestGuiStyle.drawCentered(
				graphics,
				this.font,
				line,
				centerX,
				statusY,
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
