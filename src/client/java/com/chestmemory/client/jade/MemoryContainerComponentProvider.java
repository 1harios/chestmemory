package com.chestmemory.client.jade;

import com.chestmemory.ChestMemoryMod;
import com.chestmemory.client.data.ChestMemoryStorage;
import com.chestmemory.client.data.ContainerRecord;
import com.chestmemory.client.data.ItemStackKeys;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.EnderChestBlock;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.TooltipPosition;
import snownee.jade.api.config.IPluginConfig;
import snownee.jade.api.ui.Element;
import snownee.jade.api.ui.JadeUI;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Jade overlay: only item icons with counts, laid out in horizontal rows.
 */
public final class MemoryContainerComponentProvider implements IBlockComponentProvider {
	public static final MemoryContainerComponentProvider INSTANCE = new MemoryContainerComponentProvider();
	public static final Identifier UID = ChestMemoryMod.id("memory_contents");

	private static final int MAX_ITEMS_SHOWN = 18;
	/**
	 * Cap while Shift is held — the whole list, in practice.
	 * <p>
	 * Not unbounded: an ender chest's record aggregates everything the player owns there, and
	 * a tooltip taller than the screen shows nothing useful at all. Ten rows covers a double
	 * chest's 54 distinct types with room to spare, and anything past it still says how much
	 * was left out.
	 */
	private static final int MAX_ITEMS_SHIFT = 90;
	private static final int ITEMS_PER_ROW = 9;

	private MemoryContainerComponentProvider() {
	}

	@Override
	public Identifier getUid() {
		return UID;
	}

	@Override
	public int getDefaultPriority() {
		return TooltipPosition.BODY + 50;
	}

	@Override
	public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
		Minecraft client = Minecraft.getInstance();
		if (client.level == null) {
			return;
		}

		ChestMemoryStorage storage = ChestMemoryStorage.get();
		storage.ensureLoaded(client);
		if (storage.liveWorldId() == null) {
			return;
		}

		String dimension = ChestMemoryStorage.dimensionId(client.level);
		BlockPos pos = accessor.getPosition();
		ContainerRecord record = null;

		if (accessor.getBlock() instanceof EnderChestBlock) {
			for (ContainerRecord r : storage.liveContainersSnapshot()) {
				if (r.isVirtual() && "ender_chest".equals(r.virtualId())) {
					record = r;
					break;
				}
			}
		} else {
			record = storage.findAtLive(dimension, pos, client.level);
		}

		if (record != null && storage.isStaging(record)) {
			tooltip.add(Component.translatable("jade.chestmemory.staging"));
		}

		if (record == null) {
			tooltip.add(Component.translatable("jade.chestmemory.unknown"));
			return;
		}
		if (record.items().isEmpty()) {
			// Scanned and genuinely empty, which is not the same as never opened — the
			// scanner does record an empty container once the server has synced the menu.
			// Reporting "not scanned" over a chest the player emptied themselves reads as
			// the mod having lost track of it.
			tooltip.add(Component.translatable("jade.chestmemory.empty"));
			return;
		}

		// Compact header (no item names)
		tooltip.add(Component.translatable(
			"jade.chestmemory.header_short",
			record.totalItemCount(),
			record.itemTypeCount()
		));

		List<Map.Entry<String, Integer>> sorted = new ArrayList<>(record.items().entrySet());
		sorted.sort(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue).reversed());

		// Shift opens the list up. Eighteen icons is the right size for a glance while
		// walking past a wall of chests, but it is useless when the question is "is the
		// redstone in THIS one" — and the answer was one line saying "…and 14 more".
		boolean expanded = isShiftDown(client);
		int limit = expanded ? MAX_ITEMS_SHIFT : MAX_ITEMS_SHOWN;

		List<Element> row = new ArrayList<>();
		int shown = 0;
		int total = sorted.size();

		for (Map.Entry<String, Integer> entry : sorted) {
			if (shown >= limit) {
				break;
			}

			int count = entry.getValue();
			ItemStack stack = stackFromId(entry.getKey(), Math.min(count, 64));
			// Icon + quantity only (no name)
			row.add(JadeUI.item(stack, 0.9F, formatCount(count)));
			shown++;

			if (row.size() >= ITEMS_PER_ROW) {
				tooltip.add(row);
				row = new ArrayList<>();
			}
		}

		if (!row.isEmpty()) {
			tooltip.add(row);
		}

		if (total > shown) {
			// The hint rides on the line that proves it is needed, and only while the list is
			// actually truncated by the short cap — once Shift is held, repeating it would be
			// telling the player to press the key they are already holding.
			tooltip.add(Component.translatable(
				expanded ? "jade.chestmemory.more" : "jade.chestmemory.more_shift",
				total - shown
			));
		}
	}

	/**
	 * Either shift key, read straight from the window.
	 * <p>
	 * A Jade overlay draws while the player is walking around with no screen open, so there
	 * is no key event to consult and {@code Screen}'s modifier helpers do not exist in this
	 * version. Both keys count: a player who reaches for the right one should not conclude the
	 * feature is broken.
	 */
	private static boolean isShiftDown(Minecraft client) {
		com.mojang.blaze3d.platform.Window window = client.getWindow();
		if (window == null) {
			return false;
		}
		return InputConstants.isKeyDown(window, InputConstants.KEY_LSHIFT)
			|| InputConstants.isKeyDown(window, InputConstants.KEY_RSHIFT);
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

	private static ItemStack stackFromId(String itemId, int count) {
		// Keys may include enchantments: minecraft:enchanted_book#s:minecraft:sharpness=5
		// Identifier.tryParse on the full string fails → red barrier; use ItemStackKeys.
		ItemStack stack = ItemStackKeys.toStack(itemId);
		if (stack.isEmpty() || stack.is(Items.AIR) || stack.is(Items.BARRIER)) {
			// toStack falls back to chest for unknown base — barrier only if truly broken
			if (stack.is(Items.CHEST) && itemId != null && !itemId.startsWith("minecraft:chest")) {
				return new ItemStack(Items.BARRIER);
			}
		}
		if (stack.isEmpty() || stack.is(Items.AIR)) {
			return new ItemStack(Items.BARRIER);
		}
		stack.setCount(Math.max(1, Math.min(count, stack.getMaxStackSize())));
		return stack;
	}
}
