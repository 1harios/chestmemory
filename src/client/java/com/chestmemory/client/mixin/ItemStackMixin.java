package com.chestmemory.client.mixin;

import com.chestmemory.client.tooltip.EnderChestTooltip;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

/**
 * Attach ender-chest icon-grid tooltip image when hovering the ender chest item.
 */
@Mixin(ItemStack.class)
public class ItemStackMixin {
	@Inject(method = "getTooltipImage", at = @At("RETURN"), cancellable = true)
	private void chestmemory$enderChestIcons(CallbackInfoReturnable<Optional<TooltipComponent>> cir) {
		ItemStack self = (ItemStack) (Object) this;
		if (self.isEmpty() || !self.is(Items.ENDER_CHEST)) {
			return;
		}
		// Prefer our grid when we have scanned contents; otherwise keep vanilla (empty)
		Optional<TooltipComponent> ours = EnderChestTooltip.createImageComponent();
		if (ours.isPresent()) {
			cir.setReturnValue(ours);
		}
	}
}
