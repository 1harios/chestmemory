package com.chestmemory.client.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.function.IntSupplier;
import org.jspecify.annotations.Nullable;

/**
 * A settings row drawn in the mod's wooden style rather than as a vanilla button.
 * <p>
 * Layout is label-left / value-right, so a column of rows reads as a settings list
 * instead of a stack of centred captions. Rows whose value is part of the caption
 * (plain toggles) simply leave {@code value} null and get a centred label.
 */
public class SettingRowButton extends AbstractWidget {
	private final Runnable onPress;
	private @Nullable Component value;
	/** Optional colour swatch drawn at the right edge (colour settings). */
	private @Nullable IntSupplier swatch;

	public SettingRowButton(int x, int y, int width, int height, Component label, Runnable onPress) {
		super(x, y, width, height, label);
		this.onPress = onPress;
	}

	/** Right-aligned value text; when null the label is centred. */
	public void setValue(@Nullable Component value) {
		this.value = value;
	}

	public void setSwatch(@Nullable IntSupplier swatch) {
		this.swatch = swatch;
	}

	/** Hover description; wrapped by vanilla tooltip handling. */
	public SettingRowButton describe(@Nullable Component description) {
		if (description != null) {
			this.setTooltip(net.minecraft.client.gui.components.Tooltip.create(description));
		}
		return this;
	}

	@Override
	public void onClick(MouseButtonEvent event, boolean doubleClick) {
		if (this.active && this.onPress != null) {
			this.onPress.run();
		}
	}

	@Override
	protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		boolean hover = this.isHoveredOrFocused() && this.active;
		int x0 = this.getX();
		int y0 = this.getY();

		// Hover state is the row itself: lighter face + white outline (vanilla grammar).
		ChestGuiStyle.drawSettingRow(graphics, x0, y0, this.width, this.height, hover, this.active);

		var font = net.minecraft.client.Minecraft.getInstance().font;
		int textY = y0 + (this.height - font.lineHeight) / 2 + 1;
		// Disabled rows keep a legible tone: TEXT_MUTED on the dark row was only 2.6:1.
		int labelColor = this.active ? ChestGuiStyle.TEXT_LIGHT : ChestGuiStyle.TEXT_DISABLED;

		int swatchW = this.swatch != null ? 14 : 0;
		if (this.value == null && swatchW == 0) {
			// No separate value — centre the caption, as toggles read better that way.
			String text = ChestGuiStyle.ellipsize(font, this.getMessage().getString(), this.width - 12);
			ChestGuiStyle.drawCentered(graphics, font, text, x0 + this.width / 2, textY, labelColor);
		} else {
			// Component-aware width/draw: values keep their own styles (colours, italics).
			int valueW = this.value != null ? font.width(this.value) : 0;
			int labelMax = this.width - 12 - valueW - swatchW - (valueW > 0 ? 6 : 0);
			String label = ChestGuiStyle.ellipsize(font, this.getMessage().getString(), Math.max(16, labelMax));
			graphics.text(font, label, x0 + 7, textY, labelColor, false);
			if (valueW > 0) {
				int vx = x0 + this.width - 6 - swatchW - (swatchW > 0 ? 4 : 0) - valueW;
				graphics.text(font, this.value, vx, textY,
					this.active ? ChestGuiStyle.VALUE_TEXT : ChestGuiStyle.TEXT_DISABLED, false);
			}
		}

		if (this.swatch != null) {
			int rgb = this.swatch.getAsInt();
			int sx = x0 + this.width - 6 - 14;
			int sy = y0 + (this.height - 10) / 2;
			graphics.fill(sx - 1, sy - 1, sx + 15, sy + 11, ChestGuiStyle.WOOD_DARK);
			graphics.fill(sx, sy, sx + 14, sy + 10, 0xFF000000 | rgb);
			// Tiny top gloss so the swatch reads as a chip, not a paint spill
			graphics.fill(sx, sy, sx + 14, sy + 1, ChestGuiStyle.withAlpha(0xFFFFFF, 0.30F));
		}
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput output) {
		output.add(NarratedElementType.TITLE, this.getMessage());
	}
}
