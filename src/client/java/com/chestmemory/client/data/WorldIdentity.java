package com.chestmemory.client.data;

import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Session-scoped bridge between the network layer and {@link WorldFingerprint}: which hashed
 * seed belongs to which client level.
 * <p>
 * The server announces a per-world hashed seed in every login and respawn packet — including
 * the respawn a multiworld plugin fires when a custom portal moves the player between its
 * worlds. {@code ClientPacketListenerMixin} hands that seed here; the level object the packet
 * produces is bound to it, either directly at the end of the handler or lazily on the first
 * query for a level we have not seen (the packet always arrives before the level exists).
 * <p>
 * All access happens on the render thread (packet handlers are re-dispatched there, and every
 * caller is tick/render code), but the map is guarded anyway — it is tiny and cold.
 */
public final class WorldIdentity {
	/** Levels are short-lived on multiworld servers; weak keys let dead ones drop out. */
	private static final Map<Level, Long> SEED_BY_LEVEL = new WeakHashMap<>();
	/** Seed announced by the most recent login/respawn packet, waiting for its level. */
	private static volatile @Nullable Long pendingSeed;

	private WorldIdentity() {
	}

	/** Called from the packet mixin at the start of login/respawn handling. */
	public static void onSpawnPacket(long hashedSeed) {
		pendingSeed = hashedSeed;
	}

	/**
	 * Called from the packet mixin after the handler ran, when the new level already exists.
	 * Binding here is exact; the lazy path below is only a fallback.
	 */
	public static void bind(@Nullable Level level) {
		if (level == null) {
			return;
		}
		Long seed = pendingSeed;
		if (seed == null) {
			return;
		}
		synchronized (SEED_BY_LEVEL) {
			SEED_BY_LEVEL.put(level, seed);
		}
		pendingSeed = null;
	}

	/** Hashed seed for this level, or null when the server never told us. */
	public static @Nullable Long seedFor(@Nullable Level level) {
		if (level == null) {
			return null;
		}
		synchronized (SEED_BY_LEVEL) {
			Long known = SEED_BY_LEVEL.get(level);
			if (known != null) {
				return known;
			}
			// The packet that created this level arrived before the level object did; claim
			// the announced seed on first sight. Only ever the current level is queried, so
			// a stale pending value cannot attach to the wrong world.
			Long pending = pendingSeed;
			if (pending != null) {
				SEED_BY_LEVEL.put(level, pending);
				pendingSeed = null;
				return pending;
			}
			return null;
		}
	}

	/** Forget everything — called on disconnect so nothing leaks across servers. */
	public static void clear() {
		pendingSeed = null;
		synchronized (SEED_BY_LEVEL) {
			SEED_BY_LEVEL.clear();
		}
	}
}
