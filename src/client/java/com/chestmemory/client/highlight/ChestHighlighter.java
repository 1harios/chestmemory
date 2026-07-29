package com.chestmemory.client.highlight;

import com.chestmemory.client.data.ChestMemoryStorage;
import com.chestmemory.client.data.ColorPalette;
import com.chestmemory.client.data.ContainerRecord;
import com.chestmemory.client.data.ModSettings;
import com.chestmemory.client.litematica.BuildGatherSession;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.TextGizmo;
import net.minecraft.network.chat.Component;
import net.minecraft.util.ARGB;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * One clean outline per matching chest. Stops glowing as soon as memory says the item is gone.
 * White distance label above every matching chest.
 */
public final class ChestHighlighter {
	/** Fallback if settings not loaded yet. */
	public static final long DEFAULT_DURATION_MS = 20_000L;
	/** Cap labels so dense storages stay readable. */
	private static final int MAX_LABELS = 48;

	/** Pure white ARGB for distance text (full alpha). */
	private static final int DISTANCE_WHITE = 0xFFFFFFFF;

	private static @Nullable String highlightedItemId;
	private static long highlightUntilMillis;
	private static long highlightStartedMillis;
	/** Monotonic timestamp when the game was paused, 0 when running. */
	private static long pauseStartedMillis;
	/** Optional route: pos → order number (1-based). */
	private static final java.util.Map<BlockPos, Integer> routeOrder = new java.util.HashMap<>();
	/** Which stop in the route is the current focus (1-based), 0 = auto nearest. */
	private static int routeFocusOrder;
	/** Positions for item-icon overlay (updated each tick while active). */
	private static List<IconMarker> iconMarkers = List.of();

	/** Screen-space icon anchor above a highlighted chest. */
	public record IconMarker(BlockPos pos, boolean focus) {
	}

	private ChestHighlighter() {
	}

	public static List<IconMarker> iconMarkers() {
		return iconMarkers;
	}

	public static void highlightItem(String itemId, long durationMillis) {
		highlightedItemId = itemId;
		highlightStartedMillis = net.minecraft.util.Util.getMillis();
		highlightUntilMillis = highlightStartedMillis + durationMillis;
		routeOrder.clear();
		routeFocusOrder = 0;
	}

	/**
	 * Set ordered gather route for the current highlight (call after {@link #highlightItem}).
	 * @param orderedPositions route stops in visit order
	 */
	public static void setRoute(List<BlockPos> orderedPositions) {
		routeOrder.clear();
		routeFocusOrder = 0;
		if (orderedPositions == null) {
			return;
		}
		int i = 1;
		for (BlockPos p : orderedPositions) {
			if (p != null) {
				routeOrder.put(p.immutable(), i++);
			}
		}
		if (!routeOrder.isEmpty()) {
			routeFocusOrder = 1;
		}
	}

	/** Advance focus to the next chest on the route. Returns false if no more. */
	public static boolean focusNextRouteStop() {
		if (routeOrder.isEmpty()) {
			return false;
		}
		int max = routeOrder.values().stream().mapToInt(Integer::intValue).max().orElse(0);
		if (routeFocusOrder >= max) {
			return false;
		}
		routeFocusOrder++;
		// Refresh timer a bit so glow doesn't expire mid-route
		if (isActive()) {
			highlightUntilMillis = net.minecraft.util.Util.getMillis()
				+ Math.max(5000L, highlightUntilMillis - net.minecraft.util.Util.getMillis());
		}
		return true;
	}

	public static int routeFocusOrder() {
		return routeFocusOrder;
	}

	public static int routeSize() {
		return routeOrder.size();
	}

	public static void clear() {
		highlightedItemId = null;
		highlightUntilMillis = 0;
		highlightStartedMillis = 0;
		pauseStartedMillis = 0;
		routeOrder.clear();
		routeFocusOrder = 0;
		iconMarkers = List.of();
		// Drop the memoised match list too: it pins the profile snapshot (and its records)
		// in memory, which would keep a whole server profile alive after disconnecting.
		matchesCache = List.of();
		matchesCacheSnapshot = null;
		matchesCacheItemId = null;
	}

	public static @Nullable String getHighlightedItemId() {
		return isActive() ? highlightedItemId : null;
	}

	public static boolean isActive() {
		return highlightedItemId != null && net.minecraft.util.Util.getMillis() <= highlightUntilMillis;
	}

