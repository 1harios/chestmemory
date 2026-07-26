package com.chestmemory.client.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Scroll state for a painted list of fixed-height rows.
 * <p>
 * The clan screen drew its rows straight into the panel and stopped with a {@code break} when
 * it ran out of room — so a clan with more than seven gathers, or a build with more than a
 * handful of materials, simply could not see the rest. There was no scrolling anywhere on the
 * screen.
 * <p>
 * Not a widget: the rows are painted, because each tab draws something different in them.
 * This owns only the arithmetic — which rows are visible, where the thumb goes, and how a
 * click maps back to a row index.
 */
public final class ScrollList {
	private int scroll;
	private int top = -1;
	private int bottom = -1;
	private int rowH = 22;
	private int total;
	private int left;
	private int width;

	/**
	 * Set the geometry for this frame. Called by the drawing code before it uses anything
	 * else, so the hit-testing and the painting can never disagree about the layout.
	 */
	public void layout(int left, int top, int width, int bottom, int rowH, int total) {
		this.left = left;
		this.top = top;
		this.width = width;
		this.bottom = bottom;
		this.rowH = Math.max(1, rowH);
		this.total = Math.max(0, total);
		// Clamp after the fact: rows disappear while the list is open (a gather ends, a
		// material is finished), and a stale offset would leave the view stuck past the end.
		this.scroll = Math.max(0, Math.min(this.scroll, maxScroll()));
	}

	/** Rows that fit in the visible area. */
	public int visibleRows() {
		return Math.max(0, (bottom - top) / rowH);
	}

	public int maxScroll() {
		return Math.max(0, total - visibleRows());
	}

	/** Index of the first visible row. */
	public int firstVisible() {
		return scroll;
	}

	/** Index just past the last visible row. */
	public int lastVisible() {
		return Math.min(total, scroll + visibleRows());
	}

	public boolean canScroll() {
		return maxScroll() > 0;
	}

	/** Y of a row, given its absolute index. */
	public int rowY(int index) {
		return top + (index - scroll) * rowH;
	}

	/** Width available to a row, leaving room for the scrollbar when there is one. */
	public int rowWidth() {
		return canScroll() ? width - 6 : width;
	}

	public boolean scrolled(double mouseX, double mouseY, double amount) {
		if (!canScroll() || mouseX < left || mouseX > left + width
			|| mouseY < top || mouseY > bottom) {
			return false;
		}
		// Report whether anything actually moved. Claiming the event at either end swallowed
		// the wheel, so a page behind the screen could not scroll once the pointer was over a
		// list that had nothing left to give.
		int before = scroll;
		if (amount > 0) {
			scroll = Math.max(0, scroll - 1);
		} else if (amount < 0) {
			scroll = Math.min(maxScroll(), scroll + 1);
		}
		return scroll != before;
	}

	/**
	 * Row index under the pointer, or -1.
	 * <p>
	 * Returns -1 for the seam between rows as well as for everything outside the list, so a
	 * click in a 2px gap does nothing rather than hitting a row the player was not aiming at.
	 */
	public int rowAt(double mouseX, double mouseY, int rowBodyH) {
		if (total == 0 || top < 0 || mouseX < left || mouseX > left + rowWidth()
			|| mouseY < top || mouseY >= bottom) {
			return -1;
		}
		int offset = (int) (mouseY - top);
		int index = scroll + offset / rowH;
		if (index < 0 || index >= total) {
			return -1;
		}
		if (offset % rowH > rowBodyH) {
			return -1;
		}
		return index;
	}

	/** Reset to the top — used when the list is replaced by a different one. */
	public void reset() {
		scroll = 0;
	}

	/** Thin scrollbar on the right edge; drawn only when there is something to scroll to. */
	public void drawScrollbar(GuiGraphicsExtractor graphics) {
		if (!canScroll()) {
			return;
		}
		int x = left + width - 4;
		int trackH = bottom - top;
		graphics.fill(x, top, x + 4, bottom, ChestGuiStyle.withAlpha(0x000000, 0.35F));
		int thumbH = Math.max(12, trackH * visibleRows() / Math.max(1, total));
		int travel = trackH - thumbH;
		int thumbY = top + (maxScroll() == 0 ? 0 : travel * scroll / maxScroll());
		graphics.fill(x, thumbY, x + 4, thumbY + thumbH, ChestGuiStyle.WOOD_LIGHT);
		graphics.fill(x, thumbY, x + 4, thumbY + 1, ChestGuiStyle.withAlpha(0xFFFFFF, 0.25F));
	}
}
