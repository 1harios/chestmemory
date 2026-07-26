package com.chestmemory.client.data;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelData;
import org.jspecify.annotations.Nullable;

/**
 * Tells apart two worlds that report the same dimension id.
 * <p>
 * A multiworld server (Multiverse and friends) hosts several worlds behind one address, and
 * each of them has its own Overworld / Nether / End. The client is told only the vanilla
 * dimension key, so a farm world's Nether and a build world's Nether both arrive as
 * {@code minecraft:the_nether}. Everything the mod keys on — the container key, the nearby
 * filter, the per-dimension list — then treats them as one place: a chest at 100,64,200 in
 * one Nether collides with a chest at the same coordinates in the other.
 * <p>
 * There is no world id in the protocol to lean on, so this derives a fingerprint from what
 * the server does send. The world's respawn point is the most reliable such signal: it is
 * per-world, arrives with the join/respawn packet, and is stable for as long as the server
 * admin leaves it alone.
 * <p>
 * <b>It is a hint, not an identity.</b> Two worlds can share a spawn point, and an admin can
 * move one, so callers must treat a mismatch as "cannot prove these are the same place"
 * rather than "these are definitely different". Everything here is therefore used to
 * suppress destructive actions and misleading claims, never to delete data.
 */
public final class WorldFingerprint {
	private WorldFingerprint() {
	}

	/**
	 * Short, stable tag for the world the player is standing in, or null when the client has
	 * not been told enough to say.
	 * <p>
	 * Format is deliberately opaque and compact: it lands in every container record and in
	 * the profile file, so it should not balloon them.
	 */
	public static @Nullable String current(@Nullable Minecraft client) {
		if (client == null || client.level == null) {
			return null;
		}
		return of(client.level);
	}

	public static @Nullable String of(@Nullable Level level) {
		if (level == null) {
			return null;
		}
		LevelData.RespawnData respawn = level.getRespawnData();
		if (respawn == null) {
			return null;
		}
		BlockPos pos = respawn.pos();
		if (pos == null) {
			return null;
		}
		// The default respawn data is shared by every world that has not set one, so it says
		// nothing about which world this is — better to report "unknown" than a fake match.
		if (LevelData.RespawnData.DEFAULT.equals(respawn)) {
			return null;
		}
		return "s" + pos.getX() + "_" + pos.getY() + "_" + pos.getZ();
	}

	/**
	 * True when two fingerprints are known to describe different worlds.
	 * <p>
	 * Answers false whenever either side is unknown, which is the safe direction: an old
	 * record written before fingerprints existed has none, and treating that as "different"
	 * would hide every chest remembered before this update.
	 */
	public static boolean provablyDifferent(@Nullable String a, @Nullable String b) {
		if (a == null || a.isBlank() || b == null || b.isBlank()) {
			return false;
		}
		return !a.equals(b);
	}
}