	public static float remainingSeconds() {
		if (!isActive()) {
			return 0;
		}
		return Math.max(0, (highlightUntilMillis - net.minecraft.util.Util.getMillis()) / 1000.0F);
	}

	public static void tick(Minecraft client) {
		LocalPlayer player = client.player;
		if (player == null || client.level == null) {
			if (!isActive()) {
				iconMarkers = List.of();
			}
			return;
		}

		// Singleplayer pause stops the game but not the clock, so opening the menu for
		// half a minute used to silently burn the whole highlight. Push the deadline out
		// by however long we were paused.
		if (client.isPaused()) {
			if (pauseStartedMillis == 0L) {
				pauseStartedMillis = net.minecraft.util.Util.getMillis();
			}
			// The world keeps rendering behind the pause menu, so returning before the
			// warehouse pass made every warehouse outline blink out the moment the menu
			// opened. Only the timed item highlight has a reason to sit out the pause.
			drawStagingWarehouses(client, player);
			return;
		}
		if (pauseStartedMillis != 0L) {
			long paused = net.minecraft.util.Util.getMillis() - pauseStartedMillis;
			pauseStartedMillis = 0L;
			if (paused > 0 && highlightedItemId != null) {
				highlightUntilMillis += paused;
				highlightStartedMillis += paused;
			}
		}

		// Always draw build-site warehouse chests (clan drop-off) when enabled
		drawStagingWarehouses(client, player);

		if (!isActive()) {
			if (highlightedItemId != null && net.minecraft.util.Util.getMillis() > highlightUntilMillis) {
				highlightedItemId = null;
				iconMarkers = List.of();
			}
			return;
		}

		String itemId = highlightedItemId;
		// During gather session keep the target even if memory is empty for this item
		// (do NOT clear — that wiped route when switching queue items).
		if (ChestMemoryStorage.get().liveItemTotal(itemId) <= 0 && !BuildGatherSession.isActive()) {
			clear();
			return;
		}

		String dimension = ChestMemoryStorage.dimensionId(client.level);
		Vec3 eye = player.getEyePosition();
		Vec3 playerPos = player.position();

		// Gather mode: all dimensions; normal mode: current dimension only
		boolean allDims = BuildGatherSession.isActive();
		List<ContainerRecord> matches = liveMatchesWithItem(itemId, dimension, allDims);
		if (matches.isEmpty()) {
			// Still keep highlight id so next N/B can retarget; nothing to draw this frame
			iconMarkers = List.of();
			return;
		}

		// Sort by distance, pick nearest for label + stronger outline
		record Located(ContainerRecord record, BlockPos pos, double dist, AABB box) {}
		List<Located> located = new ArrayList<>();
		for (ContainerRecord record : matches) {
			BlockPos pos = blockPosOf(record);
			if (pos == null) {
				continue;
			}
			double dist = eye.distanceTo(Vec3.atCenterOf(pos));
			if (dist > ModSettings.get().highlightRenderRange()) {
				continue;
			}
			// A remembered chest that has been broken would otherwise glow around empty air
			// until the verifier gets to it. Only skip when the chunk is loaded — an unloaded
			// chunk reads as air on the client, and skipping those would hide good chests.
			if (isMissingContainer(client, record, pos)) {
				continue;
			}
			AABB box = fullContainerBox(client, record, pos);
			located.add(new Located(record, pos, dist, box));
		}

		if (located.isEmpty()) {
			iconMarkers = List.of();
			return;
		}

		// Prefer route order when a gather route is active
		if (!routeOrder.isEmpty()) {
			located.sort(Comparator
				.comparingInt((Located l) -> routeOrder.getOrDefault(l.pos(), 999))
				.thenComparingDouble(Located::dist));
		} else {
			located.sort(Comparator.comparingDouble(Located::dist));
		}

		Located focus = located.getFirst();
		if (routeFocusOrder > 0 && !routeOrder.isEmpty()) {
			for (Located loc : located) {
				Integer ord = routeOrder.get(loc.pos());
				if (ord != null && ord == routeFocusOrder) {
					focus = loc;
					break;
				}
			}
		}

		long now = net.minecraft.util.Util.getMillis();
		float remain = (highlightUntilMillis - now) / 1000.0F;
		// Soft pulse, not frantic
		float pulse = 0.88F + 0.12F * (float) Math.sin(now / 400.0);
		float fade = remain < 3.0F ? Math.max(0.2F, remain / 3.0F) : 1.0F;
		float intensity = pulse * fade;

		boolean showDist = ModSettings.get().showDistanceLabels();
		boolean distAll = ModSettings.get().distanceOnAllChests();

		List<IconMarker> markers = new ArrayList<>();
		int shown = 0;
		for (Located loc : located) {
			boolean isFocus = loc == focus;
			Integer order = routeOrder.isEmpty() ? null : routeOrder.get(loc.pos());
			drawCleanOutline(loc.box(), isFocus, intensity);
			if (order != null) {
				drawRouteLabel(loc.pos(), order, loc.dist(), isFocus);
			} else if (showDist && (distAll || isFocus)) {
				drawDistanceLabel(loc.pos(), loc.dist(), isFocus);
			}
			// Item icon above chest (focus + first stops on route / nearest few)
			if (isFocus || order != null || shown < 8) {
				markers.add(new IconMarker(loc.pos(), isFocus));
			}
			shown++;
			if (shown >= MAX_LABELS) {
				break;
			}
		}
		iconMarkers = markers;
	}

