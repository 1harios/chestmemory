package com.chestmemory.client.data;

import com.chestmemory.ChestMemoryMod;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Client-side persistence of container contents.
 * <p>
 * Each singleplayer world and each multiplayer server has its own file.
 * The UI can switch tabs to browse other profiles without mixing live writes.
 */
public final class ChestMemoryStorage {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Type MAP_TYPE = new TypeToken<Map<String, ContainerRecord>>() {}.getType();
	private static final DateTimeFormatter EXPORT_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
	/** Bumped when the on-disk profile schema changes in a non-backwards-compatible way. */
	private static final int FORMAT_VERSION = 2;
	/** Upper bounds for staging keys accepted from the clan hub (untrusted input). */
	private static final int MAX_STAGING_KEYS = 4096;
	private static final int MAX_STAGING_KEY_LENGTH = 256;

	private static volatile ChestMemoryStorage instance;

	/** Profile currently connected to (writes always go here). */
	private String liveWorldId;
	private String liveDisplayName = "";
	private final Map<String, ContainerRecord> liveContainers = new LinkedHashMap<>();
	/** Dimensions / multiworlds seen on this profile (farm, build, …). */
	private final Set<String> liveKnownDimensions = new LinkedHashSet<>();
	/**
	 * Build-site warehouse (staging) container keys — count as “already collected”,
	 * never used as gather sources. Multiple chests allowed.
	 */
	private final Set<String> liveStagingKeys = new LinkedHashSet<>();
	private boolean liveDirty;
	/**
	 * Set when the live profile could not be parsed from disk. While true, saving is
	 * suppressed so a single parse error can't wipe an otherwise recoverable profile.
	 */
	private boolean liveLoadFailed;
	/**
	 * Cached immutable snapshot of {@link #liveContainers} values.
	 * Rebuilt lazily; invalidated on every mutation of the live map so per-tick
	 * consumers (highlighter, Jade) don't copy the whole list each call.
	 */
	private @Nullable List<ContainerRecord> liveSnapshotCache;
	/**
	 * itemId -> keys of live containers holding it.
	 * <p>
	 * Every lookup ("how much iron do I have", "which chests glow") used to walk the whole
	 * profile, once per tick for the highlighter and once per material for the gather
	 * queue. That is O(containers) per question on a base with thousands of chests.
	 * The index is maintained incrementally in {@link #remember}/{@link #forget}, the only
	 * places that mutate liveContainers, so it cannot drift out of sync.
	 */
	private final Map<String, Set<String>> liveItemIndex = new HashMap<>();

	/** Profile shown in the Ё panel (may differ from live). */
	private String viewingWorldId;
	private String viewingDisplayName = "";
	private Map<String, ContainerRecord> viewingContainers = liveContainers;
	private Set<String> viewingKnownDimensions = liveKnownDimensions;

	private ChestMemoryStorage() {
	}

	public static ChestMemoryStorage get() {
		ChestMemoryStorage local = instance;
		if (local == null) {
			synchronized (ChestMemoryStorage.class) {
				local = instance;
				if (local == null) {
					local = new ChestMemoryStorage();
					instance = local;
				}
			}
		}
		return local;
	}

	/**
	 * Stable profile id: one file per SP world name or MP server address (host:port).
	 */
	public static String resolveWorldId(Minecraft client) {
		if (client.isLocalServer()) {
			IntegratedServer server = client.getSingleplayerServer();
			if (server != null) {
				String name = server.getWorldData().getLevelName();
				return "sp_" + sanitize(name);
			}
			return null;
		}
		return resolveMultiplayerWorldId(client);
	}

	private static @Nullable String resolveMultiplayerWorldId(Minecraft client) {

		ServerData server = client.getCurrentServer();
		if (server != null && server.ip != null && !server.ip.isBlank()) {
			// Always key by address (host:port), never merge different servers
			return "mp_" + sanitizeAddress(server.ip);
		}

		ClientPacketListener connection = client.getConnection();
		if (connection != null && connection.getServerData() != null
			&& connection.getServerData().ip != null && !connection.getServerData().ip.isBlank()) {
			return "mp_" + sanitizeAddress(connection.getServerData().ip);
		}

		return null;
	}

	public static String resolveDisplayName(Minecraft client) {
		if (client.isLocalServer()) {
			IntegratedServer server = client.getSingleplayerServer();
			if (server != null) {
				return server.getWorldData().getLevelName();
			}
			return "Singleplayer";
		}
		ServerData server = client.getCurrentServer();
		if (server != null) {
			String name = server.name != null && !server.name.isBlank() ? server.name : server.ip;
			return name + " [" + server.ip + "]";
		}
		return "Multiplayer";
	}

	/**
	 * Filesystem-safe slug. ASCII-only by design (profile ids become file names), so any
	 * non-latin name would collapse to the empty string on its own — "Новый мир" and
	 * "Мой мир" would both map to the id {@code sp_}, sharing a single profile file.
	 * A short hash of the original name is therefore always appended to keep distinct
	 * worlds distinct, including two worlds that merely share a display name.
	 */
	private static String sanitize(String raw) {
		String slug = raw.toLowerCase(Locale.ROOT)
			.replaceAll("[^a-z0-9._-]+", "_")
			.replaceAll("_+", "_")
			.replaceAll("^_|_$", "");
		String hash = shortHash(raw);
		return slug.isEmpty() ? hash : slug + "_" + hash;
	}

	/** Stable 8-hex-digit fingerprint of the raw name (not security relevant). */
	private static String shortHash(String raw) {
		long h = 1125899906842597L; // FNV-ish seed
		for (int i = 0; i < raw.length(); i++) {
			h = 31 * h + raw.charAt(i);
		}
		return String.format(Locale.ROOT, "%08x", (int) (h ^ (h >>> 32)));
	}

	/**
	 * Pre-hash profile id, used to migrate files written by earlier versions.
	 * May be empty or collide across worlds — that was exactly the bug.
	 */
	private static String legacySanitize(String raw) {
		return raw.toLowerCase(Locale.ROOT)
			.replaceAll("[^a-z0-9._-]+", "_")
			.replaceAll("_+", "_")
			.replaceAll("^_|_$", "");
	}

	/**
	 * Keep host and port distinguishable: play.example.com:25565 → play_example_com_25565.
	 * Server addresses are already ASCII, so no hash suffix is needed (and adding one
	 * would orphan every existing multiplayer profile).
	 */
	private static String sanitizeAddress(String address) {
		String a = address.trim().toLowerCase(Locale.ROOT);
		// strip path junk
		int slash = a.indexOf('/');
		if (slash >= 0) {
			a = a.substring(0, slash);
		}
		return legacySanitize(a);
	}

