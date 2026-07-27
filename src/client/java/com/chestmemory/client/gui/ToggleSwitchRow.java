package com.chestmemory.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

import java.util.function.BooleanSupplier;

/**
 * A settings row with a real ON/OFF switch instead of a caption that ends in "Вкл".
 * <p>
 * The state is read live from the supplier, so nothing has to refresh labels after a click,
 * and the knob animates between positions — the row answers "did my click land?" by moving.
 */
public class ToggleSwitchRow extends AbstractWidget {
	private static final int TRACK_W = 24;
	private static final int TRACK_H = 12;
	private static final int KNOB = 8;
	/** Switch ON — same green as the hub-online lamp, so "active" reads one way everywhere. */
	private static final int ON = ChestGuiStyle.LAMP_ONLINE;
	private static final int OFF_TRACK = 0xFF4A3826;

	private final BooleanSupplier state;
	private final Runnable onToggle;
	/** 0..1 animated knob position (approaches the state each frame). */
	private float knob;
	private boolean knobInitialized;

	public ToggleSwitchRow(int x, int y, int width, int height, Component label, BooleanSupplier state, Runnable onToggle) {
		super(x, y, width, height, label);
		this.state = state;
		this.onToggle = onToggle;
	}

	/** Hover description; wrapped by vanilla tooltip handling. */
	public ToggleSwitchRow describe(@Nullable Component description) {
		if (description != null) {
			this.setTooltip(Tooltip.create(description));
		}
		return this;
	}

	@Override
	public void onClick(MouseButtonEvent event, boolean doubleClick) {
		if (this.active && this.onToggle != null) {
			this.onToggle.run();
		}
	}

	@Override
	protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		boolean hover = this.isHoveredOrFocused() && this.active;
		int x0 = this.getX();
		int y0 = this.getY();

		ChestGuiStyle.drawSettingRow(graphics, x0, y0, this.width, this.height, hover, this.active);
		if (hover) {
			int accent = 0xFF000000 | com.chestmemory.client.data.ModSettings.get().hudAccentColor();
			graphics.fill(x0 + 1, y0 + 1, x0 + 3, y0 + this.height - 1, accent);
		}

		var font = Minecraft.getInstance().font;
		int textY = y0 + (this.height - font.lineHeight) / 2 + 1;
		int labelColor = this.active ? ChestGuiStyle.TEXT_LIGHT : ChestGuiStyle.TEXT_DISABLED;
		String label = ChestGuiStyle.ellipsize(
			font, this.getMessage().getString(), this.width - TRACK_W - 18
		);
		graphics.text(font, label, x0 + 7, textY, labelColor, false);

		// Switch track at the right edge
		boolean on = this.state != null && this.state.getAsBoolean();
		float target = on ? 1F : 0F;
		if (!knobInitialized) {
			knob = target;
			knobInitialized = true;
		} else {
			knob += (target - knob) * 0.35F;
			if (Math.abs(target - knob) < 0.02F) {
				knob = target;
			}
		}

		int tx = x0 + this.width - 7 - TRACK_W;
		int ty = y0 + (this.height - TRACK_H) / 2;
		int trackFill = !this.active
			? ChestGuiStyle.ROW_WOOD_DISABLED
			: on ? ON : OFF_TRACK;
		graphics.fill(tx - 1, ty - 1, tx + TRACK_W + 1, ty + TRACK_H + 1, ChestGuiStyle.WOOD_DARK);
		graphics.fill(tx, ty, tx + TRACK_W, ty + TRACK_H, trackFill);
		// Recess shading on the track
		graphics.fill(tx, ty, tx + TRACK_W, ty + 1, ChestGuiStyle.withAlpha(0x000000, 0.30F));

		int kx = tx + 2 + Math.round(knob * (TRACK_W - KNOB - 4));
		int ky = ty + (TRACK_H - KNOB) / 2;
		int knobColor = this.active ? 0xFFF4E8CC : ChestGuiStyle.TEXT_DISABLED;
		graphics.fill(kx - 1, ky - 1, kx + KNOB + 1, ky + KNOB + 1, ChestGuiStyle.WOOD_DARK);
		graphics.fill(kx, ky, kx + KNOB, ky + KNOB, knobColor);
		graphics.fill(kx, ky, kx + KNOB, ky + 1, ChestGuiStyle.withAlpha(0xFFFFFF, 0.45F));
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput output) {
		output.add(NarratedElementType.TITLE, this.getMessage());
	}
}