	/**
	 * Memoised result of the non-gather branch of {@link #liveMatchesWithItem}. The tick
	 * used to re-walk every record of the profile 20×/second for the whole 20-second
	 * highlight; the snapshot list is identity-cached inside the storage and only replaced
	 * when a container is actually scanned or forgotten, so its reference doubles as a
	 * "did memory change" signal and the walk runs once per change instead of per tick.
	 */
	private static List<ContainerRecord> matchesCache = List.of();
	private static @Nullable List<ContainerRecord> matchesCacheSnapshot;
	private static @Nullable String matchesCacheItemId;
	private static @Nullable String matchesCacheDimension;
	private static @Nullable String matchesCacheWorldTag;
	private static boolean matchesCacheAllDimensions;

	/**
	 * Containers in live memory that still hold the item.
	 * @param allDimensions if false, only same dimension as the player
	 */
	private static List<ContainerRecord> liveMatchesWithItem(
		String itemId,
		String playerDimension,
		boolean allDimensions
	) {
		ChestMemoryStorage storage = ChestMemoryStorage.get();
		// During gather: never glow build-site warehouse (staging) — those are “already collected”
		boolean hideStaging = BuildGatherSession.isActive();
		// A chest from the other world of a multiworld server sits at coordinates that also
		// exist here; glowing it would point the player at a block that holds something else.
		String currentTag = com.chestmemory.client.data.WorldFingerprint.current(Minecraft.getInstance());

		if (hideStaging && allDimensions) {
			// Gather mode maps exactly onto the storage's indexed query: it reads the
			// item → containers reverse index instead of walking the whole profile, and
			// applies the same staging / dimension / highlightable-position rules this
			// branch needs. Its all-dimensions form skips the world-tag rule, though, so
			// that one is re-applied here — without it a chest from the other world of a
			// multiworld server would glow at coordinates that hold something else.
			List<ContainerRecord> out = new ArrayList<>();
			for (ContainerRecord r : storage.liveSourceHighlightableWithItem(itemId)) {
				if (r.isWorldBlock() && com.chestmemory.client.data.WorldTags.provablyDifferent(
					currentTag, r.worldTag())) {
					continue;
				}
				out.add(r);
			}
			return out;
		}

		// Outside a gather, staging chests holding the item must keep glowing, and the
		// indexed query excludes them unconditionally — so this branch keeps the full walk
		// but memoises it on the snapshot's identity (see matchesCache). Only cache when
		// staging is not being hidden: in the one-tick window where the gather flag flips
		// between the caller reading it and this method reading it, a staging-filtered
		// result could otherwise be served to a later non-gather tick with the same key.
		boolean cacheable = !hideStaging;
		List<ContainerRecord> snapshot = storage.liveContainersSnapshot();
		if (cacheable
			&& snapshot == matchesCacheSnapshot
			&& itemId.equals(matchesCacheItemId)
			&& allDimensions == matchesCacheAllDimensions
			&& java.util.Objects.equals(playerDimension, matchesCacheDimension)
			&& java.util.Objects.equals(currentTag, matchesCacheWorldTag)) {
			return matchesCache;
		}

		List<ContainerRecord> out = new ArrayList<>();
		for (ContainerRecord r : snapshot) {
			if (r.countOf(itemId) <= 0) {
				continue;
			}
			if (hideStaging && storage.isStaging(r)) {
				continue;
			}
			if (r.isWorldBlock() || r.hasHighlightPos()) {
				if (!allDimensions && playerDimension != null && r.dimension() != null
					&& !playerDimension.equals(r.dimension())) {
					continue;
				}
				if (r.isWorldBlock() && com.chestmemory.client.data.WorldTags.provablyDifferent(
					currentTag, r.worldTag())) {
					continue;
				}
				out.add(r);
			}
		}

		if (cacheable) {
			matchesCacheSnapshot = snapshot;
			matchesCacheItemId = itemId;
			matchesCacheDimension = playerDimension;
			matchesCacheWorldTag = currentTag;
			matchesCacheAllDimensions = allDimensions;
			matchesCache = out;
		}
		return out;
	}