	public synchronized void ensureLoaded(Minecraft client) {
		String worldId = resolveWorldId(client);
		if (worldId == null) {
			return;
		}
		String display = resolveDisplayName(client);
		if (worldId.equals(liveWorldId)) {
			liveDisplayName = display;
			// keep meta display name fresh
			return;
		}
		saveIfNeeded();
		migrateLegacyProfile(client, worldId);
		liveWorldId = worldId;
		liveDisplayName = display;
		liveContainers.clear();
		liveSnapshotCache = null;
		liveKnownDimensions.clear();
		liveStagingKeys.clear();
		liveDirty = false;
		WorldFile file = loadFromDisk(worldId);
		liveLoadFailed = file.loadFailed;
		if (file.containers != null) {
			liveContainers.putAll(file.containers);
		}
		reindexAll();
		if (file.knownDimensions != null) {
			liveKnownDimensions.addAll(file.knownDimensions);
		}
		if (file.stagingKeys != null) {
			liveStagingKeys.addAll(file.stagingKeys);
		}
		// Harvest dimensions already stored on containers
		for (ContainerRecord r : liveContainers.values()) {
			if (r.dimension() != null && !r.dimension().isBlank()) {
				liveKnownDimensions.add(r.dimension());
			}
		}
		// If UI was following live, keep following
		if (viewingWorldId == null || viewingContainers == liveContainers
			|| (viewingWorldId != null && viewingWorldId.equals(liveWorldId))) {
			viewingWorldId = liveWorldId;
			viewingDisplayName = liveDisplayName;
			viewingContainers = liveContainers;
			viewingKnownDimensions = liveKnownDimensions;
		}
		ChestMemoryMod.LOGGER.info(
			"Live profile {} ({}), {} containers, {} dims",
			worldId, display, liveContainers.size(), liveKnownDimensions.size()
		);
	}

	public synchronized void unload() {
		saveIfNeeded();
		liveContainers.clear();
		liveItemIndex.clear();
		liveSnapshotCache = null;
		liveKnownDimensions.clear();
		liveStagingKeys.clear();
		liveWorldId = null;
		liveDisplayName = "";
		liveDirty = false;
		liveLoadFailed = false;
		viewingWorldId = null;
		viewingDisplayName = "";
		viewingContainers = liveContainers;
		viewingKnownDimensions = liveKnownDimensions;
		MultiworldTracker.clearSession();
	}

	/** Remember a multiworld / dimension id for the live profile. */
	public synchronized void rememberDimensionSeen(String dimensionId) {
		if (dimensionId == null || dimensionId.isBlank() || liveWorldId == null) {
			return;
		}
		if (liveKnownDimensions.add(dimensionId)) {
			liveDirty = true;
		}
	}

	public synchronized Set<String> knownDimensions() {
		return new LinkedHashSet<>(viewingKnownDimensions != null ? viewingKnownDimensions : liveKnownDimensions);
	}

	private Path worldsDir() {
		Path dir = FabricLoader.getInstance().getConfigDir().resolve(ChestMemoryMod.MOD_ID).resolve("worlds");
		try {
			Files.createDirectories(dir);
		} catch (IOException e) {
			ChestMemoryMod.LOGGER.error("Failed to create config directory", e);
		}
		return dir;
	}

	private Path exportDir() {
		Path dir = FabricLoader.getInstance().getConfigDir().resolve(ChestMemoryMod.MOD_ID).resolve("exports");
		try {
			Files.createDirectories(dir);
		} catch (IOException e) {
			ChestMemoryMod.LOGGER.error("Failed to create export directory", e);
		}
		return dir;
	}

	private Path worldFile(String worldId) {
		return worldsDir().resolve(worldId + ".json");
	}

	/**
	 * Singleplayer profile ids gained a hash suffix (see {@link #sanitize}). Rename the
	 * pre-hash file to the new id once, so existing memory isn't orphaned by the upgrade.
	 * Skipped when the new file already exists or the legacy slug was empty — an empty
	 * slug means the old file was the shared {@code sp_.json} bucket that several worlds
	 * may have written to, and silently claiming it for one world would be wrong.
	 */
	private void migrateLegacyProfile(Minecraft client, String worldId) {
		if (!worldId.startsWith("sp_") || !client.isLocalServer()) {
			return;
		}
		IntegratedServer server = client.getSingleplayerServer();
		if (server == null) {
			return;
		}
		String legacySlug = legacySanitize(server.getWorldData().getLevelName());
		if (legacySlug.isEmpty()) {
			return;
		}
		Path legacy = worldFile("sp_" + legacySlug);
		Path target = worldFile(worldId);
		if (legacy.equals(target) || !Files.isRegularFile(legacy) || Files.exists(target)) {
			return;
		}
		try {
			Files.move(legacy, target);
			ChestMemoryMod.LOGGER.info("Migrated profile {} -> {}", legacy.getFileName(), target.getFileName());
		} catch (IOException e) {
			ChestMemoryMod.LOGGER.warn("Could not migrate profile {}: {}", legacy.getFileName(), e.toString());
		}
	}

	private static final class WorldFile {
		Map<String, String> meta = new LinkedHashMap<>();
		Map<String, ContainerRecord> containers = new LinkedHashMap<>();
		List<String> knownDimensions = new ArrayList<>();
		List<String> stagingKeys = new ArrayList<>();
		/**
		 * True when the profile file exists but could not be parsed. Callers must never
		 * save over a profile in this state — doing so replaces recoverable data with an
		 * empty file.
		 */
		boolean loadFailed;
	}

