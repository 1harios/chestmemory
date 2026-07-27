package com.chestmemory.client.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * Small square pencil button for the panel corner — the gather's settings entry,
 * mirroring the gear the main screen keeps in its own corner.
 */
public class PencilIconButton extends AbstractWidget {
	private final Runnable onPress;

	public PencilIconButton(int x, int y, int size, Component tooltip, Runnable onPress) {
		super(x, y, size, size, Component.translatable("screen.chestmemory.clan.settings_btn"));
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
	protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		boolean hover = this.isHoveredOrFocused();
		int x0 = this.getX();
		int y0 = this.getY();
		int x1 = x0 + this.width;
		int y1 = y0 + this.height;

		// Same plate as the gear button, so the two corners read as one family.
		graphics.fill(x0, y0, x1, y1, hover ? ChestGuiStyle.TEXT_LIGHT : ChestGuiStyle.WOOD_DARK);
		graphics.fill(x0 + 1, y0 + 1, x1 - 1, y1 - 1, hover ? ChestGuiStyle.ROW_WOOD_HOVER : ChestGuiStyle.ROW_WOOD);
		graphics.fill(x0 + 2, y0 + 2, x1 - 2, y1 - 2, 0xFF565656);

		int cx = x0 + this.width / 2;
		int cy = y0 + this.height / 2;
		int metal = hover ? 0xFFFFFFFF : 0xFFE0E0E0;
		int tip = 0xFF1A1A1A;

		// Pencil: body along the diagonal, dark tip at the lower-left.
		graphics.fill(cx + 2, cy - 5, cx + 5, cy - 2, metal); // eraser end
		graphics.fill(cx, cy - 3, cx + 3, cy, metal);         // upper body
		graphics.fill(cx - 2, cy - 1, cx + 1, cy + 2, metal); // lower body
		graphics.fill(cx - 4, cy + 1, cx - 1, cy + 4, metal); // collar
		graphics.fill(cx - 5, cy + 3, cx - 3, cy + 5, tip);   // tip
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput output) {
		output.add(NarratedElementType.TITLE, this.getMessage());
	}
}