	/** Extend glow duration (used when advancing queue). */
	public static void refreshDuration(long durationMillis) {
		if (highlightedItemId == null) {
			return;
		}
		long now = net.minecraft.util.Util.getMillis();
		highlightStartedMillis = now;
		highlightUntilMillis = now + Math.max(3000L, durationMillis);
	}

	/**
	 * Merged local + clan staging keys for the warehouse pass. Building this fresh meant
	 * two set copies per tick for a set that only changes when a mark is toggled, the
	 * profile switches, or a clan sync lands. Those are caught by the cheap signals below;
	 * addStagingAt can swap a legacy key for a tagged one in a single call (count stays
	 * equal), which no cheap signal sees — the timed refresh bounds that staleness.
	 */
	private static java.util.Set<String> warehouseKeys = java.util.Set.of();
	private static int warehouseKeysStagingCount = -1;
	private static @Nullable Object warehouseKeysClanSession;
	private static @Nullable String warehouseKeysWorldId;
	private static long warehouseKeysRefreshedAt;
	private static final long WAREHOUSE_KEYS_MAX_AGE_MS = 500L;
	/** «Склад» label — resolving the translatable ran every tick for a constant string. */
	private static @Nullable String warehouseLabel;
	private static @Nullable String warehouseLabelLanguage;

	/**
	 * Warehouse (staging) chest glow — solo gather and clan.
	 * Purple outline + «Склад» label. Disabled via settings.
	 */
	private static void drawStagingWarehouses(Minecraft client, LocalPlayer player) {
		// Master switch in Ё → settings → «Подсветка склада»
		if (!ModSettings.get().showWarehouseGlow()) {
			return;
		}

		ChestMemoryStorage storage = ChestMemoryStorage.get();
		// Clan-shared warehouse (even if this client never opened those chests). The hub
		// sync replaces the whole session object, so its identity doubles as a change signal.
		var clan = com.chestmemory.client.clan.ClanSessionManager.session();
		long now = net.minecraft.util.Util.getMillis();

		int stagingCount = storage.stagingCount();
		String worldId = storage.liveWorldId();
		if (stagingCount != warehouseKeysStagingCount
			|| clan != warehouseKeysClanSession
			|| !java.util.Objects.equals(worldId, warehouseKeysWorldId)
			|| now - warehouseKeysRefreshedAt >= WAREHOUSE_KEYS_MAX_AGE_MS) {
			java.util.Set<String> merged = new java.util.LinkedHashSet<>(storage.stagingKeysSnapshot());
			if (clan != null && clan.stagingKeys != null) {
				merged.addAll(clan.stagingKeys);
			}
			warehouseKeys = merged;
			warehouseKeysStagingCount = stagingCount;
			warehouseKeysClanSession = clan;
			warehouseKeysWorldId = worldId;
			warehouseKeysRefreshedAt = now;
		}
		java.util.Set<String> keys = warehouseKeys;
		// Empty → nothing to draw. Non-empty + setting on → glow for solo and clan alike.
		if (keys.isEmpty()) {
			return;
		}

		String playerDim = ChestMemoryStorage.dimensionId(client.level);
		Vec3 eye = player.getEyePosition();
		float pulse = 0.85F + 0.15F * (float) Math.sin(now / 350.0);
		int rgb = ModSettings.get().warehouseColor();
		int range = ModSettings.get().highlightRenderRange();

		// The cached string is localized, so it follows the same invalidation the display
		// name cache in ItemStackKeys uses: re-resolve only when the language changes.
		String lang = client.getLanguageManager() != null
			? client.getLanguageManager().getSelected()
			: null;
		String label = warehouseLabel;
		if (label == null || !java.util.Objects.equals(lang, warehouseLabelLanguage)) {
			label = Component.translatable("hud.chestmemory.warehouse_label").getString();
			warehouseLabel = label;
			warehouseLabelLanguage = lang;
		}
		int shown = 0;
		for (String key : keys) {
			if (shown >= MAX_LABELS) {
				break;
			}
			String[] parsed = ChestMemoryStorage.parseStagingKey(key);
			if (parsed == null) {
				continue;
			}
			String dim = parsed[0];
			if (playerDim != null && dim != null && !playerDim.equals(dim)) {
				continue;
			}
			int x;
			int y;
			int z;
			try {
				x = Integer.parseInt(parsed[1]);
				y = Integer.parseInt(parsed[2]);
				z = Integer.parseInt(parsed[3]);
			} catch (NumberFormatException e) {
				continue;
			}
			BlockPos pos = new BlockPos(x, y, z);
			double dist = eye.distanceTo(Vec3.atCenterOf(pos));
			if (dist > range) {
				continue;
			}
			// Prefer memory record for double-chest volume (staging keys are untagged and
			// shared with the clan; the record itself is keyed per world).
			ContainerRecord rec = ChestMemoryStorage.get().findByStagingKey(key);
			// Same guard as the item highlight: a warehouse mark on a chest that no longer
			// exists should not paint a label in mid-air.
			if (client.level != null && client.level.isLoaded(pos) && !isContainerBlockAt(client, pos)) {
				continue;
			}
			AABB box = fullContainerBox(client, rec, pos);
			drawRgbOutline(box, rgb, pulse, true);
			// Label “Склад” above chest
			Vec3 base = Vec3.atLowerCornerWithOffset(pos, 0.5, 1.45, 0.5);
			Gizmos.billboardText(
				label + " · " + (int) Math.round(dist) + "m",
				base,
				TextGizmo.Style.forColorAndCentered(0xFF000000 | rgb).withScale(0.55F)
			).setAlwaysOnTop();
			shown++;
		}
	}

