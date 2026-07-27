package com.chestmemory.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.network.chat.Component;

/**
 * Small square gear button for the panel corner.
 */
public class SettingsIconButton extends AbstractWidget {
	private final Runnable onPress;

	public SettingsIconButton(int x, int y, int size, Component tooltip, Runnable onPress) {
		super(x, y, size, size, Component.translatable("screen.chestmemory.settings"));
		this.onPress = onPress;
		this.setTooltip(Tooltip.create(tooltip));
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

		// Wood plate
		graphics.fill(x0, y0, x1, y1, hover ? ChestGuiStyle.TEXT_LIGHT : ChestGuiStyle.WOOD_DARK);
		graphics.fill(x0 + 1, y0 + 1, x1 - 1, y1 - 1, hover ? ChestGuiStyle.ROW_WOOD_HOVER : ChestGuiStyle.ROW_WOOD);
		graphics.fill(x0 + 2, y0 + 2, x1 - 2, y1 - 2, 0xFF565656);

		// Simple gear icon (center hub + teeth)
		int cx = x0 + this.width / 2;
		int cy = y0 + this.height / 2;
		int metal = hover ? 0xFFFFFFFF : 0xFFE0E0E0;
		int dark = 0xFF1A1A1A;

		// Outer ring teeth (4 directions + diagonals as small blocks)
		int o = 5;
		graphics.fill(cx - 1, cy - o, cx + 2, cy - o + 3, metal); // N
		graphics.fill(cx - 1, cy + o - 2, cx + 2, cy + o + 1, metal); // S
		graphics.fill(cx - o, cy - 1, cx - o + 3, cy + 2, metal); // W
		graphics.fill(cx + o - 2, cy - 1, cx + o + 1, cy + 2, metal); // E
		// Diagonals
		graphics.fill(cx - 4, cy - 4, cx - 1, cy - 1, metal);
		graphics.fill(cx + 2, cy - 4, cx + 5, cy - 1, metal);
		graphics.fill(cx - 4, cy + 2, cx - 1, cy + 5, metal);
		graphics.fill(cx + 2, cy + 2, cx + 5, cy + 5, metal);

		// Hub ring
		graphics.fill(cx - 3, cy - 3, cx + 4, cy + 4, metal);
		graphics.fill(cx - 1, cy - 1, cx + 2, cy + 2, dark);
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput output) {
		output.add(NarratedElementType.TITLE, this.getMessage());
	}
}
