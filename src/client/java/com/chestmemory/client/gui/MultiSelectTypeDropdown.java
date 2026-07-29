package com.chestmemory.client.gui;

import com.chestmemory.client.data.ContainerFilter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.function.Consumer;

/**
 * Multi-select type filter: chests + barrels + hoppers… (checkboxes in a dropdown).
 */
public class MultiSelectTypeDropdown extends AbstractWidget {
	private final Minecraft minecraft;
	private final Component prefix;
	private final List<ContainerFilter> options = new ArrayList<>();
	private final EnumSet<ContainerFilter> selected = EnumSet.noneOf(ContainerFilter.class);
	private final Consumer<EnumSet<ContainerFilter>> onChanged;
	private boolean open;
	private int scroll;
	/** Row highlighted by keyboard navigation while the list is open. */
	private int keyboardIndex;
	private final int maxVisible = 9;
	private final int rowH;

	public MultiSelectTypeDropdown(
		Minecraft minecraft,
		int x,
		int y,
		int width,
		int height,
		Component prefix,
		EnumSet<ContainerFilter> initial,
		Consumer<EnumSet<ContainerFilter>> onChanged
	) {
		super(x, y, width, height, Component.translatable("screen.chestmemory.filter"));
		this.minecraft = minecraft;
		this.prefix = prefix;
		this.onChanged = onChanged;
		this.rowH = Math.max(16, height);
		// ALL first, then specific types
		this.options.add(ContainerFilter.ALL);
		for (ContainerFilter f : ContainerFilter.values()) {
			if (f != ContainerFilter.ALL) {
				this.options.add(f);
			}
		}
		setSelected(initial);
	}

	public void setSelected(EnumSet<ContainerFilter> set) {
		selected.clear();
		if (set == null || set.isEmpty() || set.contains(ContainerFilter.ALL)) {
			selected.add(ContainerFilter.ALL);
		} else {
			selected.addAll(set);
			selected.remove(ContainerFilter.ALL);
			if (selected.isEmpty()) {
				selected.add(ContainerFilter.ALL);
			}
		}
	}

	public EnumSet<ContainerFilter> getSelected() {
		return EnumSet.copyOf(selected);
	}

	public boolean isOpen() {
		return open;
	}

	public void close() {
		this.open = false;
	}

	public int listTop() {
		return this.getY() + this.getHeight();
	}

