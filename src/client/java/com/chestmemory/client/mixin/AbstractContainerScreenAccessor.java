package com.chestmemory.client.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(AbstractContainerScreen.class)
public interface AbstractContainerScreenAccessor {
	@Accessor("leftPos")
	int chestmemory$getLeftPos();

	@Accessor("topPos")
	int chestmemory$getTopPos();

	/** Height of the window itself, so the take-hint can sit below it rather than over it. */
	@Accessor("imageHeight")
	int chestmemory$getImageHeight();

	@Accessor("imageWidth")
	int chestmemory$getImageWidth();
}
