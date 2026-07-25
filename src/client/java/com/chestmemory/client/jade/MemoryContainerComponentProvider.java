package com.chestmemory.client.jade;

import com.chestmemory.ChestMemoryMod;
import com.chestmemory.client.data.ChestMemoryStorage;
import com.chestmemory.client.data.ContainerRecord;
import com.chestmemory.client.data.ItemStackKeys;
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

		if (record == null || record.items().isEmpty()) {
			tooltip.add(Component.translatable("jade.chestmemory.unknown"));
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

		List<Element> row = new ArrayList<>();
		int shown = 0;
		int total = sorted.size();

		for (Map.Entry<String, Integer> entry : sorted) {
			if (shown >= MAX_ITEMS_SHOWN) {
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
			tooltip.add(Component.translatable("jade.chestmemory.more", total - shown));
		}
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