	/**
	 * Single outline box — no stacked rings, no waypoint halo on top.
	 */
	private static void drawCleanOutline(AABB box, boolean nearest, float intensity) {
		ModSettings s = ModSettings.get();
		int rgb = nearest ? s.nearestColor() : s.glowColor();
		drawRgbOutline(box, rgb, intensity, nearest);
	}

	private static void drawRgbOutline(AABB box, int rgb, float intensity, boolean strong) {
		// Slight inflate so edges sit outside the chest model
		AABB outline = box.inflate(strong ? 0.05 : 0.03);

		int r = (rgb >> 16) & 0xFF;
		int g = (rgb >> 8) & 0xFF;
		int b = rgb & 0xFF;

		ModSettings s = ModSettings.get();
		float strength = switch (s.glowIntensity()) {
			case 0 -> 0.65F;
			case 2 -> 1.25F;
			default -> 1.0F;
		};
		float mult = intensity * strength;

		int strokeA = Math.max(40, Math.min(240, (int) ((strong ? 220 : 170) * mult)));
		int fillA = Math.max(0, Math.min(70, (int) ((strong ? 48 : 28) * mult)));
		int fillRgb = ColorPalette.softFillRgb(rgb);

		int stroke = ARGB.color(strokeA, r, g, b);
		int fill = ARGB.color(fillA, (fillRgb >> 16) & 0xFF, (fillRgb >> 8) & 0xFF, fillRgb & 0xFF);

		float width = (strong ? 2.6F : 1.8F) * (s.glowIntensity() >= 2 ? 1.15F : 1.0F);
		Gizmos.cuboid(outline, GizmoStyle.strokeAndFill(stroke, width, fill)).setAlwaysOnTop();
	}

	/**
	 * Bright white distance above the chest. No black layers — they were drowning the text.
	 */
	private static void drawDistanceLabel(BlockPos pos, double dist, boolean nearest) {
		String label = (int) Math.max(0, Math.round(dist)) + " m";
		Vec3 base = Vec3.atLowerCornerWithOffset(pos, 0.5, 1.5, 0.5);
		// Slightly larger on nearest so it's easy to spot
		float scale = nearest ? 0.65F : 0.5F;

		// -1 / 0xFFFFFFFF = full white (same as TextGizmo.Style.whiteAndCentered)
		Gizmos.billboardText(
			label,
			base,
			TextGizmo.Style.forColorAndCentered(DISTANCE_WHITE).withScale(scale)
		).setAlwaysOnTop();
	}

