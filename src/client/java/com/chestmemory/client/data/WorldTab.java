package com.chestmemory.client.data;

import net.minecraft.network.chat.Component;

/**
 * A remembered world/server profile shown as a tab in the panel.
 */
public final class WorldTab {
	private final String id;
	private final String displayName;
	private final boolean live;
	private final int containerCount;

	public WorldTab(String id, String displayName, boolean live, int containerCount) {
		this.id = id;
		this.displayName = displayName;
		this.live = live;
		this.containerCount = containerCount;
	}

	public String id() {
		return id;
	}

	public String displayName() {
		return displayName;
	}

	/** True if this is the world/server you are currently connected to. */
	public boolean live() {
		return live;
	}

	public int containerCount() {
		return containerCount;
	}

	public Component buttonLabel() {
		String mark = live ? "★ " : "";
		return Component.literal(mark + displayName + " (" + containerCount + ")");
	}
}
