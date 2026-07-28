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
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
	private static final int FORMAT_VERSION = 3;
	/**
	 * Profile writes happen off the render thread — a big base serializes to a sizeable JSON
	 * file, and writing it synchronously froze a frame every autosave. One thread keeps the
	 * writes ordered; {@link #PENDING_WRITES} lets a load wait for its own file to settle.
	 */
	private static final ExecutorService SAVE_IO = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "chestmemory-save-io");
		t.setDaemon(true);
		return t;
	});
	/** In-flight write per profile id, so loads never read a half-written file. */
	private static final Map<String, Future<?>> PENDING_WRITES = new ConcurrentHashMap<>();
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

	/** See {@link #listWorldTabs()} — avoids reparsing every profile file per panel open. */
	private static final long WORLD_TABS_CACHE_TTL_MS = 3_000L;
	private @Nullable List<WorldTab> worldTabsCache;
	private long worldTabsCacheMillis;

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
		worldTabsCache = null;
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
		worldTabsCache = null;
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
		awaitPendingWrite(worldId);
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
					result.containers = ProfileMigration.normalize(result.containers);
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
			return result;
		}
		// Clear legacy world tags and rebuild keys from the records themselves, so files
		// written by any format version land on the keys this version looks up.
		result.containers = ProfileMigration.normalize(result.containers);
		return result;
	}

	/** Block until an in-flight write of this profile lands, so loads read settled bytes. */
	private static void awaitPendingWrite(String worldId) {
		Future<?> pending = PENDING_WRITES.get(worldId);
		if (pending == null) {
			return;
		}
		try {
			pending.get(5, TimeUnit.SECONDS);
		} catch (Exception e) {
			ChestMemoryMod.LOGGER.warn("Pending save of {} did not settle before load: {}", worldId, e.toString());
		}
	}

	/**
	 * Queue a save of the live profile if anything changed. Serialization happens under the
	 * lock (fast, memory only); the actual file write runs on {@link #SAVE_IO} so the render
	 * thread never blocks on disk. Call {@link #saveNow()} when the process is about to end.
	 */
	public synchronized void saveIfNeeded() {
		scheduleSave(false);
	}

	/**
	 * Save synchronously — waits for the write (and everything queued before it) to land.
	 * For disconnect / game-quit, where an async task might not get to run.
	 */
	public synchronized void saveNow() {
		scheduleSave(true);
	}

	private synchronized void scheduleSave(boolean blocking) {
		if (!liveDirty || liveWorldId == null) {
			return;
		}
		if (liveLoadFailed) {
			// The on-disk profile could not be parsed. Writing now would replace data that
			// is very likely still recoverable by hand with whatever little we have in memory.
			return;
		}
		String worldId = liveWorldId;
		JsonObject root = new JsonObject();
		JsonObject meta = new JsonObject();
		meta.addProperty("displayName", liveDisplayName);
		meta.addProperty("id", worldId);
		meta.addProperty("kind", worldId.startsWith("mp_") ? "multiplayer" : "singleplayer");
		meta.addProperty("formatVersion", String.valueOf(FORMAT_VERSION));
		root.add("meta", meta);
		root.add("containers", GSON.toJsonTree(liveContainers, MAP_TYPE));
		root.add("knownDimensions", GSON.toJsonTree(new ArrayList<>(liveKnownDimensions)));
		root.add("stagingKeys", GSON.toJsonTree(new ArrayList<>(liveStagingKeys)));
		liveDirty = false;
		worldTabsCache = null;

		Future<?> task = SAVE_IO.submit(() -> writeProfileFile(worldId, root));
		PENDING_WRITES.put(worldId, task);
		if (blocking) {
			try {
				task.get(10, TimeUnit.SECONDS);
			} catch (Exception e) {
				ChestMemoryMod.LOGGER.warn("Blocking save of {} did not finish cleanly: {}", worldId, e.toString());
			}
		}
	}

	/** Runs on {@link #SAVE_IO}. The JSON tree is a private snapshot — no lock needed. */
	private void writeProfileFile(String worldId, JsonObject root) {
		Path file = worldFile(worldId);
		Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
		try {
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
					ChestMemoryMod.LOGGER.warn("Could not refresh backup for {}: {}", worldId, e.toString());
				}
			}
			try {
				Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException e) {
				Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (Exception e) {
			// Catch Exception, not IOException: Gson throws unchecked JsonIOException, which
			// must not escape and kill the writer thread silently.
			ChestMemoryMod.LOGGER.error("Failed to save chest memory for {}", worldId, e);
			try {
				Files.deleteIfExists(tmp);
			} catch (IOException ignored) {
				// best effort
			}
		}
		// The PENDING_WRITES entry is left in place on purpose: waiting on a finished future
		// is free, and removing it here could race a newer task that replaced the entry.
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

	/** Forget a world block at this position — under its tagged and untagged key alike. */
	public synchronized void forgetAt(String dimension, BlockPos pos) {
		forget(ContainerRecord.makeKey(dimension, pos.getX(), pos.getY(), pos.getZ()));
		String currentTag = WorldFingerprint.current(Minecraft.getInstance());
		if (currentTag != null) {
			forget(ContainerRecord.makeKey(dimension, pos.getX(), pos.getY(), pos.getZ(), currentTag));
		}
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
	 * <p>
	 * The record is stamped with the current world tag and keyed on it, so the same
	 * coordinates in another world (multiworld farm/build) keep their own record. The
	 * untagged legacy record at this position — whichever world once wrote it — is
	 * superseded: the player is looking at the real chest right now, so this scan is the
	 * truth for these coordinates in this world.
	 *
	 * @return true when memory changed (new record or different contents), false when the
	 *         scan matched what was already remembered
	 */
	public synchronized boolean rememberBlockContainer(
		BlockGetter level,
		String dimension,
		BlockPos rawPos,
		String type,
		Map<String, Integer> items
	) {
		return rememberBlockContainer(level, dimension, rawPos, type, items, null);
	}

	/** @param shulkerItems portion of {@code items} that lies inside nested shulker boxes */
	public synchronized boolean rememberBlockContainer(
		BlockGetter level,
		String dimension,
		BlockPos rawPos,
		String type,
		Map<String, Integer> items,
		@Nullable Map<String, Integer> shulkerItems
	) {
		if (liveWorldId == null) {
			return false;
		}
		String worldTag = WorldFingerprint.current(Minecraft.getInstance());
		BlockPos canonical = ContainerKeys.canonicalPos(level, rawPos);
		boolean dbl = ContainerKeys.isDoubleChest(level, rawPos);
		String finalType = dbl ? "double_chest" : type;

		ContainerRecord record = new ContainerRecord(finalType, dimension, canonical.getX(), canonical.getY(), canonical.getZ());
		record.setItems(items);
		record.setShulkerItems(shulkerItems);
		// Stamp the world, so a multiworld server's two overworlds / Nethers can be told
		// apart even though both report the same vanilla dimension id.
		record.setWorldTag(worldTag);
		record.setDoubleChest(dbl);
		BlockPos other = ContainerKeys.otherHalf(level, rawPos);
		if (other != null) {
			// Always store the other half relative to canonical for full glow
			BlockPos otherFromCanonical = ContainerKeys.otherHalf(level, canonical);
			if (otherFromCanonical != null) {
				record.setOtherHalf(otherFromCanonical.getX(), otherFromCanonical.getY(), otherFromCanonical.getZ());
			} else {
				record.setOtherHalf(other.getX(), other.getY(), other.getZ());
			}
		}

		// The scanner calls this every tick while a chest GUI is open. When nothing changed,
		// only refresh the timestamp: no re-index, no snapshot invalidation, no dirty flag —
		// an open chest used to rewrite the profile once per autosave interval for nothing.
		String recordKey = record.positionKey();
		ContainerRecord existing = liveContainers.get(recordKey);
		if (existing != null && sameScan(existing, record)) {
			existing.setLastSeenMillis(System.currentTimeMillis());
			return false;
		}

		// Supersede stale keys for this position: the untagged legacy record at the canonical
		// position, and any leftovers under the raw / other-half positions (tagged and
		// untagged alike). The record's own key is never touched — remember() replaces it.
		forgetUnlessOwn(ContainerRecord.makeKey(dimension, canonical.getX(), canonical.getY(), canonical.getZ()), recordKey);
		if (other != null) {
			forgetBothForms(dimension, other, worldTag, recordKey);
			forgetBothForms(dimension, rawPos, worldTag, recordKey);
		}

		record.setLastSeenMillis(System.currentTimeMillis());
		// If chest is now empty of everything, still keep record (user may refill);
		// callers clear highlight when selected item is gone.
		remember(record);
		return true;
	}

	/** True when a fresh scan carries exactly what the existing record already says. */
	private static boolean sameScan(ContainerRecord existing, ContainerRecord fresh) {
		return Objects.equals(existing.type(), fresh.type())
			&& existing.doubleChest() == fresh.doubleChest()
			&& existing.hasOtherHalf() == fresh.hasOtherHalf()
			&& (!fresh.hasOtherHalf()
				|| (existing.otherX() == fresh.otherX()
					&& existing.otherY() == fresh.otherY()
					&& existing.otherZ() == fresh.otherZ()))
			&& existing.items().equals(fresh.items())
			&& existing.shulkerItems().equals(fresh.shulkerItems());
	}

	private void forgetUnlessOwn(String key, String ownKey) {
		if (!key.equals(ownKey)) {
			forget(key);
		}
	}

	/** Forget the record at this position under both its tagged and untagged key. */
	private void forgetBothForms(String dimension, BlockPos pos, @Nullable String worldTag, String ownKey) {
		forgetUnlessOwn(ContainerRecord.makeKey(dimension, pos.getX(), pos.getY(), pos.getZ()), ownKey);
		if (worldTag != null) {
			forgetUnlessOwn(ContainerRecord.makeKey(dimension, pos.getX(), pos.getY(), pos.getZ(), worldTag), ownKey);
		}
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
	 * Staging key for a block: canonical double-chest position plus the current world tag
	 * when one is known. Untagged keys collide across a multiworld server's worlds — a
	 * warehouse mark in the build world matched a chest at the same coordinates in the
	 * farm world, and the farm chest's contents were then reported as clan deliveries.
	 */
	public synchronized String stagingKeyFor(@Nullable BlockGetter level, String dimension, BlockPos rawPos) {
		BlockPos canonical = level != null
			? ContainerKeys.canonicalPos(level, rawPos)
			: rawPos.immutable();
		String tag = WorldFingerprint.current(Minecraft.getInstance());
		return ContainerRecord.makeKey(dimension, canonical.getX(), canonical.getY(), canonical.getZ(), tag);
	}

	/** True when this block is marked as staging — under its tagged or legacy key. */
	public synchronized boolean isStagingAt(@Nullable BlockGetter level, String dimension, BlockPos rawPos) {
		if (dimension == null || rawPos == null || liveStagingKeys.isEmpty()) {
			return false;
		}
		if (liveStagingKeys.contains(stagingKeyFor(level, dimension, rawPos))) {
			return true;
		}
		BlockPos canonical = level != null
			? ContainerKeys.canonicalPos(level, rawPos)
			: rawPos.immutable();
		return liveStagingKeys.contains(ContainerKeys.blockKey(dimension, canonical));
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
		String key = stagingKeyFor(level, dimension, rawPos);
		// A legacy untagged mark for the same block is superseded by the tagged one.
		liveStagingKeys.remove(ContainerKeys.blockKey(dimension, canonical));
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
		String key = stagingKeyFor(level, dimension, rawPos);
		liveStagingKeys.remove(ContainerKeys.blockKey(dimension, canonical));
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
	 * Parse a {@code dimension|x,y,z} staging key. A trailing {@code @<worldTag>} — the
	 * suffix container keys carry since the multiworld fix — is tolerated and stripped, so
	 * a record key can be fed through the same parser.
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
		String coords = key.substring(bar + 1);
		int at = coords.lastIndexOf('@');
		if (at >= 0 && WorldTags.isCurrentFormat(coords.substring(at + 1))) {
			coords = coords.substring(0, at);
		}
		String[] xyz = coords.split(",");
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
		String currentTag = WorldFingerprint.current(Minecraft.getInstance());
		int t = 0;
		for (String key : liveStagingKeys) {
			ContainerRecord r = findByStagingKeyInternal(key, currentTag);
			if (r != null) {
				t += r.countOf(itemId);
			}
		}
		return t;
	}

	/**
	 * Record behind a staging key. Staging keys are shared with clan members and therefore
	 * stay in the untagged {@code dim|x,y,z} form, while records are keyed per world — so a
	 * direct map hit is tried first, then the tagged record for the current world.
	 */
	public synchronized @Nullable ContainerRecord findByStagingKey(@Nullable String key) {
		return findByStagingKeyInternal(key, WorldFingerprint.current(Minecraft.getInstance()));
	}

	private @Nullable ContainerRecord findByStagingKeyInternal(@Nullable String key, @Nullable String currentTag) {
		if (key == null) {
			return null;
		}
		ContainerRecord direct = liveContainers.get(key);
		if (direct != null) {
			return direct;
		}
		String[] parsed = parseStagingKey(key);
		if (parsed == null) {
			return null;
		}
		try {
			int x = Integer.parseInt(parsed[1]);
			int y = Integer.parseInt(parsed[2]);
			int z = Integer.parseInt(parsed[3]);
			return lookupBlock(parsed[0], x, y, z, currentTag);
		} catch (NumberFormatException e) {
			return null;
		}
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
		String currentTag = dim.kind() == DimensionChoice.Kind.CURRENT
			? WorldFingerprint.current(Minecraft.getInstance())
			: null;
		int t = 0;
		for (ContainerRecord r : indexedContainers(itemId)) {
			if (isStaging(r)) {
				continue;
			}
			if (!dim.matches(r, playerDimension)) {
				continue;
			}
			if (currentTag != null && r.isWorldBlock()
				&& WorldTags.provablyDifferent(currentTag, r.worldTag())) {
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
		String currentTag = dim.kind() == DimensionChoice.Kind.CURRENT
			? WorldFingerprint.current(Minecraft.getInstance())
			: null;
		for (ContainerRecord r : indexedContainers(itemId)) {
			if (isStaging(r)) {
				continue;
			}
			if (!dim.matches(r, playerDimension)) {
				continue;
			}
			if (currentTag != null && r.isWorldBlock()
				&& WorldTags.provablyDifferent(currentTag, r.worldTag())) {
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
		// Opening the panel used to reparse every profile file on disk — all servers ever
		// visited — just to label the tabs. Cache briefly; saves invalidate the cache.
		long now = System.currentTimeMillis();
		if (worldTabsCache != null && now - worldTabsCacheMillis < WORLD_TABS_CACHE_TTL_MS) {
			return new ArrayList<>(worldTabsCache);
		}
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
		worldTabsCache = new ArrayList<>(tabs);
		worldTabsCacheMillis = now;
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

	/**
	 * Lookup in the live profile only (for Jade / world highlight).
	 * <p>
	 * World-aware: prefers the record stamped with the world the player is standing in, and
	 * never answers with a record known to belong to a different world — a farm-world chest
	 * must not show its contents at the same coordinates in the build world. Untagged legacy
	 * records are still returned (unknown is not proof of anything).
	 */
	public synchronized @Nullable ContainerRecord findAtLive(String dimension, BlockPos pos, @Nullable BlockGetter level) {
		if (liveWorldId == null) {
			return null;
		}
		String currentTag = WorldFingerprint.current(Minecraft.getInstance());
		BlockPos canonical = ContainerKeys.canonicalPos(level, pos);
		ContainerRecord direct = lookupBlock(dimension, canonical.getX(), canonical.getY(), canonical.getZ(), currentTag);
		if (direct != null) {
			return direct;
		}
		// Fallback raw pos (legacy entries before canonicalization)
		if (!canonical.equals(pos)) {
			ContainerRecord raw = lookupBlock(dimension, pos.getX(), pos.getY(), pos.getZ(), currentTag);
			if (raw != null) {
				return raw;
			}
		}
		BlockPos other = ContainerKeys.otherHalf(level, pos);
		if (other != null) {
			return lookupBlock(dimension, other.getX(), other.getY(), other.getZ(), currentTag);
		}
		return null;
	}

	/**
	 * Record at this position as seen from the world {@code currentTag} describes:
	 * exact tagged key first, then the untagged legacy key — but only when that legacy
	 * record is not provably from another world.
	 */
	private @Nullable ContainerRecord lookupBlock(String dimension, int x, int y, int z, @Nullable String currentTag) {
		if (currentTag != null) {
			ContainerRecord tagged = liveContainers.get(ContainerRecord.makeKey(dimension, x, y, z, currentTag));
			if (tagged != null) {
				return tagged;
			}
		}
		ContainerRecord legacy = liveContainers.get(ContainerRecord.makeKey(dimension, x, y, z));
		if (legacy != null && !WorldTags.provablyDifferent(currentTag, legacy.worldTag())) {
			return legacy;
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
				// "Nearest" means the nearest WORLD chest: the ender chest and carried
				// shulkers are with the player, and a distance to them reads as nonsense.
				if (dist >= 0 && !record.isVirtual()) {
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
				.thenComparing(s -> sortName(s.itemId()), String.CASE_INSENSITIVE_ORDER);
			case NAME -> Comparator
				.comparing((ItemSummary s) -> sortName(s.itemId()), String.CASE_INSENSITIVE_ORDER)
				.thenComparing(ItemSummary::totalCount, Comparator.reverseOrder());
			case COUNT -> Comparator
				.comparing(ItemSummary::totalCount).reversed()
				.thenComparing(s -> sortName(s.itemId()), String.CASE_INSENSITIVE_ORDER);
		};
	}

	/** Display name with legacy colour codes stripped — "§6Меч" must sort under М, not §. */
	private static String sortName(String itemId) {
		String name = itemDisplayName(itemId);
		String stripped = net.minecraft.ChatFormatting.stripFormatting(name);
		return stripped == null ? name : stripped;
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
		// "Current world" must mean the world the player is standing in, not every world
		// that shares its dimension id on a multiworld server. Computed once per call,
		// not per record — WorldFingerprint walks client state.
		boolean guardWorld = dimensionFilter != null && dimensionFilter.kind() == DimensionChoice.Kind.CURRENT;
		String currentTag = guardWorld || scope == ListScope.NEARBY
			? WorldFingerprint.current(Minecraft.getInstance())
			: null;
		List<ContainerRecord> out = new ArrayList<>();
		for (ContainerRecord record : activeView().values()) {
			if (!ContainerFilter.matchesAny(record, typeFilters)) {
				continue;
			}
			if (!dimensionFilter.matches(record, playerDimension)) {
				continue;
			}
			if (guardWorld && record.isWorldBlock()
				&& WorldTags.provablyDifferent(currentTag, record.worldTag())) {
				continue;
			}
			if (scope == ListScope.NEARBY) {
				if (!isNearby(record, playerDimension, playerPos, nearbyRange, currentTag)) {
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
				// "Nearest" means the nearest WORLD chest: the ender chest and carried
				// shulkers are with the player, and a distance to them reads as nonsense.
				if (dist >= 0 && !record.isVirtual()) {
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
		double nearbyRange,
		@Nullable String currentWorldTag
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
		// Matching dimension ids are not enough on a multiworld server: the farm world's
		// Nether and the build world's Nether are both minecraft:the_nether, so a chest at
		// these coordinates in the other world would otherwise be reported as "nearby".
		if (!record.isVirtual()
			&& WorldTags.provablyDifferent(currentWorldTag, record.worldTag())) {
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
		// The ender chest is reachable from ANY ender chest in any world, so metres to
		// the one it was last opened at are meaningless — it is always "with you". The
		// remembered position stays, but only for the glow.
		if (record.isVirtual() && "ender_chest".equals(record.virtualId())) {
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
		// The blob is memoised and already contains the raw key plus the localized display
		// name; the registry lookup and ItemStack allocation that used to follow re-derived
		// what the blob already holds, once per item per keystroke.
		if (ItemStackKeys.searchBlob(itemId).contains(q)) {
			return true;
		}
		String lower = itemId.toLowerCase(Locale.ROOT);
		int colon = lower.indexOf(':');
		String path = colon >= 0 ? lower.substring(colon + 1) : lower;
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

	/**
	 * Display name for an item key. Delegates to the memoised {@link ItemStackKeys} cache —
	 * this is called from inside sort comparators, where the old registry lookup plus
	 * ItemStack allocation ran once per comparison, O(n log n) times per list refresh.
	 */
	public static String itemDisplayName(String itemId) {
		if (itemId == null) {
			return "?";
		}
		return ItemStackKeys.displayName(itemId);
	}

	public static String dimensionId(Level level) {
		return level.dimension().identifier().toString();
	}
}
