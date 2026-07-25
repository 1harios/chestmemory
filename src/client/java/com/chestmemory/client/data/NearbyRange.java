package com.chestmemory.client.data;

import net.minecraft.network.chat.Component;

/**
 * Configurable radius for "nearby" mode.
 */
public enum NearbyRange {
	R32(32),
	R48(48),
	R64(64),
	R96(96),
	R128(128),
	R192(192),
	R256(256);

	private final int blocks;

	NearbyRange(int blocks) {
		this.blocks = blocks;
	}

	public int blocks() {
		return blocks;
	}

	public Component label() {
		return Component.translatable("screen.chestmemory.range.label", blocks);
	}

	public static NearbyRange fromBlocks(int blocks) {
		for (NearbyRange r : values()) {
			if (r.blocks == blocks) {
				return r;
			}
		}
		return R64;
	}
}
