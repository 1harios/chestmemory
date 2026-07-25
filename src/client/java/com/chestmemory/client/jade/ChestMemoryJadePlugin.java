package com.chestmemory.client.jade;

import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.IWailaCommonRegistration;
import snownee.jade.api.IWailaPlugin;
import snownee.jade.api.WailaPlugin;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.DropperBlock;
import net.minecraft.world.level.block.EnderChestBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;

/**
 * Jade integration: show remembered container contents when looking at a block.
 * Soft dependency — class is only loaded when Jade is present (entrypoint).
 */
@WailaPlugin
public class ChestMemoryJadePlugin implements IWailaPlugin {
	@Override
	public void register(IWailaCommonRegistration registration) {
		// Client-only memory; no server data.
	}

	@Override
	public void registerClient(IWailaClientRegistration registration) {
		MemoryContainerComponentProvider provider = MemoryContainerComponentProvider.INSTANCE;
		// ChestBlock also covers TrappedChest / copper chest subclasses
		registration.registerBlockComponent(provider, ChestBlock.class);
		registration.registerBlockComponent(provider, BarrelBlock.class);
		registration.registerBlockComponent(provider, ShulkerBoxBlock.class);
		registration.registerBlockComponent(provider, HopperBlock.class);
		registration.registerBlockComponent(provider, DispenserBlock.class);
		registration.registerBlockComponent(provider, DropperBlock.class);
		registration.registerBlockComponent(provider, EnderChestBlock.class);
		registration.markAsClientFeature(MemoryContainerComponentProvider.UID);
	}
}
