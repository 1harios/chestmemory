package com.chestmemory.client.data;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelData;
import org.jspecify.annotations.Nullable;

/**
 * Tells apart two worlds that report the same dimension id.
 * <p>
 * A multiworld server (Multiverse and friends, or several servers behind one proxy) hosts
 * several worlds behind one address, and each of them has its own Overworld / Nether / End.
 * The client is told only the vanilla dimension key, so a farm world's chest at 100,64,200
 * and a build world's chest at the same coordinates used to collide on one record.
 * <p>
 * The primary signal is the <b>hashed seed</b> from the login/respawn packet (captured by
 * {@code ClientPacketListenerMixin} into {@link WorldIdentity}): per-world, identical for
 * every player, stable across sessions. When a server hides its seed, the world's explicitly
 * set respawn point is used as a weaker fallback. Composition and comparison rules live in
 * {@link WorldTags}.
 * <p>
 * <b>A missing tag is "unknown", never "different".</b> Records written before tags existed
 * have none, and servers can offer nothing to fingerprint with; treating that as foreign
 * would hide or delete chests that are perfectly fine. Filtering may use this, deletion
 * must not.
 */
public final class WorldFingerprint {
	private WorldFingerprint() {
	}

	/**
	 * Short, stable tag for the world the player is standing in, or null when the client has
	 * not been told enough to say. Format is deliberately opaque and compact: it lands in
	 * every container record and in the profile file, so it should not balloon them.
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
		Long seed = WorldIdentity.seedFor(level);
		if (seed != null) {
			String tag = WorldTags.seedTag(seed);
			if (tag != null) {
				return tag;
			}
		}
		// No usable seed (hidden by the server, or the packet predates this mod's session):
		// fall back to the world's respawn point when one was explicitly set.
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
		return WorldTags.spawnTag(pos.getX(), pos.getY(), pos.getZ());
	}

	/**
	 * True when two fingerprints are known to describe different worlds.
	 * <p>
	 * Answers false whenever either side is unknown — including legacy-format tags from old
	 * profiles and tags built from different signals. See {@link WorldTags#provablyDifferent}.
	 */
	public static boolean provablyDifferent(@Nullable String a, @Nullable String b) {
		return WorldTags.provablyDifferent(a, b);
	}
}
