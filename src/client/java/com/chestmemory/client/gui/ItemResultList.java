package com.chestmemory.client.gui;

import com.chestmemory.client.data.ItemSummary;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.function.Consumer;

public class ItemResultList extends ObjectSelectionList<ItemResultList.Entry> {
	private final Consumer<ItemSummary> onSelect;

	public ItemResultList(Minecraft minecraft, int width, int height, int y, Consumer<ItemSummary> onSelect) {
		super(minecraft, width, height, y, 28);
		this.onSelect = onSelect;
	}

	public void setItems(List<ItemSummary> items) {
		this.clearEntries();
		for (ItemSummary summary : items) {
			this.addEntry(new Entry(summary));
		}
	}

	@Override
	public int getRowWidth() {
		return Math.max(40, this.width - 14);
	}

	@Override
	protected boolean entriesCanBeSelected() {
		return true;
	}

	@Override
	protected void extractListBackground(GuiGraphicsExtractor graphics) {
		// Soft wood-tinted inset instead of default menu noise
		graphics.fill(this.getX(), this.getY(), this.getRight(), this.getBottom(), 0x552A1A0E);
		graphics.fill(this.getX() + 1, this.getY() + 1, this.getRight() - 1, this.getBottom() - 1, 0x44C6C6C6);
	}

	@Override
	protected void extractListSeparators(GuiGraphicsExtractor graphics) {
		// No harsh separators — chest panel already frames the list
	}

	@Override
	public void setSelected(Entry selected) {
		super.setSelected(selected);
		if (selected != null) {
			this.onSelect.accept(selected.summary);
		}
	}

	public class Entry extends ObjectSelectionList.Entry<Entry> {
		private final ItemSummary summary;
		private final ItemStack icon;
		private final String countText;

		public Entry(ItemSummary summary) {
			this.summary = summary;
			// Put count ON the stack so vanilla itemDecorations draws it reliably
			ItemStack stack = resolveStack(summary.itemId());
			int shown = Math.min(Math.max(summary.totalCount(), 1), 999);
			this.icon = stack.copyWithCount(shown);
			this.countText = formatCount(summary.totalCount());
		}

		public ItemSummary summary() {
			return summary;
		}

		@Override
		public Component getNarration() {
			return Component.literal(displayName() + " x" + summary.totalCount());
		}

		@Override
		public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
			ItemResultList.this.setSelected(this);
			return true;
		}

		@Override
		public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float a) {
			int rowX = this.getX();
			int rowY = this.getY();
			int rowW = this.getWidth();
			int rowH = this.getHeight();

			// Row background like a chest inventory line
			graphics.fill(rowX + 1, rowY + 1, rowX + rowW - 1, rowY + rowH - 1, hovered ? ChestGuiStyle.ROW_HOVER : ChestGuiStyle.ROW_BG);

			// Vanilla slot under the item
			int slotX = rowX + 4;
			int slotY = rowY + (rowH - 18) / 2;
			ChestGuiStyle.drawSlot(graphics, slotX, slotY);

			// Item + vanilla stack count decoration
			int itemX = slotX + 1;
			int itemY = slotY + 1;
			graphics.item(this.icon, itemX, itemY);
			// Always draw our own count string (vanilla decorations hide large / odd counts)
			String stackLabel = countText;
			var font = ItemResultList.this.minecraft.font;
			// bottom-right of the 16x16 icon, like vanilla
			int cx = itemX + 17 - font.width(stackLabel);
			int cy = itemY + 9;
			graphics.text(font, stackLabel, cx + 1, cy + 1, ChestGuiStyle.TEXT_COUNT_SHADOW, false);
			graphics.text(font, stackLabel, cx, cy, ChestGuiStyle.TEXT_COUNT, false);

			// Name
			String name = displayName();
			int textLeft = slotX + 22;
			int badgeReserve = Math.max(36, font.width(countText) + 14);
			int nameMax = rowX + rowW - badgeReserve - textLeft - 8;
			if (nameMax > 16 && font.width(name) > nameMax) {
				while (name.length() > 3 && font.width(name + "…") > nameMax) {
					name = name.substring(0, name.length() - 1);
				}
				name = name + "…";
			}
			graphics.text(font, name, textLeft, rowY + 5, ChestGuiStyle.TEXT_BODY, false);

			// Secondary line: stacks · containers · distance (nearby mode)
			int per = Math.max(1, this.icon.getMaxStackSize());
			String stacks = summary.fullStacks(per) > 0
				? summary.fullStacks(per) + "×" + per + (summary.remainder(per) > 0 ? "+" + summary.remainder(per) : "")
				: ("×" + summary.totalCount());
			String meta = stacks + " · " + summary.containerCount()
				+ (summary.containerCount() == 1 ? " cont." : " cont.");
			if (summary.hasDistance()) {
				meta = meta + " · " + (int) summary.nearestDistance() + "m";
			}
			graphics.text(font, meta, textLeft, rowY + 15, ChestGuiStyle.TEXT_MUTED, false);

			// Gold count badge on the right
			ChestGuiStyle.drawCountBadge(graphics, font, countText, rowX + rowW - 6, rowY + (rowH - 16) / 2);
		}

		private String displayName() {
			if (!this.icon.isEmpty()) {
				return this.icon.getHoverName().getString();
			}
			return summary.itemId();
		}

		private static String formatCount(int count) {
			if (count >= 1_000_000) {
				return String.format("%.1fM", count / 1_000_000.0);
			}
			if (count >= 10_000) {
				return String.format("%.1fk", count / 1000.0);
			}
			return String.valueOf(count);
		}

		private static ItemStack resolveStack(String itemId) {
			Identifier id = Identifier.tryParse(itemId);
			if (id == null) {
				return new ItemStack(Items.CHEST);
			}
			Item item = BuiltInRegistries.ITEM.getValue(id);
			if (item == Items.AIR) {
				return new ItemStack(Items.CHEST);
			}
			return new ItemStack(item);
		}
	}
}