	/**
	 * Rows that fit below the bar without running off the bottom of the screen.
	 * Same reasoning as DropdownWidget.visibleRows: the list used to open at full height
	 * regardless of position, hiding its last entries on small windows.
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

	private String closedLabel() {
		String value;
		if (selected.contains(ContainerFilter.ALL) || selected.isEmpty()) {
			value = ContainerFilter.ALL.label().getString();
		} else if (selected.size() == 1) {
			value = selected.iterator().next().label().getString();
		} else {
			// Short multi label: "Сундуки + бочки + …" or count
			StringBuilder sb = new StringBuilder();
			int n = 0;
			for (ContainerFilter f : ContainerFilter.values()) {
				if (f == ContainerFilter.ALL || !selected.contains(f)) {
					continue;
				}
				if (n > 0) {
					sb.append('+');
				}
				sb.append(shortName(f));
				n++;
				if (n >= 3 && selected.size() > 3) {
					sb.append("+…");
					break;
				}
			}
			value = sb.toString();
		}
		if (prefix != null) {
			return prefix.getString() + value;
		}
		return value;
	}

	private static String shortName(ContainerFilter f) {
		String s = f.label().getString();
		// Keep short for closed bar
		if (s.length() > 10) {
			return s.substring(0, 9) + "…";
		}
		return s;
	}

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
		String text = ChestGuiStyle.ellipsize(font, closedLabel(), maxTextW);
		graphics.text(font, text, x0 + 5, y0 + (this.height - 8) / 2, 0xFF3F3F3F, false);
		String arrow = open ? "▲" : "▼";
		graphics.text(font, arrow, x0 + this.width - 12, y0 + (this.height - 8) / 2, 0xFF3F3F3F, false);
	}

	public void renderOverlay(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		if (!open || options.isEmpty()) {
			return;
		}
		drawClosedBar(graphics, mouseX, mouseY, true);

		int visible = visibleRows();
		int top = listTop();
		int listH = visible * rowH;
		int x0 = this.getX();
		int x1 = x0 + this.width;

		graphics.fill(x0 + 2, top + 2, x1 + 2, top + listH + 2, 0x88000000);
		graphics.fill(x0, top, x1, top + listH, 0xFF1A120A);
		graphics.fill(x0 + 1, top, x1 - 1, top + listH - 1, 0xFFE8E0D0);

		int maxScroll = Math.max(0, options.size() - visible);
		scroll = Mth.clamp(scroll, 0, maxScroll);

		var font = this.minecraft.font;
		int maxTextW = this.width - 22;

		for (int i = 0; i < visible; i++) {
			int idx = scroll + i;
			if (idx >= options.size()) {
				break;
			}
			ContainerFilter opt = options.get(idx);
			int ry = top + i * rowH;
			boolean hover = mouseX >= x0 && mouseX < x1 && mouseY >= ry && mouseY < ry + rowH;
			// Keyboard highlight reads the same as hover, so arrow keys are not blind.
			if (!hover && this.isFocused() && idx == keyboardIndex) {
				hover = true;
			}
			boolean on = isOn(opt);
			if (hover) {
				graphics.fill(x0 + 1, ry, x1 - 1, ry + rowH, 0xA0FFE08A);
			} else if (on) {
				graphics.fill(x0 + 1, ry, x1 - 1, ry + rowH, 0x66A0D080);
			}
			if (i > 0) {
				graphics.fill(x0 + 2, ry, x1 - 2, ry + 1, 0x33000000);
			}
			// Checkbox
			int cx = x0 + 4;
			int cy = ry + (rowH - 8) / 2;
			graphics.fill(cx, cy, cx + 8, cy + 8, 0xFF3F3F3F);
			graphics.fill(cx + 1, cy + 1, cx + 7, cy + 7, 0xFFFFFFFF);
			if (on) {
				graphics.fill(cx + 2, cy + 2, cx + 6, cy + 6, 0xFF2E8B2E);
			}
			String optText = ChestGuiStyle.ellipsize(font, opt.label().getString(), maxTextW);
			graphics.text(font, optText, x0 + 15, ry + (rowH - 8) / 2, 0xFF202020, false);
		}

		if (options.size() > visible) {
			graphics.fill(x1 - 4, top + 2, x1 - 2, top + listH - 2, 0x66000000);
			int thumbH = Math.max(6, listH * visible / options.size());
			int thumbY = top + (int) ((listH - thumbH) * (scroll / (float) Math.max(1, maxScroll)));
			graphics.fill(x1 - 4, thumbY, x1 - 2, thumbY + thumbH, ChestGuiStyle.BRASS);
		}
	}

	private boolean isOn(ContainerFilter opt) {
		if (opt == ContainerFilter.ALL) {
			return selected.contains(ContainerFilter.ALL) || selected.isEmpty();
		}
		return !selected.contains(ContainerFilter.ALL) && selected.contains(opt);
	}

	private void toggleOption(ContainerFilter opt) {
		if (opt == ContainerFilter.ALL) {
			selected.clear();
			selected.add(ContainerFilter.ALL);
		} else {
			selected.remove(ContainerFilter.ALL);
			if (selected.contains(opt)) {
				selected.remove(opt);
			} else {
				selected.add(opt);
			}
			if (selected.isEmpty()) {
				selected.add(ContainerFilter.ALL);
			}
			// If every specific type selected → collapse to ALL
			boolean allSpecific = true;
			for (ContainerFilter f : ContainerFilter.values()) {
				if (f != ContainerFilter.ALL && !selected.contains(f)) {
					allSpecific = false;
					break;
				}
			}
			if (allSpecific && !selected.contains(ContainerFilter.ALL)) {
				selected.clear();
				selected.add(ContainerFilter.ALL);
			}
		}
		if (onChanged != null) {
			onChanged.accept(getSelected());
		}
	}

	@Override
	public void onClick(MouseButtonEvent event, boolean doubleClick) {
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

		if (mx < this.getX() || mx >= this.getX() + this.width
			|| my < top || my >= top + visible * rowH) {
			this.open = false;
			return false;
		}

		int row = (int) ((my - top) / rowH);
		int idx = scroll + row;
		if (idx >= 0 && idx < options.size()) {
			this.playDownSound(Minecraft.getInstance().getSoundManager());
			toggleOption(options.get(idx));
			// Keep open for multi-select
			return true;
		}
		return false;
	}

	/**
	 * Keyboard control, mirroring DropdownWidget: the two dropdowns share the same filter
	 * row, so a keyboard user could tab to this one, open nothing and pick nothing while
	 * its neighbour worked fine. Enter/Space opens, then toggles the highlighted type —
	 * the list stays open, exactly like a mouse click, because multi-select means several
	 * toggles per visit. Arrows move the highlight, Esc closes just the list.
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
				this.keyboardIndex = 0;
				return true;
			}
			if (keyboardIndex >= 0 && keyboardIndex < options.size()) {
				this.playDownSound(Minecraft.getInstance().getSoundManager());
				toggleOption(options.get(keyboardIndex));
			}
			return true;
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
	protected void updateWidgetNarration(NarrationElementOutput output) {
		output.add(NarratedElementType.TITLE, closedLabel());
	}
}
