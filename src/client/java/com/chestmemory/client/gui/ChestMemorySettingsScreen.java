package com.chestmemory.client.gui;

import com.chestmemory.client.ChestMemoryClient;
import com.chestmemory.client.data.ColorPalette;
import com.chestmemory.client.data.ModSettings;
import com.chestmemory.client.util.ClientScreens;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Full settings: highlight, display, gather, rebindable keys. Scrollable list.
 */
public class ChestMemorySettingsScreen extends Screen {
	private final Screen parent;

	private int panelLeft;
	private int panelTop;
	private int panelW;
	private int panelH;

	/** Content scroll in pixels (positive = scrolled down). */
	private int scrollY;
	private int contentH;
	private int viewTop;
	private int viewBottom;
	private int contentLeft;
	private int contentW;

	private final List<AbstractWidget> contentWidgets = new ArrayList<>();
	private final List<Integer> contentBaseY = new ArrayList<>();
	/** Per-widget: is this control enabled (dependencies). */
	private final List<java.util.function.BooleanSupplier> contentEnabled = new ArrayList<>();
	/** Per-widget: refresh label after any setting change. */
	private final List<java.util.function.Supplier<Component>> contentLabels = new ArrayList<>();

	private final List<SettingRowButton> keyButtons = new ArrayList<>();
	private final List<KeyMapping> keyMappings = new ArrayList<>();
	private @Nullable KeyMapping listeningKey;

	/** Color swatches drawn on the right of color-cycle rows. */
	private final List<ColorMark> colorMarkers = new ArrayList<>();

