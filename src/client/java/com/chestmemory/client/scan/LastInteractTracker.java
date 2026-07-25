package com.chestmemory.client.scan;

import net.minecraft.core.BlockPos;
import org.jspecify.annotations.Nullable;

/**
 * Remembers the last block the player right-clicked, used to map opened menus to world positions.
 * Also keeps a sticky “opened ender chest” flag until the container screen closes.
 */
public final class LastInteractTracker {
	private static @Nullable BlockPos lastPos;
	private static long lastTimeMillis;
	/** True after right-clicking an ender chest block; cleared when no container is open. */
	private static boolean enderChestPending;
	private static @Nullable BlockPos enderChestPos;

	private LastInteractTracker() {
	}

	public static void set(BlockPos pos) {
		lastPos = pos.immutable();
		lastTimeMillis = System.currentTimeMillis();
	}

	/** Mark that the last interaction was an ender chest (sticky until cleared). */
	public static void markEnderChest(BlockPos pos) {
		set(pos);
		enderChestPending = true;
		enderChestPos = pos.immutable();
	}

	public static boolean isEnderChestPending() {
		return enderChestPending;
	}

	public static @Nullable BlockPos enderChestPos() {
		return enderChestPos;
	}

	public static void clearEnderChestPending() {
		enderChestPending = false;
		enderChestPos = null;
	}

	public static @Nullable BlockPos getRecent(long maxAgeMillis) {
		if (lastPos == null) {
			return null;
		}
		if (System.currentTimeMillis() - lastTimeMillis > maxAgeMillis) {
			return null;
		}
		return lastPos;
	}

	/** Prefer sticky ender pos, then recent interact, then any last pos. */
	public static @Nullable BlockPos getForScan(long maxAgeMillis) {
		if (enderChestPending && enderChestPos != null) {
			return enderChestPos;
		}
		return getRecent(maxAgeMillis);
	}

	public static @Nullable BlockPos get() {
		return lastPos;
	}

	public static void clear() {
		lastPos = null;
		clearEnderChestPending();
	}
}
