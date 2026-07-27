package com.chestmemory.client.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.network.chat.Component;

/**
 * Small trash-can icon: clear remembered item memory (with external confirm logic).
 */
public class ClearMemoryIconButton extends AbstractWidget {
	private final Runnable onPress;
	private boolean confirmMode;

	public ClearMemoryIconButton(int x, int y, int size, Component tooltip, Runnable onPress) {
		super(x, y, size, size, Component.translatable("screen.chestmemory.clear_world"));
		this.onPress = onPress;
		this.setTooltip(Tooltip.create(tooltip));
	}

	public void setConfirmMode(boolean confirm) {
		this.confirmMode = confirm;
		// Visible state change on first click: red plate (render), "Confirm?" label + tooltip
		this.setMessage(Component.translatable(
			confirm
				? "screen.chestmemory.clear_world_confirm"
				: "screen.chestmemory.clear_world"
		));
		this.setTooltip(Tooltip.create(Component.translatable(
			confirm
				? "screen.chestmemory.clear_memory.tooltip_confirm"
				: "screen.chestmemory.clear_memory.tooltip"
		)));
	}

	public boolean isConfirmMode() {
		return confirmMode;
	}

	@Override
	public void onClick(MouseButtonEvent event, boolean doubleClick) {
		if (this.onPress != null) {
			this.onPress.run();
		}
	}

	@Override
	public void playDownSound(SoundManager handler) {
		super.playDownSound(handler);
	}

	@Override
	protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		boolean hover = this.isHoveredOrFocused();
		int x0 = this.getX();
		int y0 = this.getY();
		int x1 = x0 + this.width;
		int y1 = y0 + this.height;

		// Plate — reddens only while waiting for the confirm click
		graphics.fill(x0, y0, x1, y1, hover ? ChestGuiStyle.TEXT_LIGHT : ChestGuiStyle.WOOD_DARK);
		int fill = confirmMode
			? (hover ? 0xFFB05040 : 0xFF9A4636)
			: (hover ? ChestGuiStyle.ROW_WOOD_HOVER : ChestGuiStyle.ROW_WOOD);
		graphics.fill(x0 + 1, y0 + 1, x1 - 1, y1 - 1, fill);
		graphics.fill(x0 + 2, y0 + 2, x1 - 2, y1 - 2, confirmMode ? 0xFF5A2018 : 0xFF565656);

		// Trash can icon
		int cx = x0 + this.width / 2;
		int cy = y0 + this.height / 2;
		int metal = confirmMode
			? (hover ? 0xFFFFE0D0 : 0xFFFFC0A0)
			: (hover ? 0xFFFFFFFF : 0xFFE0E0E0);
		int dark = 0xFF1A1A1A;

		// Lid
		graphics.fill(cx - 5, cy - 5, cx + 5, cy - 3, metal);
		graphics.fill(cx - 2, cy - 6, cx + 2, cy - 5, metal);
		// Body
		graphics.fill(cx - 4, cy - 3, cx + 4, cy + 5, metal);
		graphics.fill(cx - 3, cy - 2, cx + 3, cy + 4, dark);
		// Vertical lines on can
		graphics.fill(cx - 1, cy - 1, cx, cy + 3, metal);
		graphics.fill(cx + 1, cy - 1, cx + 2, cy + 3, metal);
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput output) {
		output.add(NarratedElementType.TITLE, this.getMessage());
	}
}
