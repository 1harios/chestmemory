package com.chestmemory.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Click-to-open dropdown list (never cycles on click).
 * Open list is drawn via {@link #renderOverlay} on top of the whole screen.
 */
public class DropdownWidget<T> extends AbstractWidget {
	private final Minecraft minecraft;
	private final Function<T, Component> labeler;
	private final Consumer<T> onChanged;
	/** Optional short prefix shown on the closed bar, e.g. "Сорт: ". */
	private final Component prefix;
	private List<T> options = new ArrayList<>();
	private T selected;
	private boolean open;
	private int scroll;
	/** Row highlighted by keyboard navigation while the list is open. */
	private int keyboardIndex;
	private final int maxVisible;
	private final int rowH;

	public DropdownWidget(
		Minecraft minecraft,
		int x,
		int y,
		int width,
		int height,
		Component title,
		List<T> options,
		T selected,
		Function<T, Component> labeler,
		Consumer<T> onChanged
	) {
		this(minecraft, x, y, width, height, title, null, options, selected, labeler, onChanged);
	}

	public DropdownWidget(
		Minecraft minecraft,
		int x,
		int y,
		int width,
		int height,
		Component title,
		Component prefix,
		List<T> options,
		T selected,
		Function<T, Component> labeler,
		Consumer<T> onChanged
	) {
		super(x, y, width, height, title);
		this.minecraft = minecraft;
		this.prefix = prefix;
		this.labeler = labeler;
		this.onChanged = onChanged;
		this.maxVisible = 8;
		this.rowH = Math.max(16, height);
		setOptions(options, selected);
	}

	public void setOptions(List<T> options, T selected) {
		this.options = options != null ? new ArrayList<>(options) : new ArrayList<>();
		if (selected != null && this.options.contains(selected)) {
			this.selected = selected;
		} else if (!this.options.isEmpty()) {
			this.selected = this.options.getFirst();
		} else {
			this.selected = null;
		}
		this.scroll = 0;
	}

	public T getSelected() {
		return selected;
	}

	public void setSelected(T value) {
		if (value != null && options.contains(value)) {
			this.selected = value;
		}
	}

	public boolean isOpen() {
		return open;
	}

	public void open() {
		this.open = true;
		this.scroll = 0;
	}

	public void close() {
		this.open = false;
	}

	public void toggle() {
		this.open = !this.open;
		if (this.open) {
			this.scroll = 0;
		}
	}

	public int listTop() {
		return this.getY() + this.getHeight();
	}

	/**
	 * Rows that actually fit below the bar without leaving the screen.
	 * <p>
	 * The list used to open at its full height regardless of where the bar sat, so on a
	 * small window (or a large GUI scale) the bottom entries were drawn off-screen and
	 * could be neither seen nor clicked. Never returns 0 while open — one row is always
	 * shown, scrollable, rather than an invisible list.
	 */
	private int visibleRows() {
		int wanted = Math.min(maxVisible, options.size());
		if (wanted <= 0) {
			return 0;
		}
		int screenH = this.minecraft != null && this.minecraft.getWindow() != null
			? this.minecraft.getWindow().getGuiScaledHeight()
			: Integer.MAX_VALUE;
		int room = screenH - listTop() - 2;
		if (room < rowH) {
			return 1;
		}
		return Math.max(1, Math.min(wanted, room / rowH));
	}

	public int listHeight() {
		if (!open) {
			return 0;
		}
		return visibleRows() * rowH;
	}

	public int expandedBottom() {
		return listTop() + listHeight();
	}

	private String closedLabel() {
		String value = selected != null ? labeler.apply(selected).getString() : this.getMessage().getString();
		if (prefix != null) {
			return prefix.getString() + value;
		}
		return value;
	}

	private String ellipsize(String text, int maxTextW, Font font) {
		if (font.width(text) <= maxTextW) {
			return text;
		}
		while (text.length() > 3 && font.width(text + "…") > maxTextW) {
			text = text.substring(0, text.length() - 1);
		}
		return text + "…";
	}

	/** Closed bar only — open list is drawn in {@link #renderOverlay}. */
	@Override
	protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		drawClosedBar(graphics, mouseX, mouseY, open);
	}

	private void drawClosedBar(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean highlighted) {
		int x0 = this.getX();
		int y0 = this.getY();
		int x1 = x0 + this.width;
		int y1 = y0 + this.height;

		graphics.fill(x0, y0, x1, y1, ChestGuiStyle.WOOD_DARK);
		int fill = highlighted ? 0xFFE8D090 : 0xFFC6C6C6;
		graphics.fill(x0 + 1, y0 + 1, x1 - 1, y1 - 1, fill);

		boolean hoverBar = mouseX >= x0 && mouseX < x1 && mouseY >= y0 && mouseY < y1;
		if (hoverBar && !highlighted) {
			graphics.fill(x0 + 1, y0 + 1, x1 - 1, y1 - 1, 0x88FFE08A);
		}

		var font = this.minecraft.font;
		int maxTextW = this.width - 18;
		String text = ellipsize(closedLabel(), maxTextW, font);
		graphics.text(font, text, x0 + 5, y0 + (this.height - 8) / 2, 0xFF3F3F3F, false);
		String arrow = open ? "▲" : "▼";
		graphics.text(font, arrow, x0 + this.width - 12, y0 + (this.height - 8) / 2, 0xFF3F3F3F, false);
	}

	/**
	 * Draw the open list on top of everything. Call from the screen after widgets.
	 */
	public void renderOverlay(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		if (!open || options.isEmpty()) {
			return;
		}

		// Re-draw closed bar highlighted so it sits above other widgets
		drawClosedBar(graphics, mouseX, mouseY, true);

		int visible = visibleRows();
		int top = listTop();
		int listH = visible * rowH;
		int x0 = this.getX();
		int x1 = x0 + this.width;

		// Drop shadow
		graphics.fill(x0 + 2, top + 2, x1 + 2, top + listH + 2, 0x88000000);
		// Panel
		graphics.fill(x0, top, x1, top + listH, 0xFF1A120A);
		graphics.fill(x0 + 1, top, x1 - 1, top + listH - 1, 0xFFE8E0D0);

		int maxScroll = Math.max(0, options.size() - visible);
		scroll = Mth.clamp(scroll, 0, maxScroll);

		var font = this.minecraft.font;
		int maxTextW = this.width - 12;

		for (int i = 0; i < visible; i++) {
			int idx = scroll + i;
			if (idx >= options.size()) {
				break;
			}
			T opt = options.get(idx);
			int ry = top + i * rowH;
			boolean hover = mouseX >= x0 && mouseX < x1 && mouseY >= ry && mouseY < ry + rowH;
			boolean isSel = opt != null && opt.equals(selected);
			// Keyboard highlight reads the same as hover, so arrow keys are not blind.
			if (!hover && this.isFocused() && idx == keyboardIndex) {
				hover = true;
			}
			if (hover) {
				graphics.fill(x0 + 1, ry, x1 - 1, ry + rowH, 0xA0FFE08A);
			} else if (isSel) {
				graphics.fill(x0 + 1, ry, x1 - 1, ry + rowH, 0x66C0A060);
			}
			// Separator line
			if (i > 0) {
				graphics.fill(x0 + 2, ry, x1 - 2, ry + 1, 0x33000000);
			}
			String optText = ellipsize(labeler.apply(opt).getString(), maxTextW, font);
			graphics.text(font, optText, x0 + 5, ry + (rowH - 8) / 2, 0xFF202020, false);
		}

		if (options.size() > visible) {
			graphics.fill(x1 - 4, top + 2, x1 - 2, top + listH - 2, 0x66000000);
			int thumbH = Math.max(6, listH * visible / options.size());
			int thumbY = top + (int) ((listH - thumbH) * (scroll / (float) Math.max(1, maxScroll)));
			graphics.fill(x1 - 4, thumbY, x1 - 2, thumbY + thumbH, 0xFF8B8B8B);
		}
	}

	@Override
	public void onClick(MouseButtonEvent event, boolean doubleClick) {
		// Handled in mouseClicked with full expanded hit-test
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (!this.active || !this.visible) {
			return false;
		}
		if (!this.isValidClickButton(event.buttonInfo())) {
			return false;
		}

		double mx = event.x();
		double my = event.y();

		// Click closed bar → toggle open (never cycle value)
		if (mx >= this.getX() && mx < this.getX() + this.width
			&& my >= this.getY() && my < this.getY() + this.height) {
			this.playDownSound(Minecraft.getInstance().getSoundManager());
			this.open = !this.open;
			if (this.open) {
				this.scroll = 0;
			}
			return true;
		}

		if (!open) {
			return false;
		}

		int visible = visibleRows();
		int top = listTop();

		// Click outside list while open → close, don't consume if outside entirely
		if (mx < this.getX() || mx >= this.getX() + this.width
			|| my < top || my >= top + visible * rowH) {
			this.open = false;
			return false;
		}

		// Click a row → select and close
		int row = (int) ((my - top) / rowH);
		return selectIndex(scroll + row);
	}

	/** Apply the option at {@code idx}, close the list and notify. */
	private boolean selectIndex(int idx) {
		if (idx < 0 || idx >= options.size()) {
			return false;
		}
		this.playDownSound(Minecraft.getInstance().getSoundManager());
		T value = options.get(idx);
		this.selected = value;
		this.open = false;
		if (onChanged != null) {
			onChanged.accept(value);
		}
		return true;
	}

	/**
	 * Keyboard control: the list was mouse-only, so Tab could focus the bar but nothing
	 * could be opened or picked from the keyboard.
	 * Enter/Space toggles, arrows move the highlight, Esc closes just the list.
	 */
	@Override
	public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
		if (!this.active || !this.visible) {
			return false;
		}
		int key = event.key();
		if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER
			|| key == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER
			|| key == org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE) {
			if (!open) {
				this.open = true;
				this.scroll = 0;
				this.keyboardIndex = Math.max(0, options.indexOf(selected));
				return true;
			}
			return selectIndex(keyboardIndex);
		}
		if (!open) {
			return false;
		}
		if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
			this.open = false;
			return true;
		}
		int delta = switch (key) {
			case org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN -> 1;
			case org.lwjgl.glfw.GLFW.GLFW_KEY_UP -> -1;
			default -> 0;
		};
		if (delta == 0) {
			return false;
		}
		keyboardIndex = Mth.clamp(keyboardIndex + delta, 0, Math.max(0, options.size() - 1));
		// Keep the highlighted row inside the visible window
		int visible = visibleRows();
		if (keyboardIndex < scroll) {
			scroll = keyboardIndex;
		} else if (keyboardIndex >= scroll + visible) {
			scroll = keyboardIndex - visible + 1;
		}
		return true;
	}

	public boolean isInExpandedArea(double mx, double my) {
		if (mx < this.getX() || mx >= this.getX() + this.width) {
			return false;
		}
		if (my >= this.getY() && my < this.getY() + this.height) {
			return true;
		}
		if (!open) {
			return false;
		}
		int visible = visibleRows();
		int top = listTop();
		return my >= top && my < top + visible * rowH;
	}

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		if (!open || !isInExpandedArea(x, y)) {
			return false;
		}
		int visible = visibleRows();
		int maxScroll = Math.max(0, options.size() - visible);
		if (scrollY > 0) {
			scroll = Math.max(0, scroll - 1);
		} else if (scrollY < 0) {
			scroll = Math.min(maxScroll, scroll + 1);
		}
		return true;
	}

	@Override
	public boolean isMouseOver(double mouseX, double mouseY) {
		return isInExpandedArea(mouseX, mouseY);
	}

	@Override
	public void playDownSound(SoundManager handler) {
		super.playDownSound(handler);
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput output) {
		output.add(NarratedElementType.TITLE, this.getMessage());
		if (selected != null) {
			output.add(NarratedElementType.HINT, labeler.apply(selected));
		}
	}
}
