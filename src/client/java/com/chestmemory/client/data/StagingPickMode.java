package com.chestmemory.client.data;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.BlockGetter;
import org.jspecify.annotations.Nullable;

import java.util.HashSet;
import java.util.Set;

/**
 * “Pick warehouse chests” mode: while active, every opened world chest is marked as
 * build-site staging. Turn off in the Ё panel when finished selecting.
 * <p>
 * Chat: one message per newly marked chest (not every scan tick).
 */
public final class StagingPickMode {
	private static boolean active;
	/** How many chests were newly marked in the current pick session. */
	private static int markedThisSession;
	/** Keys we already handled this pick session (canonical + both halves). */
	private static final Set<String> announcedKeys = new HashSet<>();

	private StagingPickMode() {
	}

	public static boolean isActive() {
		return active;
	}

	public static int markedThisSession() {
		return markedThisSession;
	}

	/** Toggle pick mode. Returns new state. */
	public static boolean toggle() {
		if (active) {
			stop(true);
			return false;
		}
		start();
		return true;
	}

	public static void start() {
		active = true;
		markedThisSession = 0;
		announcedKeys.clear();
		LocalPlayer player = Minecraft.getInstance().player;
		if (player != null) {
			player.sendSystemMessage(Component.translatable("message.chestmemory.staging_pick_on"));
		}
	}

	/**
	 * Stop marking without syncing anything to the hub.
	 * <p>
	 * {@link #stop(boolean)} pushes the full local warehouse to the active session, which is
	 * right when the player finishes marking — and wrong when the session is being replaced:
	 * the previous build's chest would be uploaded into the new gather. Callers that are
	 * switching gathers use this instead.
	 */
	public static void stopQuiet() {
		active = false;
		markedThisSession = 0;
		announcedKeys.clear();
	}

	public static void stop(boolean announce) {
		if (!active) {
			return;
		}
		active = false;
		if (announce) {
			LocalPlayer player = Minecraft.getInstance().player;
			if (player != null) {
				int total = ChestMemoryStorage.get().stagingCount();
				player.sendSystemMessage(Component.translatable(
					"message.chestmemory.staging_pick_off",
					markedThisSession,
					total
				));
			}
		}
		// Final sync of full warehouse list to clan hub
		Minecraft mc = Minecraft.getInstance();
		if (mc != null && com.chestmemory.client.clan.ClanSessionManager.isInSession()) {
			com.chestmemory.client.clan.ClanSessionManager.pushStagingKeysAsync(mc, true);
		}
		markedThisSession = 0;
		announcedKeys.clear();
	}

	/**
	 * Call when a world container is opened (scanner should call at most once per open GUI).
	 * One chat line per newly marked chest per pick session.
	 */
	public static void onWorldChestOpened(
		@Nullable BlockGetter level,
		String dimension,
		BlockPos rawPos
	) {
		if (!active || rawPos == null || dimension == null) {
			return;
		}
		BlockPos can = level != null
			? ContainerKeys.canonicalPos(level, rawPos)
			: rawPos.immutable();
		String key = ContainerKeys.blockKey(dimension, can);

		// Already handled this chest (or either half) this session — silent re-ensure
		if (isAnnounced(level, dimension, rawPos, can, key)) {
			ChestMemoryStorage.get().addStagingAt(level, dimension, rawPos);
			return;
		}

		boolean newly = ChestMemoryStorage.get().addStagingAt(level, dimension, rawPos);
		// Always remember so re-open / double-chest half flip never spams
		rememberAnnounced(level, dimension, rawPos, can, key);
		if (!newly) {
			// Was already staging from a previous session — no chat line
			return;
		}
		markedThisSession++;
		LocalPlayer player = Minecraft.getInstance().player;
		if (player != null) {
			player.sendSystemMessage(Component.translatable(
				"message.chestmemory.staging_picked",
				can.getX(), can.getY(), can.getZ(),
				ChestMemoryStorage.get().stagingCount()
			));
		}
		// Share warehouse position with clan so everyone glows the same drop-off
		Minecraft mc = Minecraft.getInstance();
		if (mc != null && com.chestmemory.client.clan.ClanSessionManager.isInSession()) {
			com.chestmemory.client.clan.ClanSessionManager.pushStagingKeysAsync(mc, false);
		}
	}

	private static boolean isAnnounced(
		@Nullable BlockGetter level,
		String dimension,
		BlockPos rawPos,
		BlockPos canonical,
		String canonicalKey
	) {
		if (announcedKeys.contains(canonicalKey)) {
			return true;
		}
		if (announcedKeys.contains(ContainerKeys.blockKey(dimension, rawPos))) {
			return true;
		}
		if (level != null) {
			BlockPos other = ContainerKeys.otherHalf(level, rawPos);
			if (other != null && announcedKeys.contains(ContainerKeys.blockKey(dimension, other))) {
				return true;
			}
		}
		return false;
	}

	private static void rememberAnnounced(
		@Nullable BlockGetter level,
		String dimension,
		BlockPos rawPos,
		BlockPos canonical,
		String canonicalKey
	) {
		announcedKeys.add(canonicalKey);
		announcedKeys.add(ContainerKeys.blockKey(dimension, rawPos));
		if (level != null) {
			BlockPos other = ContainerKeys.otherHalf(level, rawPos);
			if (other != null) {
				announcedKeys.add(ContainerKeys.blockKey(dimension, other));
			}
		}
		if (!rawPos.equals(canonical)) {
			announcedKeys.add(ContainerKeys.blockKey(dimension, canonical));
		}
	}
}
