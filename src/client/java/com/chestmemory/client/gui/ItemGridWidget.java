package com.chestmemory.client.gui;

import com.chestmemory.client.data.ItemSummary;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Compact inventory-style grid: fixed 18×18 slots (vanilla size), no stretched cells.
 */
public class ItemGridWidget extends AbstractWidget {
	/** Vanilla slot sprite size. */
	public static final int SLOT = 18;
	/** Gap between slots. */
	public static final int GAP = 1;
	/** Pitch = slot + gap. */
	public static final int PITCH = SLOT + GAP;

	private final Minecraft minecraft;
	private final Consumer<ItemSummary> onSelect;
	private List<ItemSummary> items = List.of();
	/**
	 * Icons for the current list. Resolving a key builds an ItemStack and hits the item
	 * (and, for enchanted keys, enchantment) registry — far too expensive to redo for
	 * every visible slot on every frame.
	 */
	private final Map<String, ItemStack> iconCache = new HashMap<>();
	private int scrollRow;
	/** True while the scrollbar is being dragged. */
	private boolean draggingScrollbar;
	private int hoveredIndex = -1;

	public ItemGridWidget(Minecraft minecraft, int x, int y, int width, int height, Consumer<ItemSummary> onSelect) {
		super(x, y, width, height, Component.translatable("screen.chestmemory.grid"));
		this.minecraft = minecraft;
		this.onSelect = onSelect;
	}

	public void setItems(List<ItemSummary> items) {
		this.items = items != null ? items : List.of();
		this.scrollRow = 0;
		this.hoveredIndex = -1;
		this.iconCache.clear();
		for (ItemSummary s : this.items) {
			this.iconCache.computeIfAbsent(s.itemId(), com.chestmemory.client.data.ItemStackKeys::toStack);
		}
	}

	/** How many columns fit without stretching slots. */
	private int cols() {
		int inner = Math.max(SLOT, this.width - 4);
		return Math.max(1, (inner + GAP) / PITCH);
	}

	/**
	 * Only fully visible rows (no clipped bottom row).
	 * Layout: 2px top pad, then rows of SLOT with GAP between them.
	 */
	private int rowsVisible() {
		int space = this.height - 2; // from gridOriginY offset
		if (space < SLOT) {
			return space > 0 ? 1 : 0;
		}
		// first row uses SLOT; each extra row needs PITCH
		return 1 + Math.max(0, (space - SLOT) / PITCH);
	}

	private int maxScrollRow() {
		int totalRows = Mth.ceil(this.items.size() / (float) cols());
		return Math.max(0, totalRows - rowsVisible());
	}

	/** Top-left of the slot grid, centered horizontally in the plate. */
	private int gridOriginX() {
		int c = cols();
		int used = c * SLOT + (c - 1) * GAP;
		return this.getX() + Math.max(2, (this.width - used) / 2);
	}

	private int gridOriginY() {
		return this.getY() + 2;
	}

	private int indexAt(double mouseX, double mouseY) {
		if (!this.isMouseOver(mouseX, mouseY)) {
			return -1;
		}
		int ox = gridOriginX();
		int oy = gridOriginY();
		int localX = (int) mouseX - ox;
		int localY = (int) mouseY - oy;
		if (localX < 0 || localY < 0) {
			return -1;
		}
		int col = localX / PITCH;
		int row = localY / PITCH;
		// Only hit the actual 18×18 slot, not the gap
		int inSlotX = localX % PITCH;
		int inSlotY = localY % PITCH;
		if (inSlotX >= SLOT || inSlotY >= SLOT) {
			return -1;
		}
		if (col < 0 || col >= cols() || row < 0 || row >= rowsVisible()) {
			return -1;
		}
		int index = (scrollRow + row) * cols() + col;
		return index >= 0 && index < items.size() ? index : -1;
	}

	@Override
	protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		// Dark plate
		graphics.fill(this.getX(), this.getY(), this.getX() + this.width, this.getY() + this.height, 0xFF1A120A);
		graphics.fill(this.getX() + 1, this.getY() + 1, this.getX() + this.width - 1, this.getY() + this.height - 1, 0xFFC6C6C6);

		int c = cols();
		int rv = rowsVisible();
		int ox = gridOriginX();
		int oy = gridOriginY();
		int visible = rv * c;
		int startIndex = scrollRow * c;
		this.hoveredIndex = indexAt(mouseX, mouseY);

