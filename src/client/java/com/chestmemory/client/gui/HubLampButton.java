package com.chestmemory.client.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.function.IntSupplier;

/**
 * The hub state as a small corner lamp: green reachable, red down, amber checking.
 * <p>
 * It replaced a full-width status strip that spent a whole row saying one word. The word
 * moved into the tooltip; the colour is read live every frame, so the lamp flips the
 * moment the state does, without a widget rebuild. Clicking it re-asks the hub — the
 * lamp doubles as the retry button the strip used to need a separate row for.
 */
public class HubLampButton extends AbstractWidget {
	private final IntSupplier colour;
	private final Runnable onPress;

	public HubLampButton(int x, int y, int size, Component tooltip, IntSupplier colour, Runnable onPress) {
		super(x, y, size, size, Component.translatable("screen.chestmemory.clan.hub"));
		this.colour = colour;
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
		int x0 = this.getX();
		int y0 = this.getY();
		int x1 = x0 + this.width;
		int y1 = y0 + this.height;
		boolean hover = this.isHoveredOrFocused();
		// Dark rim so the lamp reads against both the light panel and the header.
		graphics.fill(x0, y0, x1, y1, hover ? ChestGuiStyle.TEXT_LIGHT : ChestGuiStyle.WOOD_DARK);
		graphics.fill(x0 + 1, y0 + 1, x1 - 1, y1 - 1, this.colour.getAsInt());
		// Small top highlight so it reads as a lamp, not a paint chip.
		graphics.fill(x0 + 1, y0 + 1, x1 - 1, y0 + 2, ChestGuiStyle.withAlpha(0xFFFFFF, 0.35F));
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput output) {
		output.add(NarratedElementType.TITLE, this.getMessage());
	}
}
