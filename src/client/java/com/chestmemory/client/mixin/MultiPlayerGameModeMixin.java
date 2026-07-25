package com.chestmemory.client.mixin;

import com.chestmemory.client.scan.LastInteractTracker;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.EnderChestBlock;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MultiPlayerGameMode.class)
public class MultiPlayerGameModeMixin {
	@Inject(method = "useItemOn", at = @At("HEAD"))
	private void chestmemory$trackInteract(
		LocalPlayer player,
		InteractionHand hand,
		BlockHitResult blockHit,
		CallbackInfoReturnable<InteractionResult> cir
	) {
		var pos = blockHit.getBlockPos();
		LastInteractTracker.set(pos);
		if (player != null && player.level() != null
			&& player.level().getBlockState(pos).getBlock() instanceof EnderChestBlock) {
			LastInteractTracker.markEnderChest(pos);
		} else {
			// New non-ender click — do not keep sticky ender flag
			LastInteractTracker.clearEnderChestPending();
		}
	}
}
