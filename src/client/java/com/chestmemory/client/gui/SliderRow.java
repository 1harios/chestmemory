package com.chestmemory.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.jspecify.annotations.Nullable;

import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.IntFunction;

/**
 * A settings row with a draggable slider — for values that are genuinely a range
 * (highlight duration, render distance), where cycling through presets meant clicking
 * eight times to get back to the value just passed.
 * <p>
 * Drag the knob, click anywhere on the track, or scroll the wheel over the row for
 * step-sized adjustments. The value label updates live while dragging.
 */
public class SliderRow extends AbstractWidget {
	private static final int TRACK_W = 78;
	private static final int TRACK_H = 4;
	private static final int KNOB_W = 5;
	private static final int KNOB_H = 12;

	private final int min;
	private final int max;
	private final int step;
	private final IntSupplier getter;
	private final IntConsumer setter;
	/** Formats the current value for the label, e.g. {@code v -> Component…("%s с", v)}. */
	private final IntFunction<Component> valueLabel;
	private boolean dragging;

	public SliderRow(
		int x, int y, int width, int height,
		Component label,
		int min, int max, int step,
		IntSupplier getter, IntConsumer setter,
		IntFunction<Component> valueLabel
	) {
		super(x, y, width, height, label);
		this.min = min;
		this.max = max;
		this.step = Math.max(1, step);
		this.getter = getter;
		this.setter = setter;
		this.valueLabel = valueLabel;
	}

	/** Hover description; wrapped by vanilla tooltip handling. */
	public SliderRow describe(@Nullable Component description) {
		if (description != null) {
			this.setTooltip(Tooltip.create(description));
		}
		return this;
	}

	private int trackX() {
		return this.getX() + this.width - 8 - TRACK_W;
	}

	private void setFromPointer(double mouseX) {
		double rel = (mouseX - trackX()) / (double) TRACK_W;
		int raw = min + (int) Math.round(Mth.clamp(rel, 0, 1) * (max - min));
		int snapped = Math.round(raw / (float) step) * step;
		this.setter.accept(Mth.clamp(snapped, min, max));
	}

	@Override
	public void onClick(MouseButtonEvent event, boolean doubleClick) {
		if (!this.active) {
			return;
		}
		// Only the track arms the slider — clicking the caption must not fling the value.
		if (event.x() >= trackX() - 6 && event.x() <= trackX() + TRACK_W + 6) {
			this.dragging = true;
			setFromPointer(event.x());
		}
	}

	@Override
	protected void onDrag(MouseButtonEvent event, double dragX, double dragY) {
		if (this.dragging) {
			setFromPointer(event.x());
			return;
		}
		super.onDrag(event, dragX, dragY);
	}

	@Override
	public void onRelease(MouseButtonEvent event) {
		this.dragging = false;
		super.onRelease(event);
	}

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		if (!this.active || !this.isMouseOver(x, y)) {
			return false;
		}
		int v = this.getter.getAsInt() + (scrollY > 0 ? step : -step);
		this.setter.accept(Mth.clamp(v, min, max));
		return true;
	}

	@Override
	protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		boolean hover = (this.isHoveredOrFocused() || this.dragging) && this.active;
		int x0 = this.getX();
		int y0 = this.getY();

		ChestGuiStyle.drawSettingRow(graphics, x0, y0, this.width, this.height, hover, this.active);

		var font = Minecraft.getInstance().font;
		int textY = y0 + (this.height - font.lineHeight) / 2 + 1;

		int value = this.getter.getAsInt();
		Component valueText = this.valueLabel.apply(value);
		int valueW = font.width(valueText);
		int tx = trackX();

		// Caption left, value right-aligned against the track
		int labelMax = tx - x0 - 14 - valueW - 6;
		String label = ChestGuiStyle.ellipsize(font, this.getMessage().getString(), Math.max(16, labelMax));
		graphics.text(font, label, x0 + 7, textY,
			this.active ? ChestGuiStyle.TEXT_LIGHT : ChestGuiStyle.TEXT_DISABLED, false);
		graphics.text(font, valueText, tx - 6 - valueW, textY,
			this.active ? ChestGuiStyle.VALUE_TEXT : ChestGuiStyle.TEXT_DISABLED, false);

		// Track: recessed groove, filled neutrally up to the knob
		int cy = y0 + this.height / 2;
		int gy = cy - TRACK_H / 2;
		graphics.fill(tx - 1, gy - 1, tx + TRACK_W + 1, gy + TRACK_H + 1, ChestGuiStyle.WOOD_DARK);
		graphics.fill(tx, gy, tx + TRACK_W, gy + TRACK_H, 0xFF2E2E2E);

		float t = (max == min) ? 0F : (Mth.clamp(value, min, max) - min) / (float) (max - min);
		int fillW = Math.round(t * TRACK_W);
		if (fillW > 0 && this.active) {
			graphics.fill(tx, gy, tx + fillW, gy + TRACK_H, 0xFF9A9A9A);
		}

		int kx = tx + Math.round(t * (TRACK_W - KNOB_W));
		int ky = cy - KNOB_H / 2;
		int knobColor = !this.active ? ChestGuiStyle.TEXT_DISABLED : (hover ? 0xFFFFFFFF : 0xFFE8E8E8);
		graphics.fill(kx - 1, ky - 1, kx + KNOB_W + 1, ky + KNOB_H + 1, ChestGuiStyle.WOOD_DARK);
		graphics.fill(kx, ky, kx + KNOB_W, ky + KNOB_H, knobColor);
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput output) {
		output.add(NarratedElementType.TITLE, this.getMessage());
	}
}
