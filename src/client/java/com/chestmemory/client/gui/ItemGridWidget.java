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
		this.tooltipItemId = null;
		this.tooltipLines = List.of();
		for (ItemSummary s : this.items) {
			this.iconCache.computeIfAbsent(s.itemId(), com.chestmemory.client.data.ItemStackKeys::toStack);
		}
	}

	/**
	 * Composed tooltip for the hovered item. Building it walks the container list (world
	 * breakdown) and resolves names, so it is cached per hovered id and refreshed on a
	 * timer instead of being rebuilt every frame.
	 */
	private @org.jspecify.annotations.Nullable String tooltipItemId;
	private long tooltipBuiltMs;
	private List<Component> tooltipLines = List.of();

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
		ChestGuiStyle.drawGridTray(graphics, this.getX(), this.getY(), this.width, this.height);

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

			// Count: size / style / colour come from settings (see panel tab preview).
			String countText = summary.isBuildNeed()
				? ChestGuiStyle.formatCount(summary.neededForBuild())
				: ChestGuiStyle.formatCount(summary.totalCount());
			var font = this.minecraft.font;
			int countColor;
			if (!summary.isBuildNeed()) {
				countColor = 0; // 0 = configured colour
			} else if (summary.neededForBuild() <= 0) {
				countColor = 0xFF88CCFF; // done
			} else if (summary.stillShort() > 0) {
				countColor = 0xFFFFEE66;
			} else {
				countColor = 0xFF80FFA0; // ready to take from chests
			}
			ChestGuiStyle.drawSlotCount(graphics, font, countText, slotX, slotY, countColor);

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
			graphics.fill(this.getX() + this.width - 3, barY, this.getX() + this.width - 1, barY + barH,
				this.draggingScrollbar ? ChestGuiStyle.BRASS_BRIGHT : ChestGuiStyle.BRASS);
		}

		if (hoveredIndex >= 0 && hoveredIndex < items.size()) {
			ItemSummary s = items.get(hoveredIndex);
			long now = System.currentTimeMillis();
			// Rebuild only when the hovered item changes (or twice a second, so live data
			// like clan claims stays fresh) — building walks containers and resolves names.
			if (!s.itemId().equals(this.tooltipItemId) || now - this.tooltipBuiltMs > 500) {
				this.tooltipLines = buildTooltip(s);
				this.tooltipItemId = s.itemId();
				this.tooltipBuiltMs = now;
			}
			graphics.setTooltipForNextFrame(this.minecraft.font, this.tooltipLines, java.util.Optional.empty(), mouseX, mouseY);
		}
	}

	/**
	 * Hover card in vanilla grammar: white name, gray facts, dark-gray id at the bottom —
	 * the layout of an advanced tooltip, not a coloured dashboard.
	 */
	private List<Component> buildTooltip(ItemSummary s) {
		List<Component> lines = new ArrayList<>();
		var storage = com.chestmemory.client.data.ChestMemoryStorage.get();

		// ── Name exactly as vanilla draws it: rarity colour (aqua for enchanted gear,
		// yellow for uncommon, …), italics for renamed items. The stack is rebuilt from
		// the key with its enchantments and custom name, so getStyledHoverName() is the
		// same call vanilla's own tooltip makes — colours in renamed names included.
		if (com.chestmemory.client.data.ItemStackKeys.isKnown(s.itemId())) {
			lines.add(resolveStack(s.itemId()).getStyledHoverName());
		} else {
			// Removed-mod item: no registry entry to style, show the raw id plainly.
			lines.add(Component.literal(com.chestmemory.client.data.ItemStackKeys.baseId(s.itemId()))
				.withStyle(net.minecraft.ChatFormatting.WHITE));
		}
		if (com.chestmemory.client.data.ItemStackKeys.hasEnchantData(s.itemId())) {
			for (String name : com.chestmemory.client.data.ItemStackKeys.enchantNames(s.itemId())) {
				lines.add(Component.literal(name).withStyle(net.minecraft.ChatFormatting.GRAY));
			}
		}
		lines.add(Component.empty());

		if (s.isBuildNeed()) {
			buildModeLines(lines, s);
		} else {
			normalModeLines(lines, s, storage);
		}

		// ── Clan claims ──
		if (com.chestmemory.client.clan.ClanSessionManager.isInSession()
			&& com.chestmemory.client.clan.ClanSessionManager.isInActiveGather(s.itemId())) {
			// Only for items the gather actually contains: «Клан: свободно — клик = взять»
			// on a random remembered item promised a claim that could never happen.
			lines.add(Component.empty());
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

		if (s.isBuildNeed() && s.neededForBuild() > 0) {
			lines.add(Component.translatable("screen.chestmemory.tooltip.build_click")
				.withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
		}

		// Raw id last, dark gray — where vanilla advanced tooltips put it.
		lines.add(Component.literal(com.chestmemory.client.data.ItemStackKeys.baseId(s.itemId()))
			.withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
		return lines;
	}

	private void normalModeLines(
		List<Component> lines,
		ItemSummary s,
		com.chestmemory.client.data.ChestMemoryStorage storage
	) {
		// Amount, with real stack size — 16 for pearls, 1 for tools, not always 64.
		// Compact: "×372 (5 ст. + 52)"; the stack size itself is noise here.
		int per = Math.max(1, resolveStack(s.itemId()).getMaxStackSize());
		int stacks = s.fullStacks(per);
		int rem = s.remainder(per);
		if (stacks > 0 && rem > 0) {
			lines.add(gray(Component.translatable(
				"screen.chestmemory.tooltip.amount_stacks", s.totalCount(), stacks, rem)));
		} else if (stacks > 0) {
			lines.add(gray(Component.translatable(
				"screen.chestmemory.tooltip.amount_stacks_even", s.totalCount(), stacks)));
		} else {
			lines.add(gray(Component.translatable("screen.chestmemory.tooltip.amount", s.totalCount())));
		}
		if (s.hasDistance()) {
			lines.add(gray(Component.translatable(
				"screen.chestmemory.tooltip.containers_nearest",
				s.containerCount(), (int) s.nearestDistance()
			)));
		} else {
			lines.add(gray(Component.translatable("screen.chestmemory.tooltip.containers", s.containerCount())));
		}

		// ── Where it lies: per-world breakdown (multiworld servers) — plain gray lines ──
		boolean live = storage.isViewingLive();
		Minecraft mc = this.minecraft;
		String playerDim = live && mc != null && mc.level != null
			? com.chestmemory.client.data.ChestMemoryStorage.dimensionId(mc.level)
			: null;
		String currentTag = live
			? com.chestmemory.client.data.WorldFingerprint.current(mc)
			: null;
		List<com.chestmemory.client.data.ContainerRecord> all = storage.allContainers();
		List<com.chestmemory.client.data.WorldBreakdown.Entry> groups =
			com.chestmemory.client.data.WorldBreakdown.of(all, s.itemId(), playerDim, currentTag);
		// Only worth lines when the answer is not simply "all of it is right here".
		boolean informative = groups.size() > 1
			|| (groups.size() == 1 && !groups.getFirst().here());
		if (informative) {
			int shown = 0;
			for (com.chestmemory.client.data.WorldBreakdown.Entry e : groups) {
				if (shown >= 4) {
					lines.add(Component.literal("…").withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
					break;
				}
				if (e.here()) {
					lines.add(gray(Component.translatable(
						"screen.chestmemory.tooltip.where_line_here", e.count(), e.containers())));
				} else {
					lines.add(gray(Component.translatable(
						"screen.chestmemory.tooltip.where_line",
						worldLabel(e), e.count(), e.containers())));
				}
				shown++;
			}
		}
		// Personal storage, split the way players think about it: shulkers on you vs ender.
		int inShulkers = com.chestmemory.client.data.WorldBreakdown.shulkerCount(all, s.itemId());
		int inEnder = com.chestmemory.client.data.WorldBreakdown.enderCount(all, s.itemId());
		if (inShulkers > 0) {
			lines.add(gray(Component.translatable("screen.chestmemory.tooltip.shulkers", inShulkers)));
		}
		if (inEnder > 0) {
			lines.add(gray(Component.translatable("screen.chestmemory.tooltip.ender", inEnder)));
		}

		if (live) {
			int staging = storage.countInStaging(s.itemId());
			if (staging > 0) {
				lines.add(gray(Component.translatable("screen.chestmemory.tooltip.staging_line", staging)));
			}
		}
	}

	private void buildModeLines(List<Component> lines, ItemSummary s) {
		// Facts in plain gray; only the one status line that decides "go / don't go"
		// carries colour.
		if (s.schematicTotal() > 0) {
			lines.add(gray(Component.translatable("screen.chestmemory.tooltip.build_total", s.schematicTotal())));
		}
		if (s.neededForBuild() <= 0) {
			lines.add(Component.translatable("screen.chestmemory.tooltip.build_done")
				.withStyle(net.minecraft.ChatFormatting.GREEN));
		} else {
			lines.add(gray(Component.translatable("screen.chestmemory.tooltip.build_needed", s.neededForBuild())));
		}
		if (s.inPlayer() >= 0) {
			lines.add(gray(Component.translatable("screen.chestmemory.tooltip.build_in_player", s.inPlayer())));
		}
		int staging = com.chestmemory.client.data.ChestMemoryStorage.get().countInStaging(s.itemId());
		if (staging > 0) {
			lines.add(gray(Component.translatable("screen.chestmemory.tooltip.build_in_staging", staging)));
		}
		lines.add(gray(Component.translatable("screen.chestmemory.tooltip.build_in_chests", s.totalCount())));
		if (s.neededForBuild() > 0 && s.canGatherFromChests() > 0) {
			lines.add(gray(Component.translatable("screen.chestmemory.tooltip.build_can_gather", s.canGatherFromChests())));
		}
		if (s.neededForBuild() > 0) {
			if (s.totalCount() <= 0) {
				lines.add(Component.translatable("screen.chestmemory.tooltip.build_none_chests")
					.withStyle(net.minecraft.ChatFormatting.RED));
			} else if (s.stillShort() > 0) {
				lines.add(gray(Component.translatable("screen.chestmemory.tooltip.build_short", s.stillShort())));
			} else {
				lines.add(Component.translatable("screen.chestmemory.tooltip.build_enough")
					.withStyle(net.minecraft.ChatFormatting.GREEN));
			}
		}
		if (s.hasDistance()) {
			lines.add(gray(Component.translatable("screen.chestmemory.tooltip.nearest", (int) s.nearestDistance())));
		}
	}

	/** Label for a non-"here" breakdown group. */
	static Component worldLabel(com.chestmemory.client.data.WorldBreakdown.Entry e) {
		if (e.otherWorld()) {
			// Same dimension id as the player, provably another world: the id cannot name
			// it, so it is called what it is.
			return Component.translatable("chestmemory.world.other");
		}
		return Component.literal(com.chestmemory.client.data.DimensionChoice.prettyName(e.dimensionId()));
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