	public ChestMemorySettingsScreen(Screen parent) {
		super(Component.translatable("screen.chestmemory.settings"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		this.contentWidgets.clear();
		this.contentBaseY.clear();
		this.contentEnabled.clear();
		this.contentLabels.clear();
		this.keyButtons.clear();
		this.keyMappings.clear();
		this.sectionMarkers.clear();
		this.hintMarkers.clear();
		this.colorMarkers.clear();
		this.listeningKey = null;
		this.scrollY = 0;

		// Same size as the item panel — the frame must not resize when opening settings.
		this.panelW = ChestGuiStyle.panelWidth(this.width);
		this.panelH = ChestGuiStyle.panelHeight(this.height);
		this.panelLeft = (this.width - this.panelW) / 2;
		this.panelTop = (this.height - this.panelH) / 2;

		this.contentLeft = this.panelLeft + 12;
		this.contentW = this.panelW - 24;
		this.viewTop = this.panelTop + ChestGuiStyle.HEADER_H + 4;
		// Leave room for Done button
		this.viewBottom = this.panelTop + this.panelH - 32;

		int y = 0;
		int rowH = 18;
		int gap = 3;

		// ── Highlight ─────────────────────────────────────────────────────
		y = addSection(y, "screen.chestmemory.settings.section.highlight");
		y = addToggle(y, rowH, gap,
			() -> Component.translatable("screen.chestmemory.settings.duration", ModSettings.get().highlightSeconds()),
			() -> ModSettings.get().cycleHighlightSeconds(),
			null);
		y = addToggle(y, rowH, gap,
			() -> Component.translatable(ModSettings.get().showDistanceLabels()
				? "screen.chestmemory.settings.distance_on"
				: "screen.chestmemory.settings.distance_off"),
			() -> {
				ModSettings.get().toggleShowDistanceLabels();
			},
			null);
		// Only if distance labels are on
		y = addToggle(y, rowH, gap,
			() -> Component.translatable(ModSettings.get().distanceOnAllChests()
				? "screen.chestmemory.settings.distance_all"
				: "screen.chestmemory.settings.distance_nearest"),
			() -> ModSettings.get().toggleDistanceOnAllChests(),
			() -> ModSettings.get().showDistanceLabels());
		y = addToggle(y, rowH, gap,
			() -> Component.translatable(ModSettings.get().highlightSlots()
				? "screen.chestmemory.settings.slots_on"
				: "screen.chestmemory.settings.slots_off"),
			() -> ModSettings.get().toggleHighlightSlots(),
			null);
		// Warehouse (solo gather + clan): purple outline on drop-off chests
		y = addToggle(y, rowH, gap,
			() -> Component.translatable(ModSettings.get().showWarehouseGlow()
				? "screen.chestmemory.settings.warehouse_glow_on"
				: "screen.chestmemory.settings.warehouse_glow_off"),
			() -> ModSettings.get().toggleShowWarehouseGlow(),
			null);
		y = addToggle(y, rowH, gap,
			() -> Component.translatable("screen.chestmemory.settings.render_range", ModSettings.get().highlightRenderRange()),
			() -> ModSettings.get().cycleHighlightRenderRange(),
			null);
		y = addToggle(y, rowH, gap,
			() -> Component.translatable(ModSettings.get().showChestItemIcons()
				? "screen.chestmemory.settings.icons_on"
				: "screen.chestmemory.settings.icons_off"),
			() -> ModSettings.get().toggleShowChestItemIcons(),
			null);
		y = addToggle(y, rowH, gap,
			() -> Component.translatable("screen.chestmemory.settings.glow_intensity",
				glowIntensityLabel()),
			() -> ModSettings.get().cycleGlowIntensity(),
			null);

		// ── Colors ─────────────────────────────────────────────────────────
		y = addSection(y + 4, "screen.chestmemory.settings.section.colors");
		y = addHint(y, "screen.chestmemory.settings.colors_hint");
		y = addColorRow(y, rowH, gap,
			"screen.chestmemory.settings.color_glow",
			() -> ModSettings.get().glowColor(),
			() -> ModSettings.get().cycleGlowColor());
		y = addColorRow(y, rowH, gap,
			"screen.chestmemory.settings.color_nearest",
			() -> ModSettings.get().nearestColor(),
			() -> ModSettings.get().cycleNearestColor());
		y = addColorRow(y, rowH, gap,
			"screen.chestmemory.settings.color_slot",
			() -> ModSettings.get().slotColor(),
			() -> ModSettings.get().cycleSlotColor());
		y = addColorRow(y, rowH, gap,
			"screen.chestmemory.settings.color_route",
			() -> ModSettings.get().routeFocusColor(),
			() -> ModSettings.get().cycleRouteFocusColor());
		y = addColorRow(y, rowH, gap,
			"screen.chestmemory.settings.color_hud_accent",
			() -> ModSettings.get().hudAccentColor(),
			() -> ModSettings.get().cycleHudAccentColor());
		y = addColorRow(y, rowH, gap,
			"screen.chestmemory.settings.color_hud_title",
			() -> ModSettings.get().hudTitleColor(),
			() -> ModSettings.get().cycleHudTitleColor());
		y = addColorRow(y, rowH, gap,
			"screen.chestmemory.settings.color_warehouse",
			() -> ModSettings.get().warehouseColor(),
			() -> ModSettings.get().cycleWarehouseColor());
		y = addToggle(y, rowH, gap,
			() -> Component.translatable("screen.chestmemory.settings.colors_reset"),
			() -> {
				ModSettings.get().resetColors();
			},
			null);

		// ── Display / gather ───────────────────────────────────────────────
		y = addSection(y + 4, "screen.chestmemory.settings.section.gather");
		y = addToggle(y, rowH, gap,
			() -> Component.translatable(ModSettings.get().showGatherHud()
				? "screen.chestmemory.settings.hud_on"
				: "screen.chestmemory.settings.hud_off"),
			() -> ModSettings.get().toggleShowGatherHud(),
			null);
		y = addToggle(y, rowH, gap,
			() -> Component.translatable(
				"screen.chestmemory.settings.hud_corner",
				Component.translatable(hudCornerKey(ModSettings.get().gatherHudCorner()))
			),
			() -> ModSettings.get().cycleGatherHudCorner(),
			null);
		y = addToggle(y, rowH, gap,
			() -> Component.translatable(ModSettings.get().gatherChatMessages()
				? "screen.chestmemory.settings.chat_on"
				: "screen.chestmemory.settings.chat_off"),
			() -> ModSettings.get().toggleGatherChatMessages(),
			null);
		y = addToggle(y, rowH, gap,
			() -> Component.translatable(ModSettings.get().gatherAutoAdvance()
				? "screen.chestmemory.settings.auto_on"
				: "screen.chestmemory.settings.auto_off"),
			() -> ModSettings.get().toggleGatherAutoAdvance(),
			null);

		// ── Panel defaults ─────────────────────────────────────────────────
		y = addSection(y + 4, "screen.chestmemory.settings.section.panel");
		y = addToggle(y, rowH, gap,
			() -> Component.translatable("screen.chestmemory.settings.scope",
				ModSettings.get().listScope().label().getString()),
			() -> ModSettings.get().toggleListScope(),
			null);
		// Radius only matters for «nearby» scope
		y = addToggle(y, rowH, gap,
			() -> Component.translatable("screen.chestmemory.settings.nearby",
				ModSettings.get().nearbyRangeEnum().label().getString()),
			() -> ModSettings.get().cycleNearbyRange(),
			() -> ModSettings.get().listScope() == com.chestmemory.client.data.ListScope.NEARBY);
		y = addToggle(y, rowH, gap,
			() -> Component.translatable("screen.chestmemory.settings.sort",
				ModSettings.get().sortMode().label().getString()),
			() -> ModSettings.get().cycleSortMode(),
			null);

		// ── Keys ───────────────────────────────────────────────────────────
		y = addSection(y + 4, "screen.chestmemory.settings.section.keys");
		y = addKeyBind(y, rowH, gap, ChestMemoryClient.openPanelKey);
		y = addKeyBind(y, rowH, gap, ChestMemoryClient.clearHighlightKey);
		y = addKeyBind(y, rowH, gap, ChestMemoryClient.nextItemKey);
		y = addKeyBind(y, rowH, gap, ChestMemoryClient.toggleStagingKey);

		y = addHint(y + 3, "screen.chestmemory.settings.keys_hint");

		this.contentH = y + 4;
		applyScroll();

		// Done — fixed footer
		// Footer button in the same wooden style as the rows above it.
		this.addRenderableWidget(new SettingRowButton(
			this.contentLeft, this.panelTop + this.panelH - 26, this.contentW, 18,
			Component.translatable("screen.chestmemory.settings.done"),
			this::onClose
		));
	}

	/** Section title aligned with button column; tight spacing to first button. */
	private static String hudCornerKey(int corner) {
		return switch (corner) {
			case 1 -> "screen.chestmemory.settings.corner.top_right";
			case 2 -> "screen.chestmemory.settings.corner.bottom_left";
			case 3 -> "screen.chestmemory.settings.corner.bottom_right";
			default -> "screen.chestmemory.settings.corner.top_left";
		};
	}

	private int addSection(int y, String titleKey) {
		sectionMarkers.add(new SectionMark(y, titleKey));
		// Title ~10px high, then 2px before buttons (same left as buttons)
		return y + 12;
	}

	private final List<SectionMark> sectionMarkers = new ArrayList<>();

	private record SectionMark(int baseY, String titleKey) {
	}

	private record ColorMark(int baseY, java.util.function.IntSupplier rgb) {
	}

	private int addHint(int y, String key) {
		hintMarkers.add(new SectionMark(y, key));
		return y + 14;
	}

	private final List<SectionMark> hintMarkers = new ArrayList<>();

	private static Component glowIntensityLabel() {
		return switch (ModSettings.get().glowIntensity()) {
			case 0 -> Component.translatable("screen.chestmemory.settings.intensity_soft");
			case 2 -> Component.translatable("screen.chestmemory.settings.intensity_bright");
			default -> Component.translatable("screen.chestmemory.settings.intensity_normal");
		};
	}

	/** Cycle color preset; swatch drawn on the right in render. */
	private int addColorRow(
		int y,
		int rowH,
		int gap,
		String labelKey,
		java.util.function.IntSupplier rgb,
		Runnable onCycle
	) {
		LabelSupplier label = () -> Component.translatable(
			labelKey,
			ColorPalette.nameOf(rgb.getAsInt())
		);
		// Leave room for swatch on the right
		int btnW = this.contentW - 18;
		SettingRowButton btn = new SettingRowButton(
			this.contentLeft, this.viewTop + y, btnW, rowH, label.get(),
			() -> {
				onCycle.run();
				refreshAllSettingLabels();
				applyScroll();
			}
		);
		this.addRenderableWidget(btn);
		this.contentWidgets.add(btn);
		this.contentBaseY.add(y);
		this.contentEnabled.add(() -> true);
		this.contentLabels.add(label::get);
		this.colorMarkers.add(new ColorMark(y, rgb));
		return y + rowH + gap;
	}

	@FunctionalInterface
	private interface LabelSupplier {
		Component get();
	}

	private int addToggle(
		int y,
		int rowH,
		int gap,
		LabelSupplier label,
		Runnable onClick,
		java.util.function.@Nullable BooleanSupplier enabledWhen
	) {
		java.util.function.BooleanSupplier en = enabledWhen != null ? enabledWhen : () -> true;
		SettingRowButton btn = new SettingRowButton(
			this.contentLeft, this.viewTop + y, this.contentW, rowH, label.get(),
			() -> {
				if (!en.getAsBoolean()) {
					return;
				}
				onClick.run();
				refreshAllSettingLabels();
				applyScroll();
			}
		);
		this.addRenderableWidget(btn);
		this.contentWidgets.add(btn);
		this.contentBaseY.add(y);
		this.contentEnabled.add(en);
		this.contentLabels.add(label::get);
		return y + rowH + gap;
	}

	private int addKeyBind(int y, int rowH, int gap, KeyMapping mapping) {
		if (mapping == null) {
			return y;
		}
		SettingRowButton btn = new SettingRowButton(
			this.contentLeft, this.viewTop + y, this.contentW, rowH, keyLabel(mapping, false),
			() -> {
				this.listeningKey = mapping;
				refreshKeyLabels();
				applyScroll();
			}
		);
		this.addRenderableWidget(btn);
		this.contentWidgets.add(btn);
		this.contentBaseY.add(y);
		this.contentEnabled.add(() -> true);
		this.contentLabels.add(() -> keyLabel(mapping, this.listeningKey == mapping));
		this.keyButtons.add(btn);
		this.keyMappings.add(mapping);
		return y + rowH + gap;
	}

	private void refreshAllSettingLabels() {
		for (int i = 0; i < contentWidgets.size(); i++) {
			// Rows are SettingRowButton now; the old Button check silently stopped
			// matching, which would have frozen every caption after a click.
			if (contentWidgets.get(i) instanceof SettingRowButton b && i < contentLabels.size()) {
				b.setMessage(contentLabels.get(i).get());
			}
		}
		refreshKeyLabels();
	}

	private Component keyLabel(KeyMapping mapping, boolean listening) {
		if (listening && mapping == this.listeningKey) {
			return Component.translatable(
				"screen.chestmemory.settings.key_listening",
				Component.translatable(mapping.getName())
			);
		}
		return Component.translatable(
			"screen.chestmemory.settings.key_bound",
			Component.translatable(mapping.getName()),
			mapping.getTranslatedKeyMessage()
		);
	}

	private void refreshKeyLabels() {
		for (int i = 0; i < keyButtons.size(); i++) {
			KeyMapping map = keyMappings.get(i);
			keyButtons.get(i).setMessage(keyLabel(map, this.listeningKey == map));
		}
	}

	private void applyScroll() {
		int maxScroll = Math.max(0, this.contentH - (this.viewBottom - this.viewTop));
		this.scrollY = Math.max(0, Math.min(this.scrollY, maxScroll));
		for (int i = 0; i < contentWidgets.size(); i++) {
			AbstractWidget w = contentWidgets.get(i);
			int base = contentBaseY.get(i);
			int screenY = this.viewTop + base - this.scrollY;
			w.setY(screenY);
			boolean visible = screenY + w.getHeight() > this.viewTop && screenY < this.viewBottom;
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
		refreshKeyLabels();
		applyScroll();
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (this.listeningKey != null) {
			if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
				// Cancel rebind
				this.listeningKey = null;
				refreshKeyLabels();
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
			// Allow binding mouse buttons 1+ (right, middle, …); left click cancels if not on button
			if (button > 0) {
				bindKey(InputConstants.Type.MOUSE.getOrCreate(button));
				return true;
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (mouseX >= this.panelLeft && mouseX <= this.panelLeft + this.panelW
			&& mouseY >= this.viewTop && mouseY <= this.viewBottom) {
			this.scrollY -= (int) (scrollY * 12);
			applyScroll();
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

	@Override
	public void onClose() {
		this.listeningKey = null;
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
		// Clip-ish: darken outside content? Just draw titles for visible sections
		super.extractRenderState(graphics, mouseX, mouseY, a);

		ChestGuiStyle.drawCentered(
			graphics,
			this.font,
			this.title,
			this.panelLeft + this.panelW / 2,
			this.panelTop + 10,
			ChestGuiStyle.TEXT_TITLE
		);
		ChestGuiStyle.drawCentered(
			graphics,
			this.font,
			Component.translatable("screen.chestmemory.settings.hint"),
			this.panelLeft + this.panelW / 2,
			this.panelTop + 20,
			ChestGuiStyle.TEXT_MUTED
		);

		// Section headers (scroll with content)
		for (SectionMark m : sectionMarkers) {
			int sy = this.viewTop + m.baseY() - this.scrollY;
			if (sy + 10 < this.viewTop || sy > this.viewBottom) {
				continue;
			}
			// Wooden tab + rule, matching the panel frame
			ChestGuiStyle.drawSectionHeader(
				graphics,
				this.font,
				Component.translatable(m.titleKey()),
				this.contentLeft,
				sy,
				this.contentW
			);
		}
		for (SectionMark m : hintMarkers) {
			int sy = this.viewTop + m.baseY() - this.scrollY;
			if (sy + 10 < this.viewTop || sy > this.viewBottom) {
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

		// Color swatches (scroll with content)
		for (ColorMark m : colorMarkers) {
			int sy = this.viewTop + m.baseY() - this.scrollY;
			int sw = 12;
			int sh = 12;
			int sx = this.contentLeft + this.contentW - sw;
			if (sy + sh < this.viewTop || sy > this.viewBottom) {
				continue;
			}
			int rgb = m.rgb().getAsInt();
			int fill = 0xFF000000 | (rgb & 0xFFFFFF);
			// frame
			graphics.fill(sx - 1, sy + 2, sx + sw + 1, sy + 2 + sh + 2, 0xFF2A1A0E);
			graphics.fill(sx, sy + 3, sx + sw, sy + 3 + sh, fill);
		}

		// Scrollbar if needed
		int viewH = this.viewBottom - this.viewTop;
		if (this.contentH > viewH) {
			int barX = this.panelLeft + this.panelW - 6;
			graphics.fill(barX, this.viewTop, barX + 3, this.viewBottom, 0x66000000);
			float ratio = (float) viewH / this.contentH;
			int thumbH = Math.max(12, (int) (viewH * ratio));
			float t = this.contentH <= viewH ? 0 : (float) this.scrollY / (this.contentH - viewH);
			int thumbY = this.viewTop + (int) ((viewH - thumbH) * t);
			int accent = 0xFF000000 | ModSettings.get().hudAccentColor();
			graphics.fill(barX, thumbY, barX + 3, thumbY + thumbH, accent);
		}

		if (this.listeningKey != null) {
			String tip = Component.translatable("screen.chestmemory.settings.key_wait").getString();
			graphics.text(
				this.font,
				tip,
				this.contentLeft,
				this.panelTop + this.panelH - 40,
				0xFFFFD56A,
				false
			);
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