	private WorldFile loadFromDisk(String worldId) {
		WorldFile result = new WorldFile();
		Path file = worldFile(worldId);
		if (!Files.isRegularFile(file)) {
			return result;
		}
		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			JsonElement root = JsonParser.parseReader(reader);
			if (root == null || root.isJsonNull()) {
				return result;
			}
			if (root.isJsonObject()) {
				JsonObject obj = root.getAsJsonObject();
				if (obj.has("containers") && obj.get("containers").isJsonObject()) {
					if (obj.has("meta") && obj.get("meta").isJsonObject()) {
						JsonObject meta = obj.getAsJsonObject("meta");
						for (String key : meta.keySet()) {
							result.meta.put(key, meta.get(key).getAsString());
						}
					}
					result.containers = GSON.fromJson(obj.get("containers"), MAP_TYPE);
					if (result.containers == null) {
						result.containers = new LinkedHashMap<>();
					}
					if (obj.has("knownDimensions") && obj.get("knownDimensions").isJsonArray()) {
						result.knownDimensions = new ArrayList<>();
						obj.getAsJsonArray("knownDimensions").forEach(el -> {
							if (el != null && el.isJsonPrimitive()) {
								result.knownDimensions.add(el.getAsString());
							}
						});
					}
					if (obj.has("stagingKeys") && obj.get("stagingKeys").isJsonArray()) {
						result.stagingKeys = new ArrayList<>();
						obj.getAsJsonArray("stagingKeys").forEach(el -> {
							if (el != null && el.isJsonPrimitive()) {
								result.stagingKeys.add(el.getAsString());
							}
						});
					}
					return result;
				}
			}
			// Legacy: raw map of containers
			result.containers = GSON.fromJson(root, MAP_TYPE);
			if (result.containers == null) {
				result.containers = new LinkedHashMap<>();
			}
		} catch (Exception e) {
			// Parse failure: keep the file on disk untouched and refuse to overwrite it.
			result.loadFailed = true;
			result.containers = new LinkedHashMap<>();
			ChestMemoryMod.LOGGER.error(
				"Failed to load chest memory for {} — the profile will NOT be overwritten. "
					+ "Fix or remove {} to start fresh.",
				worldId, file, e
			);
		}
		return result;
	}

	public synchronized void saveIfNeeded() {
		if (!liveDirty || liveWorldId == null) {
			return;
		}
		if (liveLoadFailed) {
			// The on-disk profile could not be parsed. Writing now would replace data that
			// is very likely still recoverable by hand with whatever little we have in memory.
			return;
		}
		Path file = worldFile(liveWorldId);
		Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
		try {
			JsonObject root = new JsonObject();
			JsonObject meta = new JsonObject();
			meta.addProperty("displayName", liveDisplayName);
			meta.addProperty("id", liveWorldId);
			meta.addProperty("kind", liveWorldId.startsWith("mp_") ? "multiplayer" : "singleplayer");
			meta.addProperty("formatVersion", String.valueOf(FORMAT_VERSION));
			root.add("meta", meta);
			root.add("containers", GSON.toJsonTree(liveContainers, MAP_TYPE));
			root.add("knownDimensions", GSON.toJsonTree(new ArrayList<>(liveKnownDimensions)));
			root.add("stagingKeys", GSON.toJsonTree(new ArrayList<>(liveStagingKeys)));

			// Serialize fully before touching the destination: a crash mid-write must never
			// leave a truncated profile behind.
			try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
				GSON.toJson(root, writer);
			}
			// Keep one generation of backup, then swap the new file in atomically.
			if (Files.isRegularFile(file)) {
				Path backup = file.resolveSibling(file.getFileName() + ".bak");
				try {
					Files.move(file, backup, StandardCopyOption.REPLACE_EXISTING);
				} catch (IOException e) {
					ChestMemoryMod.LOGGER.warn("Could not refresh backup for {}: {}", liveWorldId, e.toString());
				}
			}
			try {
				Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException e) {
				Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
			}
			liveDirty = false;
		} catch (Exception e) {
			// Catch Exception, not IOException: Gson throws unchecked JsonIOException, which
			// would otherwise escape the client tick and crash the game.
			ChestMemoryMod.LOGGER.error("Failed to save chest memory for {}", liveWorldId, e);
			try {
				Files.deleteIfExists(tmp);
			} catch (IOException ignored) {
				// best effort
			}
		}
	}

	/** Writes always go to the live (connected) profile only. */
	public synchronized void remember(ContainerRecord record) {
		if (liveWorldId == null) {
			return;
		}
		String key = record.positionKey();
		ContainerRecord previous = liveContainers.put(key, record);
		if (previous != null) {
			unindex(key, previous);
		}
		index(key, record);
		liveSnapshotCache = null;
		liveDirty = true;
	}

	public synchronized void forget(String key) {
		ContainerRecord removed = liveContainers.remove(key);
		if (removed != null) {
			unindex(key, removed);
			liveSnapshotCache = null;
			liveDirty = true;
		}
	}

	private void index(String key, ContainerRecord record) {
		for (String itemId : record.items().keySet()) {
			liveItemIndex.computeIfAbsent(itemId, k -> new LinkedHashSet<>()).add(key);
		}
	}

	private void unindex(String key, ContainerRecord record) {
		for (String itemId : record.items().keySet()) {
			Set<String> keys = liveItemIndex.get(itemId);
			if (keys != null && keys.remove(key) && keys.isEmpty()) {
				liveItemIndex.remove(itemId);
			}
		}
	}

	/** Rebuild the whole index — used after bulk loads and clears. */
	private void reindexAll() {
		liveItemIndex.clear();
		for (Map.Entry<String, ContainerRecord> e : liveContainers.entrySet()) {
			index(e.getKey(), e.getValue());
		}
	}

	/** Live containers known to hold this item (index lookup, no full scan). */
	private List<ContainerRecord> indexedContainers(String itemId) {
		Set<String> keys = liveItemIndex.get(itemId);
		if (keys == null || keys.isEmpty()) {
			return List.of();
		}
		List<ContainerRecord> out = new ArrayList<>(keys.size());
		for (String k : keys) {
			ContainerRecord r = liveContainers.get(k);
			if (r != null) {
				out.add(r);
			}
		}
		return out;
	}

	public synchronized void forgetAt(String dimension, BlockPos pos) {
		forget(ContainerRecord.makeKey(dimension, pos.getX(), pos.getY(), pos.getZ()));
	}

	/**
	 * Snapshot of the live profile's world-block containers.
	 * <p>
	 * A copy, because the caller ({@link com.chestmemory.client.scan.ContainerVerifier})
	 * removes entries while walking the result, which would otherwise fault the map.
	 * Virtual records (ender chest, inventory shulkers) are excluded: they have no block in
	 * the world to verify.
	 */
	public synchronized List<ContainerRecord> liveWorldBlockRecords() {
		List<ContainerRecord> out = new ArrayList<>(liveContainers.size());
		for (ContainerRecord r : liveContainers.values()) {
			if (r != null && r.isWorldBlock()) {
				out.add(r);
			}
		}
		return out;
	}

	/**
	 * Store a world-block container under a canonical double-chest key and drop the other half entry.
	 */
	public synchronized void rememberBlockContainer(
		BlockGetter level,
		String dimension,
		BlockPos rawPos,
		String type,
		Map<String, Integer> items
	) {
		if (liveWorldId == null) {
			return;
		}
		BlockPos canonical = ContainerKeys.canonicalPos(level, rawPos);
		boolean dbl = ContainerKeys.isDoubleChest(level, rawPos);
		String finalType = dbl ? "double_chest" : type;

		BlockPos other = ContainerKeys.otherHalf(level, rawPos);
		if (other != null) {
			// Remove non-canonical half if it was stored earlier
			forget(ContainerKeys.blockKey(dimension, other));
			forget(ContainerKeys.blockKey(dimension, rawPos));
		}

		ContainerRecord record = new ContainerRecord(finalType, dimension, canonical.getX(), canonical.getY(), canonical.getZ());
		record.setItems(items);
		record.setDoubleChest(dbl);
		if (other != null) {
			// Always store the other half relative to canonical for full glow
			BlockPos otherFromCanonical = ContainerKeys.otherHalf(level, canonical);
			if (otherFromCanonical != null) {
				record.setOtherHalf(otherFromCanonical.getX(), otherFromCanonical.getY(), otherFromCanonical.getZ());
			} else {
				record.setOtherHalf(other.getX(), other.getY(), other.getZ());
			}
		}
		record.setLastSeenMillis(System.currentTimeMillis());
		// If chest is now empty of everything, still keep record (user may refill);
		// callers clear highlight when selected item is gone.
		remember(record);
	}

	/** Total count of an item across live containers (optionally nearby). */
	public synchronized int liveItemTotal(String itemId) {
		int total = 0;
		// Index lookup: only containers that actually hold the item, not the whole profile.
		for (ContainerRecord r : indexedContainers(itemId)) {
			total += r.countOf(itemId);
		}
		return total;
	}

	/** Ender-chest memory for this profile, if any. */
	public synchronized @Nullable ContainerRecord findEnderChest() {
		// Prefer dedicated virtual key on live profile
		ContainerRecord keyed = liveContainers.get("virtual|ender_chest");
		if (keyed != null) {
			return keyed;
		}
		// Non-empty first, then any ender record
		ContainerRecord empty = null;
		for (ContainerRecord r : liveContainers.values()) {
			if (!isEnderRecord(r)) {
				continue;
			}
			if (!r.items().isEmpty()) {
				return r;
			}
			if (empty == null) {
				empty = r;
			}
		}
		// Also check currently viewed profile (browsing another save)
		if (viewingContainers != liveContainers) {
			ContainerRecord viewKeyed = viewingContainers.get("virtual|ender_chest");
			if (viewKeyed != null) {
				return viewKeyed;
			}
			for (ContainerRecord r : viewingContainers.values()) {
				if (!isEnderRecord(r)) {
					continue;
				}
				if (!r.items().isEmpty()) {
					return r;
				}
				if (empty == null) {
					empty = r;
				}
			}
		}
		return empty;
	}

	private static boolean isEnderRecord(ContainerRecord r) {
		if (r == null) {
			return false;
		}
		if (r.virtualId() != null && r.virtualId().equalsIgnoreCase("ender_chest")) {
			return true;
		}
		String type = r.type() == null ? "" : r.type().toLowerCase(Locale.ROOT);
		return type.equals("ender_chest") || type.contains("ender");
	}

	public synchronized void clearAll() {
		// Clear currently viewed profile if live; only live can be cleared for safety
		if (viewingContainers == liveContainers && !liveContainers.isEmpty()) {
			// Months of scanning can go in one click and there is no undo, so keep the
			// pre-clear file under a distinct name rather than letting the normal .bak
			// rotation overwrite it on the very next save.
			snapshotBeforeClear();
			liveContainers.clear();
			liveItemIndex.clear();
			liveSnapshotCache = null;
			liveStagingKeys.clear();
			liveDirty = true;
			saveIfNeeded();
		}
	}

	/** Copy the live profile to {@code <id>.json.before-clear} (best effort). */
	private void snapshotBeforeClear() {
		if (liveWorldId == null) {
			return;
		}
		Path file = worldFile(liveWorldId);
		if (!Files.isRegularFile(file)) {
			return;
		}
		Path backup = file.resolveSibling(file.getFileName() + ".before-clear");
		try {
			Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING);
			ChestMemoryMod.LOGGER.info("Saved pre-clear backup: {}", backup.getFileName());
		} catch (IOException e) {
			// Not fatal — the user asked to clear, so don't block that on a failed copy.
			ChestMemoryMod.LOGGER.warn("Could not write pre-clear backup: {}", e.toString());
		}
	}

	// ── build-site warehouse (staging) ─────────────────────────────────────

	public synchronized boolean isStagingKey(String key) {
		return key != null && liveStagingKeys.contains(key);
	}

	public synchronized boolean isStaging(ContainerRecord record) {
		if (record == null || liveStagingKeys.isEmpty()) {
			return false;
		}
		if (isStagingKey(record.positionKey())) {
			return true;
		}
		// Double-chest: mark may be on either half key
		if (record.isWorldBlock() && record.dimension() != null) {
			if (isStagingKey(ContainerRecord.makeKey(record.dimension(), record.x(), record.y(), record.z()))) {
				return true;
			}
			if (record.hasOtherHalf()) {
				return isStagingKey(ContainerRecord.makeKey(
					record.dimension(), record.otherX(), record.otherY(), record.otherZ()
				));
			}
		}
		return false;
	}

	public synchronized int stagingCount() {
		return liveStagingKeys.size();
	}

	public synchronized Set<String> stagingKeysSnapshot() {
		return new LinkedHashSet<>(liveStagingKeys);
	}

	/**
	 * Add a world block as staging (idempotent). Double-chest → canonical key.
	 * @return true if newly added, false if already staging / invalid
	 */
	public synchronized boolean addStagingAt(
		@Nullable BlockGetter level,
		String dimension,
		BlockPos rawPos
	) {
		if (liveWorldId == null || dimension == null || rawPos == null) {
			return false;
		}
		BlockPos canonical = level != null
			? ContainerKeys.canonicalPos(level, rawPos)
			: rawPos.immutable();
		String key = ContainerKeys.blockKey(dimension, canonical);
		// Already marked (idempotent — do not remove/re-add; that spammed chat every tick)
		if (liveStagingKeys.contains(key)) {
			return false;
		}
		// Drop only non-canonical half keys (never the canonical key itself)
		if (level != null) {
			BlockPos other = ContainerKeys.otherHalf(level, rawPos);
			if (other != null) {
				String otherKey = ContainerKeys.blockKey(dimension, other);
				if (!otherKey.equals(key)) {
					liveStagingKeys.remove(otherKey);
				}
			}
			String rawKey = ContainerKeys.blockKey(dimension, rawPos);
			if (!rawKey.equals(key)) {
				liveStagingKeys.remove(rawKey);
			}
		}
		if (liveStagingKeys.contains(key)) {
			return false;
		}
		liveStagingKeys.add(key);
		liveDirty = true;
		saveIfNeeded();
		return true;
	}

	/**
	 * Toggle staging for a world block (double-chest → canonical key).
	 * @return true if now staging, false if removed
	 */
	public synchronized boolean toggleStagingAt(
		@Nullable BlockGetter level,
		String dimension,
		BlockPos rawPos
	) {
		if (liveWorldId == null || dimension == null || rawPos == null) {
			return false;
		}
		BlockPos canonical = level != null
			? ContainerKeys.canonicalPos(level, rawPos)
			: rawPos.immutable();
		String key = ContainerKeys.blockKey(dimension, canonical);
		if (level != null) {
			BlockPos other = ContainerKeys.otherHalf(level, rawPos);
			if (other != null) {
				liveStagingKeys.remove(ContainerKeys.blockKey(dimension, other));
				liveStagingKeys.remove(ContainerKeys.blockKey(dimension, rawPos));
			}
		}
		boolean nowOn;
		if (liveStagingKeys.contains(key)) {
			liveStagingKeys.remove(key);
			nowOn = false;
		} else {
			liveStagingKeys.add(key);
			nowOn = true;
		}
		liveDirty = true;
		saveIfNeeded();
		return nowOn;
	}

	public synchronized void clearStaging() {
		if (liveStagingKeys.isEmpty()) {
			return;
		}
		liveStagingKeys.clear();
		liveDirty = true;
		saveIfNeeded();
	}

	/** Merge remote/clan warehouse keys into local staging set. */
	public synchronized int mergeStagingKeys(java.util.Collection<String> keys) {
		if (keys == null || keys.isEmpty()) {
			return 0;
		}
		int added = 0;
		int rejected = 0;
		for (String k : keys) {
			if (liveStagingKeys.size() >= MAX_STAGING_KEYS) {
				rejected += 1;
				continue;
			}
			// These keys arrive from the clan hub, i.e. from outside this client. Anything
			// that is not a well-formed "dimension|x,y,z" key would be written straight into
			// the player's profile and then parsed back as garbage, so validate before use.
			if (!isValidStagingKey(k)) {
				rejected++;
				continue;
			}
			if (liveStagingKeys.add(k)) {
				added++;
			}
		}
		if (rejected > 0) {
			ChestMemoryMod.LOGGER.warn("Ignored {} malformed or excess staging key(s) from clan hub", rejected);
		}
		if (added > 0) {
			liveDirty = true;
			saveIfNeeded();
		}
		return added;
	}

	/** Strict check for a {@code dimension|x,y,z} key from an untrusted source. */
	private static boolean isValidStagingKey(@Nullable String key) {
		if (key == null || key.isBlank() || key.length() > MAX_STAGING_KEY_LENGTH) {
			return false;
		}
		String[] parts = parseStagingKey(key);
		if (parts == null) {
			return false;
		}
		String dim = parts[0];
		if (dim.isBlank() || dim.length() > 128) {
			return false;
		}
		for (int i = 1; i <= 3; i++) {
			try {
				int v = Integer.parseInt(parts[i]);
				// Y is checked loosely; X/Z against the vanilla world border.
				if (v < -30_000_000 || v > 30_000_000) {
					return false;
				}
			} catch (NumberFormatException e) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Parse {@code dimension|x,y,z} staging key.
	 * @return [dim, x, y, z] or null
	 */
	public static @Nullable String[] parseStagingKey(String key) {
		if (key == null || key.isBlank()) {
			return null;
		}
		int bar = key.indexOf('|');
		if (bar <= 0 || bar >= key.length() - 1) {
			return null;
		}
		String dim = key.substring(0, bar);
		String[] xyz = key.substring(bar + 1).split(",");
		if (xyz.length != 3) {
			return null;
		}
		return new String[]{dim, xyz[0].trim(), xyz[1].trim(), xyz[2].trim()};
	}

	/** Items already delivered to build-site warehouse (staging chests). */
	public synchronized int countInStaging(String itemId) {
		if (itemId == null || liveStagingKeys.isEmpty()) {
			return 0;
		}
		int t = 0;
		for (String key : liveStagingKeys) {
			ContainerRecord r = liveContainers.get(key);
			if (r != null) {
				t += r.countOf(itemId);
			}
		}
		return t;
	}

	/**
	 * Items still available to <em>take</em> (live memory, excluding staging).
	 * All dimensions.
	 */
	public synchronized int countInSourceChests(String itemId) {
		return countInSourceChests(itemId, DimensionChoice.ALL, null);
	}

	/**
	 * Source-chest stock for an item, optionally restricted by dimension filter
	 * (e.g. {@link DimensionChoice#CURRENT} + whole profile still stays in one world).
	 */
	public synchronized int countInSourceChests(
		String itemId,
		DimensionChoice dimensionFilter,
		@Nullable String playerDimension
	) {
		if (itemId == null) {
			return 0;
		}
		DimensionChoice dim = dimensionFilter != null ? dimensionFilter : DimensionChoice.ALL;
		int t = 0;
		for (ContainerRecord r : indexedContainers(itemId)) {
			if (isStaging(r)) {
				continue;
			}
			if (!dim.matches(r, playerDimension)) {
				continue;
			}
			t += r.countOf(itemId);
		}
		return t;
	}

	/** Source containers (not staging) that hold the item and can be highlighted. */
	public synchronized List<ContainerRecord> liveSourceHighlightableWithItem(String itemId) {
		return liveSourceHighlightableWithItem(itemId, DimensionChoice.ALL, null);
	}

	public synchronized List<ContainerRecord> liveSourceHighlightableWithItem(
		String itemId,
		DimensionChoice dimensionFilter,
		@Nullable String playerDimension
	) {
		List<ContainerRecord> out = new ArrayList<>();
		if (itemId == null) {
			return out;
		}
		DimensionChoice dim = dimensionFilter != null ? dimensionFilter : DimensionChoice.ALL;
		for (ContainerRecord r : indexedContainers(itemId)) {
			if (isStaging(r)) {
				continue;
			}
			if (!dim.matches(r, playerDimension)) {
				continue;
			}
			if (r.countOf(itemId) <= 0) {
				continue;
			}
			if (r.isWorldBlock() || r.hasHighlightPos()) {
				out.add(r);
			}
		}
		return out;
	}

	public synchronized void setViewingWorld(String worldId) {
		if (worldId == null) {
			return;
		}
		if (worldId.equals(liveWorldId)) {
			viewingWorldId = liveWorldId;
			viewingDisplayName = liveDisplayName;
			viewingContainers = liveContainers;
			viewingKnownDimensions = liveKnownDimensions;
			return;
		}
		if (worldId.equals(viewingWorldId) && viewingContainers != liveContainers) {
			return;
		}
		WorldFile file = loadFromDisk(worldId);
		viewingWorldId = worldId;
		viewingDisplayName = file.meta.getOrDefault("displayName", prettyId(worldId));
		viewingContainers = file.containers != null ? file.containers : new LinkedHashMap<>();
		viewingKnownDimensions = new LinkedHashSet<>();
		if (file.knownDimensions != null) {
			viewingKnownDimensions.addAll(file.knownDimensions);
		}
		for (ContainerRecord r : viewingContainers.values()) {
			if (r.dimension() != null && !r.dimension().isBlank()) {
				viewingKnownDimensions.add(r.dimension());
			}
		}
	}

	public synchronized boolean isViewingLive() {
		return viewingContainers == liveContainers;
	}

	public synchronized String viewingWorldId() {
		return viewingWorldId;
	}

	public synchronized String viewingDisplayName() {
		return viewingDisplayName == null || viewingDisplayName.isEmpty()
			? (viewingWorldId == null ? "?" : prettyId(viewingWorldId))
			: viewingDisplayName;
	}

	public synchronized String liveWorldId() {
		return liveWorldId;
	}

	public synchronized List<WorldTab> listWorldTabs() {
		List<WorldTab> tabs = new ArrayList<>();
		Map<String, WorldTab> byId = new LinkedHashMap<>();

		if (liveWorldId != null) {
			byId.put(liveWorldId, new WorldTab(liveWorldId, liveDisplayName, true, liveContainers.size()));
		}

		try (DirectoryStream<Path> stream = Files.newDirectoryStream(worldsDir(), "*.json")) {
			for (Path path : stream) {
				String fileName = path.getFileName().toString();
				String id = fileName.substring(0, fileName.length() - 5);
				if (byId.containsKey(id)) {
					continue;
				}
				WorldFile file = loadFromDisk(id);
				String name = file.meta.getOrDefault("displayName", prettyId(id));
				int count = file.containers == null ? 0 : file.containers.size();
				byId.put(id, new WorldTab(id, name, false, count));
			}
		} catch (IOException e) {
			ChestMemoryMod.LOGGER.error("Failed to list world profiles", e);
		}

		// Live first, then others alphabetically
		if (liveWorldId != null && byId.containsKey(liveWorldId)) {
			tabs.add(byId.remove(liveWorldId));
		}
		byId.values().stream()
			.sorted(Comparator.comparing(WorldTab::displayName, String.CASE_INSENSITIVE_ORDER))
			.forEach(tabs::add);
		return tabs;
	}

	private static String prettyId(String id) {
		if (id.startsWith("sp_")) {
			return "SP: " + id.substring(3).replace('_', ' ');
		}
		if (id.startsWith("mp_")) {
			return "MP: " + id.substring(3).replace('_', '.');
		}
		return id;
	}

	private Map<String, ContainerRecord> activeView() {
		return viewingContainers != null ? viewingContainers : liveContainers;
	}

	/** Default radius for "nearby" mode (blocks). */
	public static final double DEFAULT_NEARBY_RANGE = 64.0;

	public synchronized List<ContainerRecord> findContainersWithItem(String itemId) {
		return findContainersWithItem(itemId, ContainerFilter.ALL, DimensionChoice.ALL, ListScope.WORLD_TOTAL, null, null, DEFAULT_NEARBY_RANGE);
	}

	public synchronized List<ContainerRecord> findContainersWithItem(String itemId, ContainerFilter filter) {
		return findContainersWithItem(itemId, filter, DimensionChoice.ALL, ListScope.WORLD_TOTAL, null, null, DEFAULT_NEARBY_RANGE);
	}

	/**
	 * Containers that have the item, optionally only nearby ones / one dimension (incl. custom multiworlds).
	 */
	public synchronized List<ContainerRecord> findContainersWithItem(
		String itemId,
		ContainerFilter filter,
		DimensionChoice dimensionFilter,
		ListScope scope,
		@Nullable String playerDimension,
		@Nullable Vec3 playerPos,
		double nearbyRange
	) {
		List<ContainerRecord> result = new ArrayList<>();
		for (ContainerRecord record : filteredContainers(filter, dimensionFilter, scope, playerDimension, playerPos, nearbyRange)) {
			if (record.hasItem(itemId)) {
				result.add(record);
			}
		}
		if (scope == ListScope.NEARBY && playerPos != null) {
			result.sort(Comparator.comparingDouble(r -> distanceTo(r, playerPos, playerDimension)));
		}
		return result;
	}

	/** Lookup in the live profile only (for Jade / world highlight). */
	public synchronized @Nullable ContainerRecord findAtLive(String dimension, BlockPos pos, @Nullable BlockGetter level) {
		if (liveWorldId == null) {
			return null;
		}
		BlockPos canonical = ContainerKeys.canonicalPos(level, pos);
		ContainerRecord direct = liveContainers.get(ContainerKeys.blockKey(dimension, canonical));
		if (direct != null) {
			return direct;
		}
		// Fallback raw pos (legacy entries before canonicalization)
		ContainerRecord raw = liveContainers.get(ContainerKeys.blockKey(dimension, pos));
		if (raw != null) {
			return raw;
		}
		BlockPos other = ContainerKeys.otherHalf(level, pos);
		if (other != null) {
			return liveContainers.get(ContainerKeys.blockKey(dimension, other));
		}
		return null;
	}

	/**
	 * List items for the Ё panel.
	 * <p>
	 * Uses only the currently selected world profile (never mixes servers/worlds).
	 * In {@link ListScope#NEARBY} only containers within range in the current dimension count.
	 */
	public synchronized List<ItemSummary> listItems(
		String query,
		ContainerFilter filter,
		DimensionChoice dimensionFilter,
		ListScope scope,
		@Nullable String playerDimension,
		@Nullable Vec3 playerPos,
		double nearbyRange
	) {
		// count[0]=total items, count[1]=containers, nearest[item]=min distance
		Map<String, int[]> aggregated = new HashMap<>();
		Map<String, Double> nearest = new HashMap<>();

		for (ContainerRecord record : filteredContainers(filter, dimensionFilter, scope, playerDimension, playerPos, nearbyRange)) {
			double dist = distanceTo(record, playerPos, playerDimension);
			for (Map.Entry<String, Integer> entry : record.items().entrySet()) {
				int[] values = aggregated.computeIfAbsent(entry.getKey(), k -> new int[2]);
				values[0] += entry.getValue();
				values[1] += 1;
				if (dist >= 0) {
					nearest.merge(entry.getKey(), dist, Math::min);
				}
			}
		}

		String normalized = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
		return aggregated.entrySet().stream()
			.filter(e -> matchesQuery(e.getKey(), normalized))
			.map(e -> new ItemSummary(
				e.getKey(),
				e.getValue()[0],
				e.getValue()[1],
				nearest.getOrDefault(e.getKey(), -1.0)
			))
			.sorted(sortComparator(scope == ListScope.NEARBY ? SortMode.DISTANCE : SortMode.COUNT))
			.collect(Collectors.toList());
	}

	public synchronized List<ItemSummary> listItems(
		String query,
		ContainerFilter filter,
		DimensionChoice dimensionFilter,
		ListScope scope,
		@Nullable String playerDimension,
		@Nullable Vec3 playerPos,
		double nearbyRange,
		SortMode sortMode
	) {
		List<ItemSummary> base = listItems(query, filter, dimensionFilter, scope, playerDimension, playerPos, nearbyRange);
		base.sort(sortComparator(sortMode));
		return base;
	}

	private static Comparator<ItemSummary> sortComparator(SortMode mode) {
		return switch (mode) {
			case DISTANCE -> Comparator
				.comparingDouble((ItemSummary s) -> s.hasDistance() ? s.nearestDistance() : Double.MAX_VALUE)
				.thenComparing(ItemSummary::totalCount, Comparator.reverseOrder())
				.thenComparing(s -> itemDisplayName(s.itemId()), String.CASE_INSENSITIVE_ORDER);
			case NAME -> Comparator
				.comparing((ItemSummary s) -> itemDisplayName(s.itemId()), String.CASE_INSENSITIVE_ORDER)
				.thenComparing(ItemSummary::totalCount, Comparator.reverseOrder());
			case COUNT -> Comparator
				.comparing(ItemSummary::totalCount).reversed()
				.thenComparing(s -> itemDisplayName(s.itemId()), String.CASE_INSENSITIVE_ORDER);
		};
	}

	/** Back-compat: whole selected profile, no distance filter. */
	public synchronized List<ItemSummary> listItems(String query, ContainerFilter filter) {
		return listItems(query, filter, DimensionChoice.ALL, ListScope.WORLD_TOTAL, null, null, DEFAULT_NEARBY_RANGE);
	}

	public synchronized int containerCount(
		ContainerFilter filter,
		DimensionChoice dimensionFilter,
		ListScope scope,
		@Nullable String playerDimension,
		@Nullable Vec3 playerPos,
		double nearbyRange
	) {
		int n = 0;
		for (ContainerRecord ignored : filteredContainers(filter, dimensionFilter, scope, playerDimension, playerPos, nearbyRange)) {
			n++;
		}
		return n;
	}

	/** Unique dimensions stored in the currently viewed profile (for multiworld servers). */
	public synchronized List<DimensionChoice> listDimensionChoices(@Nullable String playerDimension) {
		Set<String> known = MultiworldTracker.allKnown(activeView().values());
		known.addAll(knownDimensions());
		return DimensionChoice.buildChoices(activeView().values(), playerDimension, known);
	}

	private static java.util.Collection<ContainerFilter> typeSet(ContainerFilter filter) {
		if (filter == null || filter == ContainerFilter.ALL) {
			return java.util.EnumSet.of(ContainerFilter.ALL);
		}
		return java.util.EnumSet.of(filter);
	}

	private List<ContainerRecord> filteredContainers(
		ContainerFilter filter,
		DimensionChoice dimensionFilter,
		ListScope scope,
		@Nullable String playerDimension,
		@Nullable Vec3 playerPos,
		double nearbyRange
	) {
		return filteredContainers(typeSet(filter), dimensionFilter, scope, playerDimension, playerPos, nearbyRange);
	}

	private List<ContainerRecord> filteredContainers(
		java.util.Collection<ContainerFilter> typeFilters,
		DimensionChoice dimensionFilter,
		ListScope scope,
		@Nullable String playerDimension,
		@Nullable Vec3 playerPos,
		double nearbyRange
	) {
		List<ContainerRecord> out = new ArrayList<>();
		for (ContainerRecord record : activeView().values()) {
			if (!ContainerFilter.matchesAny(record, typeFilters)) {
				continue;
			}
			if (!dimensionFilter.matches(record, playerDimension)) {
				continue;
			}
			if (scope == ListScope.NEARBY) {
				if (!isNearby(record, playerDimension, playerPos, nearbyRange)) {
					continue;
				}
			}
			out.add(record);
		}
		return out;
	}

	/** Multi-type list (chests + barrels + …). */
	public synchronized List<ItemSummary> listItems(
		String query,
		java.util.Collection<ContainerFilter> typeFilters,
		DimensionChoice dimensionFilter,
		ListScope scope,
		@Nullable String playerDimension,
		@Nullable Vec3 playerPos,
		double nearbyRange,
		SortMode sortMode
	) {
		Map<String, int[]> aggregated = new HashMap<>();
		Map<String, Double> nearest = new HashMap<>();

		for (ContainerRecord record : filteredContainers(
			typeFilters, dimensionFilter, scope, playerDimension, playerPos, nearbyRange
		)) {
			double dist = distanceTo(record, playerPos, playerDimension);
			for (Map.Entry<String, Integer> entry : record.items().entrySet()) {
				int[] values = aggregated.computeIfAbsent(entry.getKey(), k -> new int[2]);
				values[0] += entry.getValue();
				values[1] += 1;
				if (dist >= 0) {
					nearest.merge(entry.getKey(), dist, Math::min);
				}
			}
		}

		String normalized = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
		List<ItemSummary> base = aggregated.entrySet().stream()
			.filter(e -> matchesQuery(e.getKey(), normalized))
			.map(e -> new ItemSummary(
				e.getKey(),
				e.getValue()[0],
				e.getValue()[1],
				nearest.getOrDefault(e.getKey(), -1.0)
			))
			.collect(Collectors.toList());
		base.sort(sortComparator(sortMode));
		return base;
	}

	public synchronized int containerCount(
		java.util.Collection<ContainerFilter> typeFilters,
		DimensionChoice dimensionFilter,
		ListScope scope,
		@Nullable String playerDimension,
		@Nullable Vec3 playerPos,
		double nearbyRange
	) {
		int n = 0;
		for (ContainerRecord ignored : filteredContainers(
			typeFilters, dimensionFilter, scope, playerDimension, playerPos, nearbyRange
		)) {
			n++;
		}
		return n;
	}

	public synchronized List<ContainerRecord> findContainersWithItem(
		String itemId,
		java.util.Collection<ContainerFilter> typeFilters,
		DimensionChoice dimensionFilter,
		ListScope scope,
		@Nullable String playerDimension,
		@Nullable Vec3 playerPos,
		double nearbyRange
	) {
		List<ContainerRecord> result = new ArrayList<>();
		for (ContainerRecord record : filteredContainers(
			typeFilters, dimensionFilter, scope, playerDimension, playerPos, nearbyRange
		)) {
			if (record.hasItem(itemId)) {
				result.add(record);
			}
		}
		if (scope == ListScope.NEARBY && playerPos != null) {
			result.sort(Comparator.comparingDouble(r -> distanceTo(r, playerPos, playerDimension)));
		}
		return result;
	}

	/**
	 * Nearby = same dimension (for world blocks) and within range.
	 * Inventory shulkers always count as nearby when on live profile.
	 */
	private static boolean isNearby(
		ContainerRecord record,
		@Nullable String playerDimension,
		@Nullable Vec3 playerPos,
		double nearbyRange
	) {
		if (playerPos == null) {
			return false;
		}
		// Inventory shulkers move with the player
		if (record.isVirtual() && record.virtualId() != null && record.virtualId().startsWith("inv_shulker")) {
			return true;
		}
		// Ender chest is "personal" — always include as nearby when scanned
		if (record.isVirtual() && "ender_chest".equals(record.virtualId())) {
			return true;
		}
		if (playerDimension != null && record.dimension() != null
			&& !playerDimension.equals(record.dimension())
			&& !record.isVirtual()) {
			return false;
		}
		double dist = distanceTo(record, playerPos, playerDimension);
		return dist >= 0 && dist <= nearbyRange;
	}

	/** Distance in blocks, or -1 if not applicable. */
	public static double distanceTo(
		ContainerRecord record,
		@Nullable Vec3 playerPos,
		@Nullable String playerDimension
	) {
		if (playerPos == null) {
			return -1;
		}
		if (record.isVirtual() && record.virtualId() != null && record.virtualId().startsWith("inv_shulker")) {
			return 0;
		}
		if (record.isVirtual() && "ender_chest".equals(record.virtualId()) && !record.hasHighlightPos()) {
			return 0;
		}
		int x;
		int y;
		int z;
		if (record.isWorldBlock()) {
			x = record.x();
			y = record.y();
			z = record.z();
		} else if (record.hasHighlightPos()) {
			x = record.highlightX();
			y = record.highlightY();
			z = record.highlightZ();
		} else {
			return -1;
		}
		if (playerDimension != null && record.dimension() != null
			&& !playerDimension.equals(record.dimension())
			&& record.isWorldBlock()) {
			return -1;
		}
		double dx = playerPos.x - (x + 0.5);
		double dy = playerPos.y - (y + 0.5);
		double dz = playerPos.z - (z + 0.5);
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}

	private static boolean matchesQuery(String itemId, String query) {
		if (query.isEmpty()) {
			return true;
		}
		String q = query.toLowerCase(Locale.ROOT);
		if (ItemStackKeys.searchBlob(itemId).contains(q)) {
			return true;
		}
		String lower = itemId.toLowerCase(Locale.ROOT);
		if (lower.contains(q)) {
			return true;
		}
		int colon = lower.indexOf(':');
		String path = colon >= 0 ? lower.substring(colon + 1) : lower;
		if (path.contains(q)) {
			return true;
		}
		String base = ItemStackKeys.baseId(itemId);
		Identifier id = Identifier.tryParse(base);
		if (id != null) {
			Item item = BuiltInRegistries.ITEM.getValue(id);
			if (item != Items.AIR) {
				String name = new ItemStack(item).getHoverName().getString().toLowerCase(Locale.ROOT);
				if (name.contains(q)) {
					return true;
				}
			}
		}
		return path.replace('_', ' ').contains(q);
	}

	public synchronized int containerCount() {
		return activeView().size();
	}

	public synchronized int containerCount(ContainerFilter filter) {
		return containerCount(filter, DimensionChoice.ALL, ListScope.WORLD_TOTAL, null, null, DEFAULT_NEARBY_RANGE);
	}

	/** @deprecated use {@link #liveWorldId()} or {@link #viewingWorldId()} */
	@Deprecated
	public synchronized String currentWorldId() {
		return liveWorldId;
	}

	public synchronized List<ContainerRecord> allContainers() {
		return new ArrayList<>(activeView().values());
	}

	public synchronized List<ContainerRecord> liveContainersSnapshot() {
		List<ContainerRecord> cached = liveSnapshotCache;
		if (cached == null) {
			cached = List.copyOf(liveContainers.values());
			liveSnapshotCache = cached;
		}
		return cached;
	}

	public synchronized @Nullable ContainerRecord findLiveByKey(String key) {
		if (key == null) {
			return null;
		}
		return liveContainers.get(key);
	}

	/**
	 * RFC 4180 field escaping: quote when the value contains a comma, quote or
	 * line break; inner quotes are doubled ({@code "} → {@code ""}).
	 */
	private static String csvField(@Nullable Object value) {
		String s = value == null ? "" : String.valueOf(value);
		if (s.contains("\"") || s.contains(",") || s.contains("\n") || s.contains("\r")) {
			return '"' + s.replace("\"", "\"\"") + '"';
		}
		return s;
	}

	/**
	 * Write the currently displayed list to config/chestmemory/exports/.
	 *
	 * @param items the rows the panel is showing, so the file matches the screen exactly
	 *              (the panel filters by a set of types, not the single ContainerFilter
	 *              this method used to take)
	 * @param typeFilters same set, used for the per-container section
	 * @return the written path, or null on failure
	 */
	public synchronized @Nullable Path exportCsv(
		List<ItemSummary> items,
		java.util.Collection<ContainerFilter> typeFilters,
		String query
	) {
		String world = viewingWorldId == null ? "unknown" : viewingWorldId;
		String fileName = "export_" + world + "_" + EXPORT_TIME.format(LocalDateTime.now()) + ".csv";
		Path out = exportDir().resolve(fileName);

		StringBuilder sb = new StringBuilder();
		sb.append("world_id,display_name\n");
		sb.append(csvField(world)).append(',').append(csvField(viewingDisplayName())).append('\n').append('\n');
		sb.append("item_id,display_name,total_count,container_count,stacks_of_64\n");
		for (ItemSummary summary : items) {
			sb.append(csvField(summary.itemId())).append(',')
				.append(csvField(itemDisplayName(summary.itemId()))).append(',')
				.append(summary.totalCount()).append(',')
				.append(summary.containerCount()).append(',')
				.append(summary.fullStacks()).append('\n');
		}

		sb.append("\n# containers\n");
		sb.append("type,dimension,x,y,z,virtual_id,double_chest,item_id,count\n");
		for (ContainerRecord record : activeView().values()) {
			if (!ContainerFilter.matchesAny(record, typeFilters)) {
				continue;
			}
			for (Map.Entry<String, Integer> e : record.items().entrySet()) {
				if (!matchesQuery(e.getKey(), query == null ? "" : query.trim().toLowerCase(Locale.ROOT))) {
					continue;
				}
				sb.append(csvField(record.type())).append(',')
					.append(csvField(record.dimension())).append(',')
					.append(record.x()).append(',')
					.append(record.y()).append(',')
					.append(record.z()).append(',')
					.append(csvField(record.virtualId())).append(',')
					.append(record.doubleChest()).append(',')
					.append(csvField(e.getKey())).append(',')
					.append(e.getValue()).append('\n');
			}
		}

		try {
			Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
			return out;
		} catch (IOException e) {
			ChestMemoryMod.LOGGER.error("Export failed", e);
			return null;
		}
	}

	public static String itemDisplayName(String itemId) {
		if (itemId == null) {
			return "?";
		}
		// Enchanted books / gear: include enchant names
		if (ItemStackKeys.hasEnchantData(itemId)) {
			return ItemStackKeys.displayName(itemId);
		}
		Identifier id = Identifier.tryParse(ItemStackKeys.baseId(itemId));
		if (id == null) {
			return itemId;
		}
		Item item = BuiltInRegistries.ITEM.getValue(id);
		if (item == Items.AIR) {
			return itemId;
		}
		return new ItemStack(item).getHoverName().getString();
	}

	public static String dimensionId(Level level) {
		return level.dimension().identifier().toString();
	}
}