		for (int i = 0; i < visible; i++) {
			int index = startIndex + i;
			int col = i % c;
			int row = i / c;
			int slotX = ox + col * PITCH;
			int slotY = oy + row * PITCH;

			ChestGuiStyle.drawSlot(graphics, slotX, slotY);

			if (index >= items.size()) {
				continue;
			}

			ItemSummary summary = items.get(index);
			boolean hovered = index == hoveredIndex;
			if (hovered) {
				graphics.fill(slotX + 1, slotY + 1, slotX + 17, slotY + 17, 0x66FFFFFF);
			}

			ItemStack icon = resolveStack(summary.itemId());
			graphics.item(icon, slotX + 1, slotY + 1);

			// Build mode: tint by gather state
			// red = none in chests, orange = partial, green = enough in chests, blue-gray = done (inv OK)
			if (summary.isBuildNeed()) {
				if (summary.neededForBuild() <= 0) {
					graphics.fill(slotX + 1, slotY + 1, slotX + 17, slotY + 17, 0x443088C0);
				} else if (summary.totalCount() <= 0) {
					graphics.fill(slotX + 1, slotY + 1, slotX + 17, slotY + 17, 0x55FF4040);
				} else if (summary.stillShort() > 0) {
					graphics.fill(slotX + 1, slotY + 1, slotX + 17, slotY + 17, 0x55FFAA20);
				} else {
					graphics.fill(slotX + 1, slotY + 1, slotX + 17, slotY + 17, 0x4430E060);
				}
			}

			// Clan claims: visible overlay so others don't pick the same item
			boolean clan = com.chestmemory.client.clan.ClanSessionManager.isInSession();
			boolean mine = clan && com.chestmemory.client.clan.ClanSessionManager.isClaimedByMe(
				this.minecraft, summary.itemId());
			boolean other = clan && com.chestmemory.client.clan.ClanSessionManager.isClaimedByOther(
				this.minecraft, summary.itemId());
			if (other) {
				// Magenta = taken by teammate
				graphics.fill(slotX + 1, slotY + 1, slotX + 17, slotY + 17, 0x66C040E0);
			} else if (mine) {
				// Gold = your claim
				graphics.fill(slotX + 1, slotY + 1, slotX + 17, slotY + 17, 0x55E0C040);
			}

			// Compact + slightly scaled count (fits in 18px slot without black plate)
			String countText = summary.isBuildNeed()
				? formatCount(summary.neededForBuild())
				: formatCount(summary.totalCount());
			var font = this.minecraft.font;
			int countColor;
			if (!summary.isBuildNeed()) {
				countColor = 0xFFFFFFFF;
			} else if (summary.neededForBuild() <= 0) {
				countColor = 0xFF88CCFF; // done
			} else if (summary.stillShort() > 0) {
				countColor = 0xFFFFEE66;
			} else {
				countColor = 0xFF80FFA0; // ready to take from chests
			}
			drawSlotCount(graphics, font, countText, slotX, slotY, countColor);

			// First letter of claimer in top-left of slot
			if (mine || other) {
				String badge = com.chestmemory.client.clan.ClanSessionManager.claimBadge(summary.itemId());
				if (badge != null) {
					int bc = mine ? 0xFFFFEE88 : 0xFFFFAAFF;
					graphics.text(font, badge, slotX + 2, slotY + 1, 0xE0000000, false);
					graphics.text(font, badge, slotX + 1, slotY, bc, false);
				}
			}
		}

		if (maxScrollRow() > 0) {
			int barH = Math.max(10, this.height * rowsVisible() / (rowsVisible() + maxScrollRow()));
			int barY = this.getY() + (int) ((this.height - barH) * (scrollRow / (float) maxScrollRow()));
			graphics.fill(this.getX() + this.width - 3, this.getY() + 2, this.getX() + this.width - 1, this.getY() + this.height - 2, 0x88000000);
			graphics.fill(this.getX() + this.width - 3, barY, this.getX() + this.width - 1, barY + barH, 0xFFE0C040);
		}

