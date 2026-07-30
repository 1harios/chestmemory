package com.chestmemory.client.highlight;

import com.chestmemory.client.data.ModSettings;
import com.chestmemory.client.litematica.BuildGatherSession;
import com.chestmemory.client.mixin.AbstractContainerScreenAccessor;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
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
		boolean wantTint = ModSettings.get().highlightSlots();
		boolean wantHint = ModSettings.get().gatherSlotHint();
		if (!wantTint && !wantHint) {
			return;
		}
		// Two sources for "which item are we looking at", and they have different lifetimes.
		//
		// The highlight is a timer: it glows the chests in the world for highlightSeconds and
		// then stops, which is right for a one-off "where is my redstone". The gather target is
		// not a timer at all — it lasts until the material is collected. Everything here used to
		// hang off the highlight alone, so half a minute into standing at a chest the slot tint
		// and the count both vanished while the gather was still running, which read as the mod
		// having given up.
		String itemId = ChestHighlighter.getHighlightedItemId();
		boolean fromHighlight = itemId != null;
		if (itemId == null && BuildGatherSession.isActive()) {
			itemId = BuildGatherSession.currentItemId();
		}
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
		// A timed highlight pulses and fades out as it expires — that fade is how the player
		// knows it is about to stop. A gather target has nothing to fade towards, so it is
		// marked steadily and a little dimmer, which also keeps the two states told apart.
		float pulse = fromHighlight ? 0.8F + 0.2F * (float) Math.sin(now / 280.0) : 0.7F;
		float remain = ChestHighlighter.remainingSeconds();
		float fade = fromHighlight && remain < 4.0F ? Math.max(0.25F, remain / 4.0F) : 1.0F;
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

		// How much of it is in THIS container: the number that decides whether to keep walking
		// or start carrying. The per-slot badges implied it; nobody wants to add them up.
		int inThisContainer = 0;
		for (Slot slot : menu.slots) {
			if (slot.isActive() && !slot.getItem().isEmpty()
				&& slot.container != null
				&& !(slot.container instanceof net.minecraft.world.entity.player.Inventory)
				&& com.chestmemory.client.data.ItemStackKeys.matches(slot.getItem(), itemId)) {
				inThisContainer += slot.getItem().getCount();
			}
		}

		for (Slot slot : menu.slots) {
			if (!wantTint) {
				break;
			}
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

		if (wantHint && stillNeed > 0) {
			drawTakeHint(screen, graphics, font, left, top, itemId, stillNeed, inThisContainer);
		}
	}

	/**
	 * The take-this-much banner over an open container.
	 * <p>
	 * It used to be a bare «↓ 1600» — the one number the HUD already showed. Standing at a
	 * chest the questions are different: what am I collecting, is there enough in THIS
	 * container, and how much is that to carry. So: the item's own icon and name, the
	 * remainder in stacks beside it, and a second line for what this container holds, coloured
	 * by whether it covers the remainder.
	 */
	private static void drawTakeHint(
		AbstractContainerScreen<?> screen,
		GuiGraphicsExtractor graphics,
		net.minecraft.client.gui.Font font,
		int left,
		int top,
		String itemId,
		int stillNeed,
		int inThisContainer
	) {
		ItemStack icon = com.chestmemory.client.data.ItemStackKeys.toStack(itemId);
		String name = com.chestmemory.client.data.ChestMemoryStorage.itemDisplayName(itemId);
		// The same pair the HUD shows. "↓ 1600" over a chest does not say whether that is the
		// whole job or the tail of 31096, and the answer changes how much you take.
		int total = BuildGatherSession.totalNeed(itemId);
		String need = total > stillNeed
			? "↓ " + Component.translatable(
				"hud.chestmemory.val_need_of", stillNeed, total).getString()
			: "↓ " + stillNeed;
		int per = Math.max(1, icon.isEmpty() ? 64 : icon.getMaxStackSize());
		var bulk = com.chestmemory.client.data.BulkAmount.of(stillNeed, per);
		String stacks = bulk.hasStack()
			? com.chestmemory.client.gui.BulkTooltip.stacksText(bulk)
			: "";

		String hereLine = inThisContainer > 0
			? Component.translatable("hint.chestmemory.here", inThisContainer).getString()
			: Component.translatable("hint.chestmemory.here_none").getString();
		int hereColour = inThisContainer >= stillNeed ? 0xFF7FE08A
			: inThisContainer > 0 ? 0xFFFFE066
			: 0xFFFF9090;

		// 18px for the icon, then the widest of the two text rows.
		String topLine = name + "   " + need + (stacks.isEmpty() ? "" : "   " + stacks);
		int textW = Math.max(font.width(topLine), font.width(hereLine));
		int w = 18 + textW + 6;
		int h = 22;

		AbstractContainerScreenAccessor acc = (AbstractContainerScreenAccessor) screen;
		int guiH = acc.chestmemory$getImageHeight();
		int x = left;
		int y = switch (ModSettings.get().gatherSlotHintPos()) {
			case 1 -> top + 2;
			case 2 -> top + guiH + 2;
			default -> top - h - 2;
		};
		// Never off the top of the screen: above is the default and the window can already sit
		// close to the edge on a small GUI scale.
		if (y < 1) {
			y = top + 2;
		}
		if (y + h > screen.height - 1) {
			y = Math.max(1, screen.height - 1 - h);
		}
		// Nor off the right: a long item name on a narrow window pushed the box past the edge.
		if (x + w > screen.width - 1) {
			x = Math.max(1, screen.width - 1 - w);
		}

		graphics.fill(x, y, x + w, y + h, 0xE0101018);
		int accentRgb = ModSettings.get().hudAccentColor();
		graphics.fill(x, y, x + w, y + 1, 0xFF000000 | accentRgb);
		graphics.item(icon, x + 2, y + 3);
		graphics.text(font, topLine, x + 20, y + 3, 0xFFFFFFFF, true);
		graphics.text(font, hereLine, x + 20, y + 13, hereColour, true);
	}
}
