package com.chestmemory.client.data;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Tracks every dimension / multiworld the client has seen on this server.
 * <p>
 * Sources:
 * <ul>
 *   <li>current {@link Level#dimension()}</li>
 *   <li>login/respawn dimension list from {@link ClientPacketListener#levels()}</li>
 *   <li>dimensions of remembered containers</li>
 * </ul>
 * Many multiworld plugins register farm/build as separate dimension keys —
 * they show up in {@code levels()} even before you open a chest there.
 */
public final class MultiworldTracker {
	private static final Set<String> SESSION_KNOWN = new LinkedHashSet<>();
	/** Dimension sets change on world switches, not per tick — once a second is plenty. */
	private static final int TICK_INTERVAL = 20;
	private static int tickCounter;

	private MultiworldTracker() {
	}

	public static void tick(Minecraft client) {
		if (tickCounter++ % TICK_INTERVAL != 0) {
			return;
		}
		if (client.level == null) {
			return;
		}
		String current = ChestMemoryStorage.dimensionId(client.level);
		if (current != null) {
			SESSION_KNOWN.add(current);
			ChestMemoryStorage.get().rememberDimensionSeen(current);
		}
		ClientPacketListener conn = client.getConnection();
		if (conn != null) {
			for (ResourceKey<Level> key : conn.levels()) {
				if (key != null && key.identifier() != null) {
					String id = key.identifier().toString();
					SESSION_KNOWN.add(id);
					ChestMemoryStorage.get().rememberDimensionSeen(id);
				}
			}
		}
	}

	public static void clearSession() {
		SESSION_KNOWN.clear();
	}

	/** All dimension ids known this session + from storage. */
	public static Set<String> allKnown(@Nullable Iterable<ContainerRecord> containers) {
		Set<String> ids = new LinkedHashSet<>(SESSION_KNOWN);
		ids.addAll(ChestMemoryStorage.get().knownDimensions());
		if (containers != null) {
			for (ContainerRecord r : containers) {
				if (r.dimension() != null && !r.dimension().isBlank()) {
					ids.add(r.dimension());
				}
			}
		}
		Minecraft client = Minecraft.getInstance();
		if (client != null && client.level != null) {
			ids.add(ChestMemoryStorage.dimensionId(client.level));
		}
		if (client != null && client.getConnection() != null) {
			for (ResourceKey<Level> key : client.getConnection().levels()) {
				if (key != null && key.identifier() != null) {
					ids.add(key.identifier().toString());
				}
			}
		}
		return ids;
	}

	/**
	 * Human label for the place the player is standing right now.
	 * Prefers farm/build keywords over generic "Overworld".
	 */
	public static String currentPlaceLabel(@Nullable String dimensionId) {
		if (dimensionId == null || dimensionId.isBlank()) {
			return "?";
		}
		return DimensionChoice.prettyName(dimensionId);
	}

	/** True if id looks like a farm multiworld (delegates to DimensionChoice token match). */
	public static boolean isFarmWorld(String dimensionId) {
		if (dimensionId == null || dimensionId.isBlank()) {
			return false;
		}
		// prettyName returns the translated farm label when tokens match
		String p = DimensionChoice.prettyName(dimensionId).toLowerCase(Locale.ROOT);
		return p.contains("ферм") || p.contains("farm");
	}

	/** True if id looks like a build/creative multiworld. */
	public static boolean isBuildWorld(String dimensionId) {
		if (dimensionId == null || dimensionId.isBlank()) {
			return false;
		}
		String p = DimensionChoice.prettyName(dimensionId).toLowerCase(Locale.ROOT);
		return p.contains("постро") || p.contains("build") || p.contains("creative");
	}
}
