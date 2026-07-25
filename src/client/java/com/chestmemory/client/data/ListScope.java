package com.chestmemory.client.data;

import net.minecraft.network.chat.Component;

/**
 * How the Ё panel aggregates containers.
 */
public enum ListScope {
	/** Only containers near the player (current dimension, live world). */
	NEARBY("nearby"),
	/** Everything remembered in the selected world/server profile. */
	WORLD_TOTAL("world_total");

	private final String id;

	ListScope(String id) {
		this.id = id;
	}

	public String id() {
		return id;
	}

	public Component label() {
		return Component.translatable("screen.chestmemory.scope." + id);
	}
}
