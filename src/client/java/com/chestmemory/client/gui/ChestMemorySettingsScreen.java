package com.chestmemory.client.gui;

import com.chestmemory.client.ChestMemoryClient;
import com.chestmemory.client.data.ColorPalette;
import com.chestmemory.client.data.ListScope;
import com.chestmemory.client.data.ModSettings;
import com.chestmemory.client.util.ClientScreens;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * Settings, reworked as a tabbed sheet instead of one long blind scroll.
 * <p>
 * Five tabs — highlight, colours, gather, panel, keys — each short enough to see whole.
 * Ranges (duration, render distance) are real draggable sliders; on/off options are real
 * switches whose knob moves, so the state is visible without reading a caption; every row
 * explains itself in a hover tooltip. Everything still saves itself on change.
 */
public class ChestMemorySettingsScreen extends Screen {
	private final Screen parent;

	private enum Tab {
		HIGHLIGHT("screen.chestmemory.settings.tab.highlight"),
		COLORS("screen.chestmemory.settings.tab.colors"),
		GATHER("screen.chestmemory.settings.tab.gather"),
		PANEL("screen.chestmemory.settings.tab.panel"),
		KEYS("screen.chestmemory.settings.tab.keys");

		final String titleKey;

		Tab(String titleKey) {
			this.titleKey = titleKey;
		}

		Component label() {
			return Component.translatable(titleKey);
		}
	}

	/** Remembered across openings in one game session — you return to where you were. */
	private static Tab lastTab = Tab.HIGHLIGHT;

	private int panelLeft;
	private int panelTop;
	private int panelW;
	private int panelH;
	private int tabsY;

	/** Content scroll in pixels (positive = scrolled down). */
	private int scrollY;
	private int contentH;
	private boolean draggingScrollbar;
	private int viewTop;
	private int viewBottom;
	private int contentLeft;
	private int contentW;

	private final List<AbstractWidget> contentWidgets = new ArrayList<>();
	private final List<Integer> contentBaseY = new ArrayList<>();
	/** Per-widget: is this control enabled (dependencies). */
	private final List<BooleanSupplier> contentEnabled = new ArrayList<>();
	/** Rows whose right-hand value must be re-pulled after any click (cycles, keys). */
	private final List<Runnable> valueRefreshers = new ArrayList<>();
	private final List<SectionMark> hintMarkers = new ArrayList<>();

	private final List<SettingRowButton> keyButtons = new ArrayList<>();
	private final List<KeyMapping> keyMappings = new ArrayList<>();
	private @Nullable KeyMapping listeningKey;

	private static final int ROW_H = 20;
	private static final int ROW_GAP = 4;

	// ── Footer band: the Done button, and the signature under it ───────────
	//
	// This was three independent magic numbers that had to agree by hand — the viewport
	// ended 32px above the panel's foot, the button sat 26 above it, the signature 9 — and
	// they did not agree. drawSettingRow fills rows [y, y+h), so the button's last row was
	// exactly the signature's first row, and the signature's descenders reached the frame
	// with nothing to spare. Deriving all three positions from one set of paddings is the
	// fix: only the paddings are chosen freely, so the parts cannot drift into each other.
	/** Field edge → signature. */
	private static final int FOOTER_PAD = 4;
	/** One line of text. */
	private static final int CREDITS_H = 9;
	/** Same height as every settings row, so the footer reads as one of them. */
	private static final int DONE_H = 18;
	/** Signature → button; wide enough to hold the groove between them. */
	private static final int FOOTER_GAP = 5;
	/** Button → scrolling content. */
	private static final int CONTENT_GAP = 6;

	/** Panel foot → the signature's first row. */
	private static final int CREDITS_UP = FOOTER_PAD + CREDITS_H;
	/** Panel foot → the Done button's top row. */
	private static final int DONE_UP = CREDITS_UP + FOOTER_GAP + DONE_H;
	/** Panel foot → the end of the scrolling viewport. */
	private static final int VIEW_BOTTOM_UP = DONE_UP + CONTENT_GAP;

