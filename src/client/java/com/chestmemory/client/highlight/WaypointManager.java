package com.chestmemory.client.highlight;

import com.chestmemory.client.data.ContainerRecord;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Legacy API kept for screen code.
 * <p>
 * Glow + distance are fully handled by {@link ChestHighlighter} so we never stack
 * multiple outlines on the same chest. Methods are no-ops (except {@link #clear()}).
 */
public final class WaypointManager {
	private WaypointManager() {
	}

	public static void clear() {
		// no-op: ChestHighlighter owns active state
	}

	public static void addForContainers(String itemId, List<ContainerRecord> records, long durationMillis) {
		// no-op — ChestHighlighter.highlightItem + live memory drive the glow
	}

	public static void addNearest(String itemId, @Nullable ContainerRecord nearest, long durationMillis) {
		// no-op
	}

	public static void tick(Minecraft client) {
		// no-op — rendering is in ChestHighlighter.tick
	}

	public static int activeCount() {
		return ChestHighlighter.isActive() ? 1 : 0;
	}
}
