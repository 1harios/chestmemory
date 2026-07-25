package com.chestmemory.client.tooltip;

import com.chestmemory.client.data.ChestMemoryStorage;
import com.chestmemory.client.data.ContainerRecord;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.fabricmc.fabric.api.client.rendering.v1.ClientTooltipComponentCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Ender-chest hover: short text header + icon grid of remembered contents.
 */
public final class EnderChestTooltip {
	/** Max icons shown in the grid. */
	private static final int MAX_ICONS = 27;

	private EnderChestTooltip() {
	}

	public static void register() {
		// Map our TooltipComponent → client icon grid renderer
		ClientTooltipComponentCallback.EVENT.register(data -> {
			if (data instanceof EnderChestTooltipComponent ender) {
				return new ClientEnderChestTooltip(ender);
			}
			return null;
		});

		// Compact text header (icons come from getTooltipImage via mixin)
		ItemTooltipCallback.EVENT.register((stack, context, flag, lines) -> {
			if (stack == null || stack.isEmpty() || !stack.is(Items.ENDER_CHEST)) {
				return;
			}

			Minecraft client = Minecraft.getInstance();
			if (client != null && client.player != null) {
				ChestMemoryStorage.get().ensureLoaded(client);
			}

			ContainerRecord ender = ChestMemoryStorage.get().findEnderChest();
			if (ender == null) {
				lines.add(Component.translatable("tooltip.chestmemory.ender_unknown").withStyle(ChatFormatting.DARK_GRAY));
				return;
			}
			if (ender.items().isEmpty()) {
				lines.add(Component.translatable("tooltip.chestmemory.ender_empty").withStyle(ChatFormatting.DARK_GRAY));
				return;
			}

			lines.add(Component.translatable(
				"tooltip.chestmemory.ender_header",
				ender.totalItemCount(),
				ender.itemTypeCount()
			).withStyle(ChatFormatting.GOLD));
			// No per-item text lines — icons are drawn by ClientEnderChestTooltip
		});
	}

	/**
	 * Build icon-grid tooltip component for an ender chest item, if memory has data.
	 */
	public static Optional<TooltipComponent> createImageComponent() {
		Minecraft client = Minecraft.getInstance();
		if (client != null && client.player != null) {
			ChestMemoryStorage.get().ensureLoaded(client);
		}

		ContainerRecord ender = ChestMemoryStorage.get().findEnderChest();
		if (ender == null || ender.items().isEmpty()) {
			return Optional.empty();
		}

		List<Map.Entry<String, Integer>> sorted = new ArrayList<>(ender.items().entrySet());
		sorted.sort(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue).reversed());

		List<ItemStack> stacks = new ArrayList<>();
		int hidden = 0;
		for (int i = 0; i < sorted.size(); i++) {
			Map.Entry<String, Integer> e = sorted.get(i);
			if (i >= MAX_ICONS) {
				hidden = sorted.size() - MAX_ICONS;
				break;
			}
			ItemStack icon = stackFromId(e.getKey(), e.getValue());
			if (icon != null && !icon.isEmpty()) {
				stacks.add(icon);
			}
		}

		if (stacks.isEmpty()) {
			return Optional.empty();
		}
		return Optional.of(new EnderChestTooltipComponent(List.copyOf(stacks), hidden));
	}

	private static @Nullable ItemStack stackFromId(String itemId, int count) {
		Identifier id = Identifier.tryParse(itemId);
		if (id == null) {
			return null;
		}
		Item item = BuiltInRegistries.ITEM.getValue(id);
		if (item == null || item == Items.AIR) {
			return null;
		}
		// Show real total on the icon (vanilla count text handles large numbers poorly —
		// ClientEnderChestTooltip formats k/M itself)
		int shown = Math.max(1, Math.min(count, 999_999));
		return new ItemStack(item, shown);
	}
}