		if (hoveredIndex >= 0 && hoveredIndex < items.size()) {
			ItemSummary s = items.get(hoveredIndex);
			List<Component> lines = new ArrayList<>();
			// Title: the renamed label stands on its own line in italics above the base
			// item name, the way vanilla shows a renamed item.
			String custom = com.chestmemory.client.data.ItemStackKeys.customNameOf(s.itemId());
			if (custom != null) {
				lines.add(Component.literal(custom)
					.withStyle(net.minecraft.ChatFormatting.YELLOW, net.minecraft.ChatFormatting.ITALIC));
				// Base item name: build a clean stack so the custom name is not reapplied.
				lines.add(new ItemStack(resolveStack(s.itemId()).getItem()).getHoverName()
					.copy().withStyle(net.minecraft.ChatFormatting.GRAY));
			} else {
				lines.add(Component.literal(
					com.chestmemory.client.data.ChestMemoryStorage.itemDisplayName(s.itemId())
				).withStyle(net.minecraft.ChatFormatting.WHITE));
			}
			if (s.isBuildNeed()) {
				if (s.schematicTotal() > 0) {
					lines.add(Component.translatable("screen.chestmemory.tooltip.build_total", s.schematicTotal()));
				}
				if (s.neededForBuild() <= 0) {
					lines.add(Component.translatable("screen.chestmemory.tooltip.build_done"));
				} else {
					lines.add(Component.translatable("screen.chestmemory.tooltip.build_needed", s.neededForBuild()));
				}
				if (s.inPlayer() >= 0) {
					lines.add(Component.translatable("screen.chestmemory.tooltip.build_in_player", s.inPlayer()));
				}
				int staging = com.chestmemory.client.data.ChestMemoryStorage.get().countInStaging(s.itemId());
				if (staging > 0) {
					lines.add(Component.translatable("screen.chestmemory.tooltip.build_in_staging", staging));
				}
				lines.add(Component.translatable("screen.chestmemory.tooltip.build_in_chests", s.totalCount()));
				if (s.neededForBuild() > 0 && s.canGatherFromChests() > 0) {
					lines.add(Component.translatable("screen.chestmemory.tooltip.build_can_gather", s.canGatherFromChests()));
				}
				if (s.neededForBuild() > 0) {
					if (s.totalCount() <= 0) {
						lines.add(Component.translatable("screen.chestmemory.tooltip.build_none_chests"));
					} else if (s.stillShort() > 0) {
						lines.add(Component.translatable("screen.chestmemory.tooltip.build_short", s.stillShort()));
					} else {
						lines.add(Component.translatable("screen.chestmemory.tooltip.build_enough"));
					}
				}
			} else {
				lines.add(gray(Component.translatable("screen.chestmemory.tooltip.total", s.totalCount())));
				// Use the item's real stack size — 16 for pearls, 1 for tools, not always 64.
				int per = Math.max(1, resolveStack(s.itemId()).getMaxStackSize());
				if (s.fullStacks(per) > 0) {
					lines.add(gray(Component.translatable(
						"screen.chestmemory.tooltip.stacks",
						s.fullStacks(per), per, s.remainder(per)
					)));
				} else {
					lines.add(gray(Component.translatable("screen.chestmemory.tooltip.less_than_stack", s.totalCount())));
				}
			}
			lines.add(gray(Component.translatable("screen.chestmemory.tooltip.containers", s.containerCount())));
			if (s.hasDistance()) {
				lines.add(gray(Component.translatable("screen.chestmemory.tooltip.nearest", (int) s.nearestDistance())));
			}
			if (s.isBuildNeed() && s.neededForBuild() > 0) {
				lines.add(Component.translatable("screen.chestmemory.tooltip.build_click"));
			}
			if (com.chestmemory.client.clan.ClanSessionManager.isInSession()) {
				if (com.chestmemory.client.clan.ClanSessionManager.isClaimedByMe(this.minecraft, s.itemId())) {
					lines.add(Component.translatable("screen.chestmemory.tooltip.clan_claim_me"));
				} else if (com.chestmemory.client.clan.ClanSessionManager.isClaimedByOther(this.minecraft, s.itemId())) {
					String who = com.chestmemory.client.clan.ClanSessionManager.claimName(s.itemId());
					lines.add(Component.translatable(
						"screen.chestmemory.tooltip.clan_claim_other",
						who != null ? who : "?"
					));
				} else {
					lines.add(Component.translatable("screen.chestmemory.tooltip.clan_claim_free"));
				}
				int cd = com.chestmemory.client.clan.ClanSessionManager.clanDelivered(s.itemId());
				if (cd > 0) {
					lines.add(Component.translatable("screen.chestmemory.tooltip.clan_delivered", cd));
				}
			}
			graphics.setTooltipForNextFrame(this.minecraft.font, lines, java.util.Optional.empty(), mouseX, mouseY);
		}
	}

	/** True when the pointer is over the scrollbar strip (with a little slack). */
	private boolean isOverScrollbar(double mouseX) {
		return maxScrollRow() > 0 && mouseX >= this.getX() + this.width - 8;
	}

	/** Map a pointer position on the track to a scroll row. */
	private void scrollToPointer(double mouseY) {
		int maxRow = maxScrollRow();
		if (maxRow <= 0) {
			return;
		}
		int barH = Math.max(10, this.height * rowsVisible() / (rowsVisible() + maxRow));
		int trackH = Math.max(1, this.height - barH);
		double rel = (mouseY - this.getY() - barH / 2.0) / trackH;
		this.scrollRow = Mth.clamp((int) Math.round(rel * maxRow), 0, maxRow);
	}

	@Override
	public void onClick(MouseButtonEvent event, boolean doubleClick) {
		// Clicking the scrollbar jumps there instead of selecting whatever slot is behind.
		if (isOverScrollbar(event.x())) {
			this.draggingScrollbar = true;
			scrollToPointer(event.y());
			return;
		}
		int index = indexAt(event.x(), event.y());
		if (index >= 0 && index < items.size()) {
			this.onSelect.accept(items.get(index));
		}
	}

	@Override
	public void onRelease(MouseButtonEvent event) {
		this.draggingScrollbar = false;
		super.onRelease(event);
	}

	@Override
	protected void onDrag(MouseButtonEvent event, double dragX, double dragY) {
		// The bar was paint-only: the wheel scrolled but dragging it did nothing.
		if (this.draggingScrollbar) {
			scrollToPointer(event.y());
			return;
		}
		super.onDrag(event, dragX, dragY);
	}

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		if (!this.isMouseOver(x, y) || maxScrollRow() <= 0) {
			return false;
		}
		if (scrollY > 0) {
			scrollRow = Math.max(0, scrollRow - 1);
		} else if (scrollY < 0) {
			scrollRow = Math.min(maxScrollRow(), scrollRow + 1);
		}
		return true;
	}

	@Override
	public void playDownSound(SoundManager soundManager) {
		super.playDownSound(soundManager);
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput output) {
		output.add(NarratedElementType.TITLE, Component.translatable("screen.chestmemory.grid"));
	}

	/**
	 * Draw count bottom-right in the slot, scaled down so 3–4 digits fit cleanly.
	 * No black plate — shadow only for readability.
	 */
	private static void drawSlotCount(
		GuiGraphicsExtractor graphics,
		net.minecraft.client.gui.Font font,
		String countText,
		int slotX,
		int slotY,
		int countColor
	) {
		float scale = 0.72f;
		int textW = font.width(countText);
		// Bottom-right of the 16px icon area (slot is 18 with 1px border)
		float drawX = slotX + 17 - textW * scale;
		float drawY = slotY + 17 - 7.2f * scale;
		if (drawX < slotX + 1) {
			drawX = slotX + 1;
		}
		graphics.pose().pushMatrix();
		graphics.pose().translate(drawX, drawY);
		graphics.pose().scale(scale, scale);
		// Soft shadow (no dark plate)
		graphics.text(font, countText, 1, 1, 0xE0000000, false);
		graphics.text(font, countText, 0, 0, countColor, false);
		graphics.pose().popMatrix();
	}

	/** Compact counts for 18px slots: 999, 1k, 1.5k, 12k, 1.2M */
	private static String formatCount(int count) {
		if (count < 0) {
			return "0";
		}
		if (count >= 1_000_000) {
			double m = count / 1_000_000.0;
			return m >= 10 ? String.format("%.0fM", m) : String.format("%.1fM", m);
		}
		// From 1000: always compact so full "1234" never overflows the slot
		if (count >= 1000) {
			double k = count / 1000.0;
			if (k >= 100) {
				return String.format("%.0fk", k);
			}
			if (k >= 10) {
				// 12k / 12.5k — prefer short
				if (Math.abs(k - Math.rint(k)) < 0.05) {
					return String.format("%.0fk", k);
				}
				return String.format("%.0fk", k);
			}
			if (Math.abs(k - Math.rint(k)) < 0.05) {
				return String.format("%.0fk", k);
			}
			return String.format("%.1fk", k);
		}
		return String.valueOf(count);
	}

	/** Muted tone for the tooltip's secondary lines, so the title stands out. */
	private static Component gray(Component c) {
		return c.copy().withStyle(net.minecraft.ChatFormatting.GRAY);
	}

	private ItemStack resolveStack(String itemId) {
		ItemStack cached = this.iconCache.get(itemId);
		if (cached != null) {
			return cached;
		}
		// Not in the current list (defensive) — resolve and remember.
		ItemStack stack = com.chestmemory.client.data.ItemStackKeys.toStack(itemId);
		this.iconCache.put(itemId, stack);
		return stack;
	}
}
