package com.chestmemory.client.gui;

import com.chestmemory.client.data.ModSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Live preview of the slot-count settings: three real slots with real items and the
 * counts drawn through the exact same renderer the panel grid uses — change the size,
 * style or colour above and this row shows precisely what the grid will look like.
 */
public class SlotCountPreviewRow extends AbstractWidget {
	private static final String[] SAMPLE_COUNTS = {"7", "999", "1.2M"};

	/** Resolved lazily — item registry access belongs after client bootstrap. */
	private ItemStack[] samples;

	public SlotCountPreviewRow(int x, int y, int width, int height, Component label) {
		super(x, y, width, height, label);
	}

	private ItemStack[] samples() {
		if (this.samples == null) {
			this.samples = new ItemStack[]{
				new ItemStack(Items.DIAMOND),
				new ItemStack(Items.IRON_INGOT),
				new ItemStack(Items.OAK_LOG)
			};
		}
		return this.samples;
	}

	@Override
	public void onClick(MouseButtonEvent event, boolean doubleClick) {
		// Purely informative — nothing to press.
	}

	@Override
	protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		int x0 = this.getX();
		int y0 = this.getY();

		// Recessed tray, not a button: this row shows, it does not do.
		ChestGuiStyle.drawGridTray(graphics, x0, y0, this.width, this.height);

		var font = Minecraft.getInstance().font;
		int textY = y0 + (this.height - font.lineHeight) / 2 + 1;

		ItemStack[] stacks = samples();
		int slots = stacks.length;
		int slotsW = slots * ChestGuiStyle.GRID_SLOT + (slots - 1);
		int slotX = x0 + this.width - 6 - slotsW;
		int slotY = y0 + (this.height - ChestGuiStyle.GRID_SLOT) / 2;

		String label = ChestGuiStyle.ellipsize(
			font, this.getMessage().getString(), slotX - x0 - 12
		);
		graphics.text(font, label, x0 + 7, textY, ChestGuiStyle.TEXT_MUTED, false);

		ModSettings s = ModSettings.get();
		for (int i = 0; i < slots; i++) {
			int sx = slotX + i * (ChestGuiStyle.GRID_SLOT + 1);
			ChestGuiStyle.drawSlot(graphics, sx, slotY);
			graphics.item(stacks[i], sx + 1, slotY + 1);
			ChestGuiStyle.drawSlotCountStyled(
				graphics, font, SAMPLE_COUNTS[i], sx, slotY,
				0xFF000000 | s.slotCountColor(),
				s.slotCountScalePct() / 100F,
				s.slotCountStyle()
			);
		}
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput output) {
		output.add(NarratedElementType.TITLE, this.getMessage());
	}
}