	public ChestMemorySettingsScreen(Screen parent) {
		super(Component.translatable("screen.chestmemory.settings"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		this.contentWidgets.clear();
		this.contentBaseY.clear();
		this.contentEnabled.clear();
		this.valueRefreshers.clear();
		this.hintMarkers.clear();
		this.keyButtons.clear();
		this.keyMappings.clear();
		this.listeningKey = null;

		// Same size as the item panel — the frame must not resize when opening settings.
		this.panelW = ChestGuiStyle.panelWidth(this.width);
		this.panelH = ChestGuiStyle.panelHeight(this.height);
		this.panelLeft = (this.width - this.panelW) / 2;
		this.panelTop = (this.height - this.panelH) / 2;

		this.contentLeft = this.panelLeft + 12;
		this.contentW = this.panelW - 24;
		// Tab strip sits at the bottom of the header band; its baseline lands exactly on
		// the frame's engraved header groove, so the strip reads as part of the frame.
		this.tabsY = this.panelTop + ChestGuiStyle.HEADER_H - 14;
		this.viewTop = this.panelTop + ChestGuiStyle.HEADER_H + 5;
		// Leave room for the whole footer band, not just the button.
		this.viewBottom = this.panelTop + this.panelH - VIEW_BOTTOM_UP;

		int y = 0;
		y = switch (lastTab) {
			case HIGHLIGHT -> buildHighlightTab(y);
			case COLORS -> buildColorsTab(y);
			case GATHER -> buildGatherTab(y);
			case PANEL -> buildPanelTab(y);
			case KEYS -> buildKeysTab(y);
		};

		this.contentH = y + 4;
		applyScroll();

		// Done — fixed footer in the same wooden style as the rows above it.
		this.addRenderableWidget(new SettingRowButton(
			this.contentLeft, this.panelTop + this.panelH - DONE_UP, this.contentW, DONE_H,
			Component.translatable("screen.chestmemory.settings.done"),
			this::onClose
		));
	}

	// ── Tab content ────────────────────────────────────────────────────────

	private int buildHighlightTab(int y) {
		y = addSlider(y,
			"screen.chestmemory.settings.row.duration",
			"screen.chestmemory.settings.row.duration.desc",
			5, 120, 5,
			() -> ModSettings.get().highlightSeconds(),
			v -> ModSettings.get().setHighlightSeconds(v),
			v -> Component.translatable("screen.chestmemory.settings.unit.seconds", v));
		y = addSlider(y,
			"screen.chestmemory.settings.row.render_range",
			"screen.chestmemory.settings.row.render_range.desc",
			32, 256, 8,
			() -> ModSettings.get().highlightRenderRange(),
			v -> ModSettings.get().setHighlightRenderRange(v),
			v -> Component.translatable("screen.chestmemory.settings.unit.blocks", v));
		y = addCycle(y,
			"screen.chestmemory.settings.row.intensity",
			"screen.chestmemory.settings.row.intensity.desc",
			ChestMemorySettingsScreen::glowIntensityLabel,
			() -> ModSettings.get().cycleGlowIntensity(),
			null);
		y = addSwitch(y,
			"screen.chestmemory.settings.row.distance",
			"screen.chestmemory.settings.row.distance.desc",
			() -> ModSettings.get().showDistanceLabels(),
			() -> ModSettings.get().toggleShowDistanceLabels(),
			null);
		y = addSwitch(y,
			"screen.chestmemory.settings.row.distance_all",
			"screen.chestmemory.settings.row.distance_all.desc",
			() -> ModSettings.get().distanceOnAllChests(),
			() -> ModSettings.get().toggleDistanceOnAllChests(),
			() -> ModSettings.get().showDistanceLabels());
		y = addSwitch(y,
			"screen.chestmemory.settings.row.icons",
			"screen.chestmemory.settings.row.icons.desc",
			() -> ModSettings.get().showChestItemIcons(),
			() -> ModSettings.get().toggleShowChestItemIcons(),
			null);
		y = addSwitch(y,
			"screen.chestmemory.settings.row.slots",
			"screen.chestmemory.settings.row.slots.desc",
			() -> ModSettings.get().highlightSlots(),
			() -> ModSettings.get().toggleHighlightSlots(),
			null);
		y = addSwitch(y,
			"screen.chestmemory.settings.row.warehouse_glow",
			"screen.chestmemory.settings.row.warehouse_glow.desc",
			() -> ModSettings.get().showWarehouseGlow(),
			() -> ModSettings.get().toggleShowWarehouseGlow(),
			null);
		return y;
	}

	private int buildColorsTab(int y) {
		y = addHint(y, "screen.chestmemory.settings.colors_hint");
		y = addColor(y, "screen.chestmemory.settings.row.color_glow",
			() -> ModSettings.get().glowColor(), () -> ModSettings.get().cycleGlowColor());
		y = addColor(y, "screen.chestmemory.settings.row.color_nearest",
			() -> ModSettings.get().nearestColor(), () -> ModSettings.get().cycleNearestColor());
		y = addColor(y, "screen.chestmemory.settings.row.color_slot",
			() -> ModSettings.get().slotColor(), () -> ModSettings.get().cycleSlotColor());
		y = addColor(y, "screen.chestmemory.settings.row.color_route",
			() -> ModSettings.get().routeFocusColor(), () -> ModSettings.get().cycleRouteFocusColor());
		y = addColor(y, "screen.chestmemory.settings.row.color_hud_accent",
			() -> ModSettings.get().hudAccentColor(), () -> ModSettings.get().cycleHudAccentColor());
		y = addColor(y, "screen.chestmemory.settings.row.color_hud_title",
			() -> ModSettings.get().hudTitleColor(), () -> ModSettings.get().cycleHudTitleColor());
		y = addColor(y, "screen.chestmemory.settings.row.color_warehouse",
			() -> ModSettings.get().warehouseColor(), () -> ModSettings.get().cycleWarehouseColor());
		y = addAction(y + 2,
			"screen.chestmemory.settings.colors_reset",
			"screen.chestmemory.settings.colors_reset.desc",
			() -> ModSettings.get().resetColors(),
			null);
		return y;
	}

	private static String hintPosKey(int pos) {
		return switch (pos) {
			case 1 -> "screen.chestmemory.settings.hint_pos.inside";
			case 2 -> "screen.chestmemory.settings.hint_pos.below";
			default -> "screen.chestmemory.settings.hint_pos.above";
		};
	}

	private int buildGatherTab(int y) {
		y = addSwitch(y,
			"screen.chestmemory.settings.row.slot_hint",
			"screen.chestmemory.settings.row.slot_hint.desc",
			() -> ModSettings.get().gatherSlotHint(),
			() -> ModSettings.get().toggleGatherSlotHint(),
			null);
		y = addCycle(y,
			"screen.chestmemory.settings.row.slot_hint_pos",
			"screen.chestmemory.settings.row.slot_hint_pos.desc",
			() -> Component.translatable(hintPosKey(ModSettings.get().gatherSlotHintPos())),
			() -> ModSettings.get().cycleGatherSlotHintPos(),
			() -> ModSettings.get().gatherSlotHint());
		y = addSwitch(y,
			"screen.chestmemory.settings.row.hud",
			"screen.chestmemory.settings.row.hud.desc",
			() -> ModSettings.get().showGatherHud(),
			() -> ModSettings.get().toggleShowGatherHud(),
			null);
		y = addCycle(y,
			"screen.chestmemory.settings.row.hud_corner",
			"screen.chestmemory.settings.row.hud_corner.desc",
			() -> Component.translatable(hudCornerKey(ModSettings.get().gatherHudCorner())),
			() -> ModSettings.get().cycleGatherHudCorner(),
			() -> ModSettings.get().showGatherHud());
		y = addSlider(y,
			"screen.chestmemory.settings.row.hud_scale",
			"screen.chestmemory.settings.row.hud_scale.desc",
			60, 150, 5,
			() -> ModSettings.get().gatherHudScalePct(),
			pct -> ModSettings.get().setGatherHudScalePct(pct),
			pct -> Component.literal(pct + "%"));
		y = addSwitch(y,
			"screen.chestmemory.settings.row.hud_compact",
			"screen.chestmemory.settings.row.hud_compact.desc",
			() -> ModSettings.get().gatherHudCompact(),
			() -> ModSettings.get().toggleGatherHudCompact(),
			() -> ModSettings.get().showGatherHud());
		y = addSwitch(y,
			"screen.chestmemory.settings.row.gather_chat",
			"screen.chestmemory.settings.row.gather_chat.desc",
			() -> ModSettings.get().gatherChatMessages(),
			() -> ModSettings.get().toggleGatherChatMessages(),
			null);
		y = addSwitch(y,
			"screen.chestmemory.settings.row.auto_advance",
			"screen.chestmemory.settings.row.auto_advance.desc",
			() -> ModSettings.get().gatherAutoAdvance(),
			() -> ModSettings.get().toggleGatherAutoAdvance(),
			null);
		return y;
	}

	private int buildPanelTab(int y) {
		// ── Slot counts: size / style / colour, with a live preview right below ──
		y = addSlider(y,
			"screen.chestmemory.settings.row.count_size",
			"screen.chestmemory.settings.row.count_size.desc",
			55, 100, 5,
			() -> ModSettings.get().slotCountScalePct(),
			v -> ModSettings.get().setSlotCountScalePct(v),
			v -> Component.translatable("screen.chestmemory.settings.unit.percent", v));
		y = addCycle(y,
			"screen.chestmemory.settings.row.count_style",
			"screen.chestmemory.settings.row.count_style.desc",
			ChestMemorySettingsScreen::slotCountStyleLabel,
			() -> ModSettings.get().cycleSlotCountStyle(),
			null);
		y = addColor(y, "screen.chestmemory.settings.row.count_color",
			() -> ModSettings.get().slotCountColor(), () -> ModSettings.get().cycleSlotCountColor());
		SlotCountPreviewRow preview = new SlotCountPreviewRow(
			this.contentLeft, this.viewTop + y, this.contentW, 24,
			Component.translatable("screen.chestmemory.settings.row.count_preview")
		);
		register(preview, y, () -> true);
		y += 24 + ROW_GAP + 2;

		y = addCycle(y,
			"screen.chestmemory.settings.row.scope",
			"screen.chestmemory.settings.row.scope.desc",
			() -> ModSettings.get().listScope().label(),
			() -> ModSettings.get().toggleListScope(),
			null);
		y = addCycle(y,
			"screen.chestmemory.settings.row.nearby",
			"screen.chestmemory.settings.row.nearby.desc",
			() -> ModSettings.get().nearbyRangeEnum().label(),
			() -> ModSettings.get().cycleNearbyRange(),
			() -> ModSettings.get().listScope() == ListScope.NEARBY);
		y = addCycle(y,
			"screen.chestmemory.settings.row.sort",
			"screen.chestmemory.settings.row.sort.desc",
			() -> ModSettings.get().sortMode().label(),
			() -> ModSettings.get().cycleSortMode(),
			null);
		y = addSwitch(y,
			"screen.chestmemory.settings.row.notify_other_world",
			"screen.chestmemory.settings.row.notify_other_world.desc",
			() -> ModSettings.get().notifyOtherWorld(),
			() -> ModSettings.get().toggleNotifyOtherWorld(),
			null);
		// CSV export lives here rather than as an icon on the panel: it is a rare action
		// and the panel header is for things used constantly.
		y = addAction(y + 2,
			"screen.chestmemory.settings.export",
			"screen.chestmemory.settings.export.desc",
			() -> {
				if (this.parent instanceof ChestMemoryScreen panel) {
					panel.onExportCsv();
					// Close settings so the panel's status line (and the opened folder)
					// are actually visible.
					this.onClose();
				}
			},
			() -> this.parent instanceof ChestMemoryScreen);
		return y;
	}

	private int buildKeysTab(int y) {
		y = addKeyBind(y, ChestMemoryClient.openPanelKey);
		y = addKeyBind(y, ChestMemoryClient.clearHighlightKey);
		y = addKeyBind(y, ChestMemoryClient.nextItemKey);
		y = addKeyBind(y, ChestMemoryClient.toggleStagingKey);
		y = addHint(y + 3, "screen.chestmemory.settings.keys_hint");
		return y;
	}

	// ── Row builders ───────────────────────────────────────────────────────

	private int addSwitch(
		int y,
		String labelKey,
		String descKey,
		BooleanSupplier state,
		Runnable onToggle,
		@Nullable BooleanSupplier enabledWhen
	) {
		BooleanSupplier en = enabledWhen != null ? enabledWhen : () -> true;
		ToggleSwitchRow row = new ToggleSwitchRow(
			this.contentLeft, this.viewTop + y, this.contentW, ROW_H,
			Component.translatable(labelKey),
			state,
			() -> {
				onToggle.run();
				refreshValues();
				applyScroll();
			}
		).describe(Component.translatable(descKey));
		register(row, y, en);
		return y + ROW_H + ROW_GAP;
	}

	private int addSlider(
		int y,
		String labelKey,
		String descKey,
		int min, int max, int step,
		IntSupplier getter,
		java.util.function.IntConsumer setter,
		java.util.function.IntFunction<Component> valueLabel
	) {
		SliderRow row = new SliderRow(
			this.contentLeft, this.viewTop + y, this.contentW, ROW_H,
			Component.translatable(labelKey),
			min, max, step, getter, setter, valueLabel
		).describe(Component.translatable(descKey));
		register(row, y, () -> true);
		return y + ROW_H + ROW_GAP;
	}

	private int addCycle(
		int y,
		String labelKey,
		String descKey,
		Supplier<Component> valueSupplier,
		Runnable onCycle,
		@Nullable BooleanSupplier enabledWhen
	) {
		BooleanSupplier en = enabledWhen != null ? enabledWhen : () -> true;
		SettingRowButton row = new SettingRowButton(
			this.contentLeft, this.viewTop + y, this.contentW, ROW_H,
			Component.translatable(labelKey),
			() -> {
				if (!en.getAsBoolean()) {
					return;
				}
				onCycle.run();
				refreshValues();
				applyScroll();
			}
		).describe(Component.translatable(descKey));
		row.setValue(valueSupplier.get());
		this.valueRefreshers.add(() -> row.setValue(valueSupplier.get()));
		register(row, y, en);
		return y + ROW_H + ROW_GAP;
	}

	private int addColor(int y, String labelKey, IntSupplier rgb, Runnable onCycle) {
		SettingRowButton row = new SettingRowButton(
			this.contentLeft, this.viewTop + y, this.contentW, ROW_H,
			Component.translatable(labelKey),
			() -> {
				onCycle.run();
				refreshValues();
				applyScroll();
			}
		);
		row.setSwatch(rgb);
		row.setValue(ColorPalette.nameOf(rgb.getAsInt()));
		this.valueRefreshers.add(() -> row.setValue(ColorPalette.nameOf(rgb.getAsInt())));
		register(row, y, () -> true);
		return y + ROW_H + ROW_GAP;
	}

	private int addAction(
		int y,
		String labelKey,
		String descKey,
		Runnable onClick,
		@Nullable BooleanSupplier enabledWhen
	) {
		BooleanSupplier en = enabledWhen != null ? enabledWhen : () -> true;
		SettingRowButton row = new SettingRowButton(
			this.contentLeft, this.viewTop + y, this.contentW, ROW_H,
			Component.translatable(labelKey),
			() -> {
				if (!en.getAsBoolean()) {
					return;
				}
				onClick.run();
				refreshValues();
				applyScroll();
			}
		).describe(Component.translatable(descKey));
		register(row, y, en);
		return y + ROW_H + ROW_GAP;
	}

	private int addKeyBind(int y, KeyMapping mapping) {
		if (mapping == null) {
			return y;
		}
		SettingRowButton row = new SettingRowButton(
			this.contentLeft, this.viewTop + y, this.contentW, ROW_H,
			Component.translatable(mapping.getName()),
			() -> {
				this.listeningKey = mapping;
				refreshValues();
				applyScroll();
			}
		);
		row.setValue(keyValue(mapping));
		this.valueRefreshers.add(() -> row.setValue(keyValue(mapping)));
		register(row, y, () -> true);
		this.keyButtons.add(row);
		this.keyMappings.add(mapping);
		return y + ROW_H + ROW_GAP;
	}

	private Component keyValue(KeyMapping mapping) {
		if (this.listeningKey == mapping) {
			return Component.translatable("screen.chestmemory.settings.key.listening")
				.withStyle(ChatFormatting.YELLOW);
		}
		return Component.literal("[").append(mapping.getTranslatedKeyMessage()).append("]");
	}

	private void register(AbstractWidget row, int baseY, BooleanSupplier enabledWhen) {
		this.addRenderableWidget(row);
		this.contentWidgets.add(row);
		this.contentBaseY.add(baseY);
		this.contentEnabled.add(enabledWhen);
	}

	private int addHint(int y, String key) {
		hintMarkers.add(new SectionMark(y, key));
		return y + 14;
	}

	private record SectionMark(int baseY, String titleKey) {
	}

	private void refreshValues() {
		for (Runnable r : valueRefreshers) {
			r.run();
		}
	}

	private static String hudCornerKey(int corner) {
		return switch (corner) {
			case 1 -> "screen.chestmemory.settings.corner.top_right";
			case 2 -> "screen.chestmemory.settings.corner.bottom_left";
			case 3 -> "screen.chestmemory.settings.corner.bottom_right";
			default -> "screen.chestmemory.settings.corner.top_left";
		};
	}

	private static Component glowIntensityLabel() {
		return switch (ModSettings.get().glowIntensity()) {
			case 0 -> Component.translatable("screen.chestmemory.settings.intensity_soft");
			case 2 -> Component.translatable("screen.chestmemory.settings.intensity_bright");
			default -> Component.translatable("screen.chestmemory.settings.intensity_normal");
		};
	}

	private static Component slotCountStyleLabel() {
		return switch (ModSettings.get().slotCountStyle()) {
			case ChestGuiStyle.COUNT_STYLE_SHADOW -> Component.translatable("screen.chestmemory.settings.countstyle.shadow");
			case ChestGuiStyle.COUNT_STYLE_PLATE -> Component.translatable("screen.chestmemory.settings.countstyle.plate");
			case ChestGuiStyle.COUNT_STYLE_PLAIN -> Component.translatable("screen.chestmemory.settings.countstyle.plain");
			default -> Component.translatable("screen.chestmemory.settings.countstyle.outline");
		};
	}

	// ── Tabs ───────────────────────────────────────────────────────────────

	private Component[] tabLabels() {
		Tab[] tabs = Tab.values();
		Component[] labels = new Component[tabs.length];
		for (int i = 0; i < tabs.length; i++) {
			labels[i] = tabs[i].label();
		}
		return labels;
	}

	private int tabIndexAt(double mouseX, double mouseY) {
		// Shared geometry with drawTabs — tab widths follow their labels.
		return ChestGuiStyle.tabIndexAt(
			this.font, tabLabels(), this.contentLeft, this.contentW, this.tabsY, mouseX, mouseY
		);
	}

	private void switchTab(Tab tab) {
		if (tab == lastTab) {
			return;
		}
		lastTab = tab;
		this.listeningKey = null;
		this.scrollY = 0;
		this.rebuildWidgets();
	}

	// ── Scroll / input plumbing ────────────────────────────────────────────

	private void applyScroll() {
		int maxScroll = maxScroll();
		this.scrollY = Math.max(0, Math.min(this.scrollY, maxScroll));
		for (int i = 0; i < contentWidgets.size(); i++) {
			AbstractWidget w = contentWidgets.get(i);
			int base = contentBaseY.get(i);
			int screenY = this.viewTop + base - this.scrollY;
			w.setY(screenY);
			// Fully inside the viewport only. Partial visibility let the top row ride over
			// the tabs and the bottom row over the Done button.
			boolean visible = screenY >= this.viewTop && screenY + w.getHeight() <= this.viewBottom;
			w.visible = visible;
			boolean depOk = i < contentEnabled.size() && contentEnabled.get(i).getAsBoolean();
			if (this.listeningKey != null) {
				// Only key-bind rows stay active while rebinding
				w.active = keyButtons.contains(w) && visible;
			} else {
				w.active = visible && depOk;
			}
		}
	}

	private void bindKey(InputConstants.Key key) {
		if (this.listeningKey == null) {
			return;
		}
		this.listeningKey.setKey(key);
		KeyMapping.resetMapping();
		Minecraft mc = this.minecraft;
		if (mc != null) {
			mc.options.save();
		}
		this.listeningKey = null;
		refreshValues();
		applyScroll();
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (this.listeningKey != null) {
			if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
				// Cancel rebind
				this.listeningKey = null;
				refreshValues();
				applyScroll();
				return true;
			}
			// Unbind with Delete / Backspace
			if (event.key() == GLFW.GLFW_KEY_DELETE || event.key() == GLFW.GLFW_KEY_BACKSPACE) {
				bindKey(InputConstants.UNKNOWN);
				return true;
			}
			bindKey(InputConstants.getKey(event));
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (this.listeningKey != null) {
			// Mouse buttons as binds (except left click on a key button handled by widget)
			int button = event.button();
			if (button > 0) {
				bindKey(InputConstants.Type.MOUSE.getOrCreate(button));
				return true;
			}
		}
		// Past the keybind capture (which WANTS the other buttons), only the left button
		// may work the painted tab strip and scrollbar: this handler receives every
		// button, so a right-click on «Цвета» switched tabs like a left click. Widgets
		// judge the button themselves via isValidClickButton.
		if (event.button() != 0) {
			return super.mouseClicked(event, doubleClick);
		}
		int tab = tabIndexAt(event.x(), event.y());
		if (tab >= 0) {
			switchTab(Tab.values()[tab]);
			return true;
		}
		// Grabbing the scrollbar must not fall through to the row behind it.
		if (isOverScrollbar(event.x(), event.y())) {
			this.draggingScrollbar = true;
			scrollToPointer(event.y());
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}

	private int maxScroll() {
		return Math.max(0, this.contentH - (this.viewBottom - this.viewTop));
	}

	/** Pointer is on the scrollbar strip at the right edge of the view. */
	private boolean isOverScrollbar(double mouseX, double mouseY) {
		return maxScroll() > 0
			&& mouseX >= this.panelLeft + this.panelW - 12
			&& mouseX <= this.panelLeft + this.panelW - 2
			&& mouseY >= this.viewTop && mouseY <= this.viewBottom;
	}

	private void scrollToPointer(double mouseY) {
		int max = maxScroll();
		if (max <= 0) {
			return;
		}
		int viewH = this.viewBottom - this.viewTop;
		int barH = Math.max(12, viewH * viewH / Math.max(1, this.contentH));
		int trackH = Math.max(1, viewH - barH);
		double rel = (mouseY - this.viewTop - barH / 2.0) / trackH;
		this.scrollY = (int) Math.round(Math.max(0, Math.min(1, rel)) * max);
		applyScroll();
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
		if (this.draggingScrollbar) {
			scrollToPointer(event.y());
			return true;
		}
		return super.mouseDragged(event, dragX, dragY);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		this.draggingScrollbar = false;
		return super.mouseReleased(event);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		// Give slider rows the wheel first — fine adjustment beats page scrolling there.
		if (super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)) {
			return true;
		}
		if (mouseX >= this.panelLeft && mouseX <= this.panelLeft + this.panelW
			&& mouseY >= this.viewTop && mouseY <= this.viewBottom) {
			this.scrollY -= (int) (scrollY * 12);
			applyScroll();
			return true;
		}
		return false;
	}

	@Override
	public void onClose() {
		this.listeningKey = null;
		if (this.minecraft != null) {
			ClientScreens.set(this.minecraft, this.parent);
		}
	}

	// ── Render ─────────────────────────────────────────────────────────────

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		graphics.fill(0, 0, this.width, this.height, ChestGuiStyle.VIGNETTE);
		ChestGuiStyle.drawChestPanel(graphics, this.panelLeft, this.panelTop, this.panelW, this.panelH);
	}

	/**
	 * The band under the Done button: a groove, then one line of text.
	 *
	 * The line is shared. While a key is being rebound the prompt takes it over, because
	 * that is the only thing worth reading at that moment — and because the prompt used to
	 * be drawn 40px above the panel's foot, which put it inside the scrolling viewport, on
	 * top of whichever row happened to be there.
	 */
	private void drawFooter(GuiGraphicsExtractor graphics) {
		int centerX = this.panelLeft + this.panelW / 2;
		int textY = this.panelTop + this.panelH - CREDITS_UP;

		// Engraved groove, cut the same way as the one under the header, so the footer reads
		// as part of the frame rather than as text that ran out of room.
		int grooveY = textY - FOOTER_GAP + 1;
		graphics.fill(
			this.panelLeft + 6, grooveY, this.panelLeft + this.panelW - 6, grooveY + 1,
			ChestGuiStyle.HEADER_LINE
		);
		graphics.fill(
			this.panelLeft + 6, grooveY + 1, this.panelLeft + this.panelW - 6, grooveY + 2,
			ChestGuiStyle.PLANK_SEAM_LIGHT
		);

		boolean rebinding = this.listeningKey != null;
		String line = Component.translatable(rebinding
			? "screen.chestmemory.settings.key_wait"
			: "screen.chestmemory.credits").getString();
		// Clipped to the content width: the signature fits today, but a longer translation
		// would run over the frame, and drawCentered has no opinion about that.
		ChestGuiStyle.drawCentered(
			graphics,
			this.font,
			ChestGuiStyle.ellipsize(this.font, line, this.contentW),
			centerX,
			textY,
			rebinding ? ChestGuiStyle.TEXT_TITLE : ChestGuiStyle.TEXT_ON_WOOD_MUTED
		);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		super.extractRenderState(graphics, mouseX, mouseY, a);

		ChestGuiStyle.drawCentered(
			graphics,
			this.font,
			this.title,
			this.panelLeft + this.panelW / 2,
			this.panelTop + 8,
			ChestGuiStyle.TEXT_TITLE
		);

		drawFooter(graphics);

		ChestGuiStyle.drawTabs(
			graphics,
			this.font,
			tabLabels(),
			this.contentLeft,
			this.tabsY,
			this.contentW,
			lastTab.ordinal(),
			tabIndexAt(mouseX, mouseY)
		);

		for (SectionMark m : hintMarkers) {
			int sy = this.viewTop + m.baseY() - this.scrollY;
			if (sy < this.viewTop || sy + 10 > this.viewBottom) {
				continue;
			}
			String hint = Component.translatable(m.titleKey()).getString();
			graphics.text(
				this.font,
				ChestGuiStyle.ellipsize(this.font, hint, this.contentW),
				this.contentLeft,
				sy,
				ChestGuiStyle.TEXT_MUTED,
				false
			);
		}

		int max = maxScroll();
		if (max > 0) {
			int trackH = this.viewBottom - this.viewTop;
			int barH = Math.max(12, trackH * trackH / Math.max(1, this.contentH));
			int trackX = this.panelLeft + this.panelW - 10;
			int barY = this.viewTop + (int) ((trackH - barH) * (this.scrollY / (float) max));
			graphics.fill(trackX, this.viewTop, trackX + 4, this.viewBottom, 0x66000000);
			graphics.fill(trackX, barY, trackX + 4, barY + barH,
				this.draggingScrollbar ? ChestGuiStyle.BRASS_BRIGHT : ChestGuiStyle.BRASS);
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