	/** Route stop: "1 · 12m" */
	private static void drawRouteLabel(BlockPos pos, int order, double dist, boolean focus) {
		String label = order + " · " + (int) Math.max(0, Math.round(dist)) + "m";
		Vec3 base = Vec3.atLowerCornerWithOffset(pos, 0.5, 1.55, 0.5);
		float scale = focus ? 0.7F : 0.5F;
		int focusRgb = ModSettings.get().routeFocusColor();
		int color = focus
			? (0xFF000000 | focusRgb)
			: DISTANCE_WHITE;
		Gizmos.billboardText(
			label,
			base,
			TextGizmo.Style.forColorAndCentered(color).withScale(scale)
		).setAlwaysOnTop();
	}

	private static @Nullable BlockPos blockPosOf(ContainerRecord record) {
		if (record.isVirtual()) {
			if (!record.hasHighlightPos()) {
				return null;
			}
			return new BlockPos(record.highlightX(), record.highlightY(), record.highlightZ());
		}
		return new BlockPos(record.x(), record.y(), record.z());
	}

	/**
	 * True when the record points at a block that is no longer a container.
	 * <p>
	 * Deliberately conservative: answers false whenever the answer cannot be trusted — a
	 * virtual record (ender chest has no fixed block), a null level, or an unloaded chunk,
	 * which reads as air on the client and would otherwise hide every distant chest.
	 */
	private static boolean isMissingContainer(Minecraft client, ContainerRecord record, BlockPos pos) {
		if (!record.isWorldBlock() || client.level == null || !client.level.isLoaded(pos)) {
			return false;
		}
		// On a multiworld server the same coordinates exist in another world's Nether. Do not
		// judge a record that belongs to a world we are not standing in — and do not draw it
		// either; the caller skips whatever this returns true for.
		if (com.chestmemory.client.data.WorldFingerprint.provablyDifferent(
			com.chestmemory.client.data.WorldFingerprint.of(client.level), record.worldTag())) {
			return true;
		}
		if (isContainerBlockAt(client, pos)) {
			return false;
		}
		// Half of a double chest can be broken while the other half stands; the record is
		// keyed on one position, so check its partner before calling it gone.
		if (record.hasOtherHalf()) {
			BlockPos other = new BlockPos(record.otherX(), record.otherY(), record.otherZ());
			return !(client.level.isLoaded(other) && isContainerBlockAt(client, other));
		}
		return true;
	}

	private static boolean isContainerBlockAt(Minecraft client, BlockPos pos) {
		if (client.level == null) {
			return false;
		}
		var block = client.level.getBlockState(pos).getBlock();
		return block instanceof ChestBlock
			|| block instanceof net.minecraft.world.level.block.BarrelBlock
			|| block instanceof net.minecraft.world.level.block.ShulkerBoxBlock
			|| block instanceof net.minecraft.world.level.block.EnderChestBlock
			|| block instanceof net.minecraft.world.level.block.HopperBlock
			|| block instanceof net.minecraft.world.level.block.DispenserBlock;
	}

	/**
	 * Full volume of a chest — always covers both halves of a double chest.
	 */
	public static AABB fullContainerBox(Minecraft client, ContainerRecord record, BlockPos pos) {
		AABB base = new AABB(pos);

		if (record != null && record.hasOtherHalf()) {
			BlockPos other = new BlockPos(record.otherX(), record.otherY(), record.otherZ());
			return base.minmax(new AABB(other));
		}

		if (client.level == null) {
			return base;
		}

		BlockState state = client.level.getBlockState(pos);
		if (state.getBlock() instanceof ChestBlock) {
			ChestType type = state.getValue(ChestBlock.TYPE);
			if (type != ChestType.SINGLE) {
				BlockPos other = ChestBlock.getConnectedBlockPos(pos, state);
				return base.minmax(new AABB(other));
			}
			if (record != null && record.doubleChest()) {
				for (Direction d : Direction.Plane.HORIZONTAL) {
					BlockPos n = pos.relative(d);
					BlockState ns = client.level.getBlockState(n);
					if (ns.getBlock() instanceof ChestBlock) {
						return base.minmax(new AABB(n));
					}
				}
			}
		}
		return base;
	}
}
