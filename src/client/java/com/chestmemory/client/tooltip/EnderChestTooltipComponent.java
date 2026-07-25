package com.chestmemory.client.tooltip;

import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * Server/common-side tooltip payload: remembered ender-chest item stacks for icon grid.
 */
public record EnderChestTooltipComponent(List<ItemStack> stacks, int hiddenExtra) implements TooltipComponent {
}
