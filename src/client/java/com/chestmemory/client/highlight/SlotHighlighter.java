package com.chestmemory.client.highlight;

import com.chestmemory.client.data.ModSettings;
import com.chestmemory.client.litematica.BuildGatherSession;
import com.chestmemory.client.mixin.AbstractContainerScreenAccessor;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.MenuAccess;
import net.minecraft.util.ARGB;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Soft green highlight on matching slots (under items/tooltips).
 * In gather mode shows how many more to take from each container slot.
 */
public final class SlotHighlighter {
	/**
	 * Memoised {@link BuildGatherSession#remainingNeed(String)}: the call still walks all
	 * 41 player-inventory slots, and this render runs every frame while a container is
	 * open during a gather — 60+ inventory walks a second for a number that feeds a badge.
	 * 250ms matches the staleness the gather's own material snapshot already has, so the
	 * badge is never more out of date than the HUD.
	 */
	private static @org.jspecify.annotations.Nullable String needCacheItemId;
	private static int needCacheValue;
	private static long needCacheAtMillis;
	private static final long NEED_CACHE_MAX_AGE_MS = 250L;

	private SlotHighlighter() {
	}

	public static void render(
		AbstractContainerScreen<?> screen,
		GuiGraphicsExtractor graphics,
		int mouseX,
		int mouseY
	) {
		if (!ModSettings.get().highlightSlots()) {
			return;
		}
		String itemId = ChestHighlighter.getHighlightedItemId();
		if (itemId == null) {
			return;
		}
		if (!(screen instanceof MenuAccess<?> access)) {
			return;
		}

		AbstractContainerMenu menu = access.getMenu();
		AbstractContainerScreenAccessor acc = (AbstractContainerScreenAccessor) screen;
		int left = acc.chestmemory$getLeftPos();
		int top = acc.chestmemory$getTopPos();

		long now = System.currentTimeMillis();
		float pulse = 0.8F + 0.2F * (float) Math.sin(now / 280.0);
		float remain = ChestHighlighter.remainingSeconds();
		float fade = remain < 4.0F ? Math.max(0.25F, remain / 4.0F) : 1.0F;
		// Softer fill so item icons and tooltips stay readable
		int fillA = (int) (55 * pulse * fade);
		int borderA = (int) (180 * pulse * fade);
		int slotRgb = ModSettings.get().slotColor();
		int bright = com.chestmemory.client.data.ColorPalette.brighten(slotRgb, 1.35f);
		int fill = ARGB.color(fillA,
			com.chestmemory.client.data.ColorPalette.r(slotRgb),
			com.chestmemory.client.data.ColorPalette.g(slotRgb),
			com.chestmemory.client.data.ColorPalette.b(slotRgb));
		int border = ARGB.color(borderA,
			com.chestmemory.client.data.ColorPalette.r(bright),
			com.chestmemory.client.data.ColorPalette.g(bright),
			com.chestmemory.client.data.ColorPalette.b(bright));

		var font = screen.getFont();

		int stillNeed = -1;
		if (BuildGatherSession.isActive()) {
			if (itemId.equals(needCacheItemId) && now - needCacheAtMillis < NEED_CACHE_MAX_AGE_MS) {
				stillNeed = needCacheValue;
			} else {
				stillNeed = BuildGatherSession.remainingNeed(itemId);
				needCacheItemId = itemId;
				needCacheValue = stillNeed;
				needCacheAtMillis = now;
			}
		}
		int remainingToMark = stillNeed > 0 ? stillNeed : 0;

		for (Slot slot : menu.slots) {
			if (!slot.isActive()) {
				continue;
			}
			ItemStack stack = slot.getItem();
			if (stack.isEmpty()) {
				continue;
			}
			if (!com.chestmemory.client.data.ItemStackKeys.matches(stack, itemId)) {
				continue;
			}

			int x = left + slot.x;
			int y = top + slot.y;

			// Don't paint over the slot under the cursor — vanilla tooltip sits there
			boolean mouseOverSlot = mouseX >= x && mouseX < x + 16 && mouseY >= y && mouseY < y + 16;
			if (mouseOverSlot) {
				// Only a thin corner mark so description stays clear
				graphics.fill(x, y, x + 3, y + 3, border);
				continue;
			}

			boolean containerSlot = slot.container != null
				&& !(slot.container instanceof net.minecraft.world.entity.player.Inventory);

			graphics.fill(x, y, x + 16, y + 16, fill);
			graphics.outline(x - 1, y - 1, 18, 18, border);

			// Take-count badge only on container slots (not player inv), not on hovered
			if (stillNeed > 0 && containerSlot && remainingToMark > 0) {
				int take = Math.min(stack.getCount(), remainingToMark);
				remainingToMark -= take;
				String badge = String.valueOf(take);
				int tw = font.width(badge);
				int bx = x + 16 - tw;
				int by = y - 1;
				graphics.fill(bx - 1, by, bx + tw + 1, by + 9, 0xCC1A1208);
				graphics.text(font, badge, bx + 1, by + 1, 0xFF000000, false);
				int badgeRgb = ModSettings.get().hudAccentColor();
				graphics.text(font, badge, bx, by, 0xFF000000 | badgeRgb, false);
			}
			// Do not re-draw stack size — vanilla already does; re-drawing caused overlap mess
		}

		// Top-of-GUI hint: still need total
		if (stillNeed > 0) {
			String hint = "↓ " + stillNeed;
			int hx = left + 8;
			int hy = top - 10;
			if (hy < 2) {
				hy = top + 2;
			}
			graphics.fill(hx - 2, hy - 1, hx + font.width(hint) + 4, hy + 10, 0xCC101018);
			graphics.text(font, hint, hx + 1, hy + 1, 0xFF000000, false);
			int hintRgb = ModSettings.get().hudAccentColor();
			graphics.text(font, hint, hx, hy, 0xFF000000 | hintRgb, false);
		}
	}
}
