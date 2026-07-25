package com.chestmemory.client.data;

import net.minecraft.network.chat.Component;

/**
 * How items are ordered in the Ё panel grid.
 */
public enum SortMode {
	DISTANCE("distance"),
	COUNT("count"),
	NAME("name");

	private final String id;

	SortMode(String id) {
		this.id = id;
	}

	public String id() {
		return id;
	}

	public Component label() {
		return Component.translatable("screen.chestmemory.sort." + id);
	}

	public static SortMode fromId(String id) {
		if (id == null) {
			return DISTANCE;
		}
		for (SortMode m : values()) {
			if (m.id.equalsIgnoreCase(id)) {
				return m;
			}
		}
		return DISTANCE;
	}
}
