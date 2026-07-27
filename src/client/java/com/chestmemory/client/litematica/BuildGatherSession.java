package com.chestmemory.client.litematica;

import com.chestmemory.client.data.ChestMemoryStorage;
import com.chestmemory.client.data.ContainerFilter;
import com.chestmemory.client.data.ContainerRecord;
import com.chestmemory.client.data.DimensionChoice;
import com.chestmemory.client.data.ItemSummary;
import com.chestmemory.client.data.ListScope;
import com.chestmemory.client.data.ModSettings;
import com.chestmemory.client.data.SortMode;
import com.chestmemory.client.highlight.ChestHighlighter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Gather-for-schematic session.
 * <p>
 * <b>Phases</b>
 * <ul>
 *   <li>{@link GatherPhase#CHESTS} — only items that exist in live chest memory
 *       (enough first, then partial). Craft-only items are never auto-selected.</li>
 *   <li>{@link GatherPhase#CRAFT} — only after chest phase is empty (or user forces via N).</li>
 * </ul>
 * <p>
 * Hotkey <b>N</b> — next material (skip current; chests phase first, then craft).
 * All matching chests are highlighted at once — no “next chest” key.
 */
public final class BuildGatherSession {
	public enum GatherPhase {
		/** Collect from remembered chests only. */
		CHESTS,
		/** Remaining materials with nothing in chests (craft / buy / other). */
		CRAFT
	}

	private static boolean active;
	private static BuildFilter filter = BuildFilter.ALL;
	private static GatherPhase phase = GatherPhase.CHESTS;

	/** Display / progress order for HUD. */
	private static final List<String> queue = new ArrayList<>();
	/** Schematic totals snapshot. */
	private static final Map<String, Integer> queueMissing = new HashMap<>();
	/** Manually skipped via N (within current phase). */
	private static final Set<String> skipped = new HashSet<>();

	private static int queueIndex;
	private static @Nullable String currentItemId;
	private static List<ChestRoute.Stop> currentRoute = List.of();
	private static int routeChestIndex;
	/** Rebuilt on tick, read by the HUD render path — volatile publish of an immutable snapshot. */
	private static volatile List<HudLine> hudLines = List.of();
	private static @Nullable String listName;
	/** Material list the current queueMissing snapshot belongs to. */
	private static @Nullable String snapshotListName;
	/**
	 * Item clicked in the panel whose clan claim has not come back from the hub yet.
	 * Survives resetState so startQueue cannot lose the player's pick.
	 */
	private static @Nullable String pendingClaimFocus;
	private static int tickCounter;
	private static long lastAdvanceMillis;
	private static boolean highlightPaused;

	private BuildGatherSession() {
	}

	public static boolean isActive() {
		return active;
	}

	public static GatherPhase phase() {
		return phase;
	}

	public static BuildFilter filter() {
		return filter;
	}

	public static void setFilter(BuildFilter f) {
		filter = f != null ? f : BuildFilter.ALL;
	}

	public static BuildFilter cycleFilter() {
		filter = filter.next();
		return filter;
	}

	public static @Nullable String currentItemId() {
		return currentItemId;
	}

	public static List<HudLine> hudLines() {
		return hudLines;
	}

	public static @Nullable String listName() {
		return listName;
	}

	public static List<ChestRoute.Stop> currentRoute() {
		return currentRoute;
	}

	public static int routeChestIndex() {
		return routeChestIndex;
	}

	public static void setActive(boolean on) {
		active = on;
		// Keep the material list alive across dimension changes, but only while gathering:
		// Litematica clears its own list on every world load, so a portal trip would
		// otherwise look like a finished build. See MaterialListCache.
		MaterialListCache.setArmed(on);
		if (!on) {
			resetState();
		} else {
			listName = LitematicaAccess.activeListName();
		}
	}

	private static void resetState() {
		queue.clear();
		queueMissing.clear();
		skipped.clear();
		queueIndex = 0;
		currentItemId = null;
		currentRoute = List.of();
		routeChestIndex = 0;
		hudLines = List.of();
		listName = null;
		snapshotListName = null;
		lastAdvanceMillis = 0;
		highlightPaused = false;
		phase = GatherPhase.CHESTS;
		// pendingClaimFocus deliberately survives: startQueue resets state immediately after
		// the panel click, and the player's pick must outlive that.
	}

	public static void pauseSchemeHighlight() {
		highlightPaused = true;
	}

	public static void resumeSchemeHighlight() {
		highlightPaused = false;
	}

	public static boolean isHighlightPaused() {
		return highlightPaused;
	}

	// ── inventory / need ───────────────────────────────────────────────────

	public static int remainingNeed(String itemId) {
		return remainingNeed(itemId, Minecraft.getInstance() != null ? Minecraft.getInstance().player : null);
	}

	public static int remainingNeed(String itemId, @Nullable LocalPlayer player) {
		if (itemId == null) {
			return 0;
		}
		int inStaging = countInStaging(itemId);
		// Clan delivered (shared warehouse progress) merges as max with local staging
		int clanDel = com.chestmemory.client.clan.ClanSessionManager.clanDelivered(itemId);
		int warehouse = Math.max(inStaging, clanDel);
		// A clan gather counts what reached the warehouse, nothing else. Counting the
		// backpack marked an item finished the moment it was picked up: the gather advanced
		// to the next target while the hub was still told delivered = 0, because the report
		// reads the warehouse. Locally done, clan-wise nothing happened.
		//
		// Solo it stays as it was — there is no warehouse to require, and carrying the
		// material IS having gathered it.
		boolean clanGather = com.chestmemory.client.clan.ClanSessionManager.isInActiveGather(itemId);
		int inPlayer = clanGather ? 0 : countInPlayer(player, itemId);
		int covered = inPlayer + warehouse;
		for (LitematicaCompat.MaterialNeed n : LitematicaAccess.missingMaterials()) {
			if (itemId.equals(n.itemId())) {
				// Progress = inv + staging / clan delivered (not source chests)
				return Math.max(0, n.total() - covered);
			}
		}
		int snapTotal = queueMissing.getOrDefault(itemId, 0);
		if (snapTotal <= 0) {
			// Clan session may define need without local Litematica list
			int clanNeed = com.chestmemory.client.clan.ClanSessionManager.clanNeed(itemId);
			if (clanNeed > 0) {
				return Math.max(0, clanNeed - covered);
			}
			return 0;
		}
		return Math.max(0, snapTotal - covered);
	}

	/** Items already in marked build-site warehouse chests. */
	public static int countInStaging(String itemId) {
		return ChestMemoryStorage.get().countInStaging(itemId);
	}

	public static int countInPlayer(@Nullable LocalPlayer player, String itemId) {
		if (player == null || itemId == null) {
			return 0;
		}
		Inventory inv = player.getInventory();
		int total = 0;
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack stack = inv.getItem(i);
			if (!stack.isEmpty() && com.chestmemory.client.data.ItemStackKeys.matches(stack, itemId)) {
				total += stack.getCount();
			}
		}
		return total;
	}

	/**
	 * Count in <b>source</b> chests only (live memory, excludes build-site warehouse).
	 * All dimensions (routes / global stock).
	 */
	public static int countInChestsLive(String itemId) {
		return ChestMemoryStorage.get().countInSourceChests(itemId);
	}

	public static int countInChestsLive(String itemId, DimensionChoice dimFilter, @Nullable String playerDim) {
		return ChestMemoryStorage.get().countInSourceChests(itemId, dimFilter, playerDim);
	}

	/** Nearest live source chest holding the item, in blocks — or -1 when unknown. */
	public static double nearestChestDistance(@Nullable Minecraft client, String itemId) {
		if (client == null || client.player == null || client.level == null || itemId == null) {
			return -1;
		}
		return nearestLiveDist(
			itemId, client.player.position(),
			ChestMemoryStorage.dimensionId(client.level), DimensionChoice.ALL
		);
	}

	/** Source containers (not staging) for routes / glow — all dimensions. */
	private static List<ContainerRecord> liveHighlightableWithItem(String itemId) {
		return ChestMemoryStorage.get().liveSourceHighlightableWithItem(itemId);
	}

	private static double nearestLiveDist(
		String itemId,
		@Nullable Vec3 pos,
		@Nullable String dim,
		DimensionChoice dimFilter
	) {
		if (pos == null) {
			return -1;
		}
		double best = Double.MAX_VALUE;
		boolean any = false;
		List<ContainerRecord> list = ChestMemoryStorage.get()
			.liveSourceHighlightableWithItem(itemId, dimFilter, dim);
		for (ContainerRecord r : list) {
			double d = ChestMemoryStorage.distanceTo(r, pos, dim);
			if (d < 0) {
				continue;
			}
			any = true;
			if (d < best) {
				best = d;
			}
		}
		return any ? best : -1;
	}

	// ── start / clear ──────────────────────────────────────────────────────

	/**
	 * Start gather. Always begins in CHESTS phase.
	 * If {@code first} has stock in live memory, focus it; otherwise best chest item.
	 * Craft-only clicks do not jump past remaining chest items.
	 */
	public static void startQueue(Minecraft client, String first, List<String> orderedIds) {
		resetState();
		// Arm the list cache BEFORE reading materials: this is the real entry point for a
		// gather (setActive is not on this path), and snapshotTotals below already needs the
		// cache live. Missing this made the portal fix dead code — armed stayed false, so a
		// world load still emptied the list.
		MaterialListCache.setArmed(true);
		snapshotTotals();
		active = true;
		phase = GatherPhase.CHESTS;
		listName = LitematicaAccess.activeListName();

		String startId = null;
		if (first != null
			&& remainingNeed(first, client.player) > 0
			&& countInChestsLive(first) > 0) {
			startId = first;
		}
		if (startId == null) {
			startId = bestIdForPhase(client, GatherPhase.CHESTS, null);
		}
		// User clicked a craft-only item while chests still have stuff — start chests, hint
		if (startId != null
			&& first != null
			&& !first.equals(startId)
			&& countInChestsLive(first) <= 0
			&& client.player != null) {
			client.player.sendSystemMessage(Component.translatable(
				"message.chestmemory.build_chests_first",
				ChestMemoryStorage.itemDisplayName(first)
			));
		}
		if (startId == null) {
			// No chest stock at all — offer craft phase
			phase = GatherPhase.CRAFT;
			startId = bestIdForPhase(client, GatherPhase.CRAFT, null);
			if (startId != null && client.player != null) {
				client.player.sendSystemMessage(Component.translatable("message.chestmemory.build_phase_craft"));
			}
		}
		if (startId == null) {
			if (client.player != null) {
				client.player.sendSystemMessage(Component.translatable("message.chestmemory.build_queue_done"));
			}
			refreshHud(client);
			return;
		}
		focusItem(client, startId, true);
		refreshHud(client);
	}

	public static void clear() {
		setActive(false);
		ChestHighlighter.clear();
	}

	/**
	 * Suspend the gather without throwing it away.
	 * <p>
	 * A multiworld server issues a real disconnect when you portal between its worlds, so the
	 * old behaviour — clearing on disconnect — destroyed a build in progress: the material
	 * list cache was dropped, the queue was emptied, and the «Сбор» button had nothing left to
	 * open. The queue and the cached list are kept; only the world-bound parts (highlight,
	 * route) are dropped, because they point at blocks in a world we just left.
	 */
	public static void park() {
		ChestHighlighter.clear();
		currentRoute = List.of();
		routeChestIndex = 0;
		highlightPaused = false;
		// Deliberately keeps: active, queue, queueMissing, skipped, listName, and the material
		// list cache. Those describe the build, not the connection.
	}

	// ── tick ───────────────────────────────────────────────────────────────

	public static void tick(Minecraft client) {
		if (!active || client.player == null || client.level == null) {
			hudLines = List.of();
			return;
		}
		if (!LitematicaAccess.isAvailable() || !LitematicaAccess.hasActiveMaterialList()) {
			refreshHud(client);
			return;
		}

		if (++tickCounter % 5 != 0) {
			return;
		}

		long now = System.currentTimeMillis();
		String highlighted = ChestHighlighter.getHighlightedItemId();

		if (highlighted != null && currentItemId != null && !currentItemId.equals(highlighted)) {
			highlightPaused = true;
		}

		int need = currentItemId != null ? remainingNeed(currentItemId, client.player) : 0;

		if (ModSettings.get().gatherAutoAdvance()
			&& currentItemId != null
			&& need <= 0
			&& now - lastAdvanceMillis > 600L) {
			lastAdvanceMillis = now;
			highlightPaused = false;
			String doneName = ChestMemoryStorage.itemDisplayName(currentItemId);
			// Report the delivery now instead of waiting for the 10s staging tick. The target
			// switched the instant the warehouse covered the need, so the player saw a new item
			// before the hub knew anything had been handed in — it read as "not counted".
			com.chestmemory.client.clan.ClanSessionManager.reportStagedNow(client, currentItemId);
			boolean wasClaimed = com.chestmemory.client.clan.ClanSessionManager
				.isClaimedByMe(client, currentItemId);
			currentItemId = null;
			currentRoute = List.of();
			chat(client, Component.translatable("message.chestmemory.build_got_enough", doneName));
			// Auto-advance only to something the player actually signed up for. Finishing a
			// claimed item used to fall through to the ranking and jump to a material they never
			// picked; in a clan the next move is theirs.
			if (wasClaimed && firstOwnClaim(client, null) == null) {
				hudLines = List.of();
				refreshHud(client);
				return;
			}
			// Auto only within same phase — never auto-jump into craft
			if (!focusBestInPhase(client, true)) {
				onPhaseExhausted(client, true);
			}
		} else if (!highlightPaused
			&& currentItemId != null
			&& need > 0
			&& highlighted == null) {
			// Glow timed out — restore same item (do not switch)
			focusItem(client, currentItemId, false);
		}

		refreshHud(client);
	}

	// ── N: next item ───────────────────────────────────────────────────────

	/**
	 * N — next material. CHESTS phase first; when empty, enters CRAFT phase.
	 */
	public static void skipCurrentItem(Minecraft client) {
		if (!active) {
			if (LitematicaAccess.isAvailable() && LitematicaAccess.hasActiveMaterialList()) {
				startQueue(client, null, List.of());
				return;
			}
			if (client.player != null) {
				client.player.sendSystemMessage(Component.translatable("message.chestmemory.build_no_session"));
			}
			return;
		}

		highlightPaused = false;
		lastAdvanceMillis = System.currentTimeMillis();

		// No target: pick best in current phase (or start chests)
		if (currentItemId == null) {
			if (focusBestInPhase(client, true)) {
				refreshHud(client);
				return;
			}
			// Try other phase
			if (phase == GatherPhase.CHESTS) {
				enterCraftPhase(client, true);
				if (focusBestInPhase(client, true)) {
					refreshHud(client);
					return;
				}
			} else {
				// Craft empty — go back to chests if any refilled
				phase = GatherPhase.CHESTS;
				skipped.clear();
				if (focusBestInPhase(client, true)) {
					refreshHud(client);
					return;
				}
			}
			if (client.player != null) {
				client.player.sendSystemMessage(Component.translatable("message.chestmemory.build_queue_done"));
			}
			refreshHud(client);
			return;
		}

		// Skip current item
		String skippedName = ChestMemoryStorage.itemDisplayName(currentItemId);
		skipped.add(currentItemId);
		if (client.player != null) {
			client.player.sendSystemMessage(Component.translatable(
				"message.chestmemory.build_skipped",
				skippedName
			));
		}
		currentItemId = null;
		currentRoute = List.of();
		ChestHighlighter.clear();

		if (focusBestInPhase(client, true)) {
			refreshHud(client);
			return;
		}

		// Phase exhausted (all remaining skipped or done)
		if (phase == GatherPhase.CHESTS) {
			// Clear skips in chest phase once — maybe only skips left
			Set<String> wasSkipped = new HashSet<>(skipped);
			skipped.clear();
			// Re-pick only if there are chest items that weren't "done"
			String id = bestIdForPhase(client, GatherPhase.CHESTS, null);
			if (id != null && !wasSkipped.contains(id)) {
				focusItem(client, id, true);
				refreshHud(client);
				return;
			}
			// Truly no more chest stock (or all were skipped) → craft
			skipped.clear();
			enterCraftPhase(client, true);
			if (focusBestInPhase(client, true)) {
				refreshHud(client);
				return;
			}
		} else {
			// Craft phase: clear skips and retry, else done
			skipped.clear();
			if (focusBestInPhase(client, true)) {
				if (client.player != null) {
					client.player.sendSystemMessage(Component.translatable("message.chestmemory.build_queue_restart"));
				}
				refreshHud(client);
				return;
			}
		}

		finishQueue(client, true);
	}

	/** Soft advance (auto-collect) — does not step chests. Prefer {@link #skipCurrentItem} for N. */
	public static void advance(Minecraft client, boolean announce) {
		// Kept for any external callers — same as N without permanent skip of current
		highlightPaused = false;
		lastAdvanceMillis = System.currentTimeMillis();
		if (currentItemId != null) {
			// Only completed items leave the queue; items still needed stay pickable.
			if (remainingNeed(currentItemId, client.player) <= 0) {
				skipped.add(currentItemId);
			}
			currentItemId = null;
		}
		if (!focusBestInPhase(client, announce)) {
			onPhaseExhausted(client, announce);
		}
	}

	// ── phase / ranking ────────────────────────────────────────────────────

	private static void enterCraftPhase(Minecraft client, boolean announce) {
		phase = GatherPhase.CRAFT;
		skipped.clear();
		if (announce) {
			chat(client, Component.translatable("message.chestmemory.build_phase_craft"));
		}
	}

	private static void onPhaseExhausted(Minecraft client, boolean announce) {
		if (phase == GatherPhase.CHESTS) {
			skipped.clear();
			// If any chest items still needed and not all skipped-only empty
			if (bestIdForPhase(client, GatherPhase.CHESTS, null) != null) {
				// only skips blocked — clear and continue chests
				if (focusBestInPhase(client, announce)) {
					return;
				}
			}
			enterCraftPhase(client, announce);
			if (focusBestInPhase(client, announce)) {
				return;
			}
		}
		finishQueue(client, announce);
	}

	private static void finishQueue(Minecraft client, boolean announce) {
		currentItemId = null;
		currentRoute = List.of();
		routeChestIndex = 0;
		queue.clear();
		queueIndex = 0;
		ChestHighlighter.clear();
		if (announce) {
			chat(client, Component.translatable("message.chestmemory.build_queue_done"));
		}
		refreshHud(client);
	}

	private static void snapshotTotals() {
		// Entries are only ever added here, so switching Litematica to a different
		// schematic without ending the session left the previous build's materials in the
		// queue — mixing two material lists with stale totals. Reset when the list changes.
		String active = LitematicaAccess.activeListName();
		if (!Objects.equals(active, snapshotListName)) {
			snapshotListName = active;
			queueMissing.clear();
			skipped.clear();
		}
		for (LitematicaCompat.MaterialNeed n : LitematicaAccess.missingMaterials()) {
			if (n.total() > 0) {
				queueMissing.put(n.itemId(), n.total());
			}
		}
	}

	/**
	 * Rank candidates for a phase using <b>live</b> chest counts only.
	 * CHESTS: inChests &gt; 0, order ready (inChests ≥ need) then partial.
	 * CRAFT: inChests == 0.
	 */
	private static List<RankedItem> rankPhase(Minecraft client, GatherPhase forPhase) {
		snapshotTotals();
		LocalPlayer player = client != null ? client.player : null;
		String dim = client != null && client.level != null
			? ChestMemoryStorage.dimensionId(client.level) : null;
		Vec3 pos = player != null ? player.position() : null;

		List<RankedItem> out = new ArrayList<>();
		Set<String> seen = new HashSet<>();

		for (LitematicaCompat.MaterialNeed n : LitematicaAccess.missingMaterials()) {
			seen.add(n.itemId());
			int need = remainingNeed(n.itemId(), player);
			if (need <= 0) {
				continue;
			}
			int inChests = countInChestsLive(n.itemId());
			boolean hasChests = inChests > 0;
			if (forPhase == GatherPhase.CHESTS && !hasChests) {
				continue;
			}
			if (forPhase == GatherPhase.CRAFT && hasChests) {
				continue;
			}
			int band = hasChests ? (inChests >= need ? 0 : 1) : 2;
			double dist = nearestLiveDist(n.itemId(), pos, dim, DimensionChoice.ALL);
			out.add(new RankedItem(n.itemId(), need, inChests, band, dist, n.total()));
		}

		for (Map.Entry<String, Integer> e : queueMissing.entrySet()) {
			if (seen.contains(e.getKey())) {
				continue;
			}
			int need = remainingNeed(e.getKey(), player);
			if (need <= 0) {
				continue;
			}
			int inChests = countInChestsLive(e.getKey());
			boolean hasChests = inChests > 0;
			if (forPhase == GatherPhase.CHESTS && !hasChests) {
				continue;
			}
			if (forPhase == GatherPhase.CRAFT && hasChests) {
				continue;
			}
			int band = hasChests ? (inChests >= need ? 0 : 1) : 2;
			double dist = nearestLiveDist(e.getKey(), pos, dim, DimensionChoice.ALL);
			out.add(new RankedItem(e.getKey(), need, inChests, band, dist, e.getValue()));
		}

		// Only `need` is descending. Chaining .reversed() after thenComparingInt would
		// reverse the whole composite instead, putting partial stacks before ready ones
		// and the farthest chest first.
		out.sort(Comparator
			.comparingInt(RankedItem::band)
			.thenComparingDouble((RankedItem r) -> r.dist >= 0 ? r.dist : Double.MAX_VALUE)
			.thenComparing(Comparator.comparingInt(RankedItem::need).reversed())
			.thenComparing(r -> ChestMemoryStorage.itemDisplayName(r.itemId), String.CASE_INSENSITIVE_ORDER));
		return out;
	}

	private static @Nullable String bestIdForPhase(Minecraft client, GatherPhase forPhase, @Nullable String exclude) {
		// What you claimed in the clan wins over the mod's own ranking: taking an item is a
		// promise to bring it, and the mod used to ignore that and walk you to whatever it
		// thought was most needed — so the HUD named an item you had never picked.
		// A pick whose claim is still in flight counts as claimed: the player already chose it.
		if (pendingClaimFocus != null
			&& !pendingClaimFocus.equals(exclude)
			&& !skipped.contains(pendingClaimFocus)
			&& remainingNeed(pendingClaimFocus, client != null ? client.player : null) > 0) {
			return pendingClaimFocus;
		}
		String claimed = firstOwnClaim(client, exclude);
		if (claimed != null) {
			return claimed;
		}
		for (RankedItem r : rankPhase(client, forPhase)) {
			if (exclude != null && exclude.equals(r.itemId)) {
				continue;
			}
			if (skipped.contains(r.itemId)) {
				continue;
			}
			if (com.chestmemory.client.clan.ClanSessionManager.isClaimedByOther(client, r.itemId)) {
				continue;
			}
			return r.itemId;
		}
		return null;
	}

	/**
	 * First material this player has claimed in the clan session that still needs work.
	 * <p>
	 * Deliberately ignores whether any of it sits in a chest: the user asked to stay on a
	 * claimed item even when there is none to be found, so they can decide themselves whether
	 * to go mine it or give the claim up. Auto-switching is what made the HUD confusing.
	 */
	private static @Nullable String firstOwnClaim(Minecraft client, @Nullable String exclude) {
		if (client == null || !com.chestmemory.client.clan.ClanSessionManager.isInSession()) {
			return null;
		}
		var session = com.chestmemory.client.clan.ClanSessionManager.session();
		if (session == null) {
			return null;
		}
		// Click order first: glass claimed before wool means glass is worked first.
		// The session map's order is the hub's, not the player's.
		for (String itemId : com.chestmemory.client.clan.ClanSessionManager.myClaimOrder(client)) {
			if (exclude != null && exclude.equals(itemId)) {
				continue;
			}
			if (skipped.contains(itemId)) {
				continue;
			}
			if (remainingNeed(itemId, client.player) <= 0) {
				continue;
			}
			return itemId;
		}
		// Claims with no recorded order (made before a relog) fall back to the map.
		for (var e : session.materials.entrySet()) {
			String itemId = e.getKey();
			if (exclude != null && exclude.equals(itemId)) {
				continue;
			}
			if (skipped.contains(itemId)) {
				continue;
			}
			if (!com.chestmemory.client.clan.ClanSessionManager.isClaimedByMe(client, itemId)) {
				continue;
			}
			if (remainingNeed(itemId, client.player) <= 0) {
				continue;
			}
			return itemId;
		}
		return null;
	}

	/**
	 * Point the gather at an item the player just claimed.
	 * <p>
	 * Called from the claim callback, once the hub has confirmed it. Calling it before the
	 * round trip completes is useless: the claim is not in the session snapshot yet, so the
	 * ranking wins and the HUD names the wrong item.
	 */
	public static void focusClaimed(Minecraft client, String itemId) {
		if (!active || itemId == null) {
			return;
		}
		pendingClaimFocus = null;
		skipped.remove(itemId);
		focusItem(client, itemId, true);
	}

	/**
	 * Remember an item the player just clicked, before the hub has confirmed the claim.
	 * <p>
	 * {@link #startQueue} runs immediately after the click and resets state, so the intent has
	 * to survive that reset — otherwise the queue is rebuilt around whatever the ranking
	 * prefers and the player's pick is lost.
	 */
	public static void setPendingClaimFocus(@Nullable String itemId) {
		pendingClaimFocus = itemId;
	}

	/** Item the player picked and is waiting on, or null. */
	public static @Nullable String pendingClaimFocus() {
		return pendingClaimFocus;
	}

	/**
	 * Give up the current target after its claim was released, and move to the next thing
	 * worth doing — the player said they no longer want this one.
	 */
	public static void dropCurrentClaimFocus(Minecraft client) {
		pendingClaimFocus = null;
		currentItemId = null;
		currentRoute = List.of();
		highlightPaused = false;
		if (!focusBestInPhase(client, false)) {
			hudLines = List.of();
		}
		refreshHud(client);
	}

	private static boolean focusBestInPhase(Minecraft client, boolean announce) {
		String id = bestIdForPhase(client, phase, null);
		if (id == null) {
			return false;
		}
		focusItem(client, id, announce);
		return true;
	}

	private static void focusItem(Minecraft client, String itemId, boolean announce) {
		if (itemId == null) {
			return;
		}
		// HUD queue = current phase ranking
		List<RankedItem> ranked = rankPhase(client, phase);
		queue.clear();
		queue.add(itemId);
		for (RankedItem r : ranked) {
			if (!r.itemId.equals(itemId) && !skipped.contains(r.itemId)) {
				queue.add(r.itemId);
			}
		}
		queueIndex = Math.max(0, queue.indexOf(itemId));
		currentItemId = itemId;
		queueMissing.putIfAbsent(itemId, Math.max(1, remainingNeed(itemId, client != null ? client.player : null)));
		highlightCurrent(client, announce);
	}

	private static void highlightCurrent(Minecraft client, boolean announce) {
		if (currentItemId == null) {
			currentRoute = List.of();
			return;
		}
		highlightPaused = false;
		routeChestIndex = 0;
		long duration = ModSettings.get().highlightDurationMs();
		ChestHighlighter.highlightItem(currentItemId, duration);
		ChestHighlighter.refreshDuration(duration);

		if (client == null || client.player == null || client.level == null) {
			return;
		}

		// Route from LIVE memory only
		String dimension = ChestMemoryStorage.dimensionId(client.level);
		Vec3 pos = client.player.position();
		List<ContainerRecord> world = liveHighlightableWithItem(currentItemId);
		int need = remainingNeed(currentItemId);
		if (need <= 0) {
			need = queueMissing.getOrDefault(currentItemId, 1);
		}
		currentRoute = ChestRoute.build(world, pos, dimension, currentItemId, Math.max(1, need));
		if (currentRoute.isEmpty() && !world.isEmpty()) {
			currentRoute = ChestRoute.build(world, pos, dimension, currentItemId, Integer.MAX_VALUE / 4);
		}
		routeChestIndex = 0;

		List<net.minecraft.core.BlockPos> ordered = new ArrayList<>();
		for (ChestRoute.Stop s : currentRoute) {
			ordered.add(s.pos());
		}
		ChestHighlighter.setRoute(ordered);
		ChestHighlighter.refreshDuration(duration);

		if (announce && client != null && client.player != null) {
			String name = ChestMemoryStorage.itemDisplayName(currentItemId);
			int inChests = countInChestsLive(currentItemId);
			String phaseLabel = phase == GatherPhase.CHESTS
				? Component.translatable("hud.chestmemory.phase_chests").getString()
				: Component.translatable("hud.chestmemory.phase_craft").getString();
			if (!currentRoute.isEmpty()) {
				ChestRoute.Stop first = currentRoute.getFirst();
				int totalM = (int) Math.max(0, Math.round(ChestRoute.totalLength(currentRoute)));
				chat(client, Component.translatable(
					"message.chestmemory.build_route",
					queueIndex + 1,
					Math.max(queue.size(), 1),
					name,
					need,
					currentRoute.size(),
					totalM,
					first.pos().getX(),
					first.pos().getY(),
					first.pos().getZ()
				));
			} else if (phase == GatherPhase.CRAFT || inChests <= 0) {
				chat(client, Component.translatable(
					"message.chestmemory.build_craft_item",
					queueIndex + 1,
					Math.max(queue.size(), 1),
					name,
					need,
					phaseLabel
				));
			} else {
				chat(client, Component.translatable(
					"message.chestmemory.build_next_no_chest",
					queueIndex + 1,
					Math.max(queue.size(), 1),
					name,
					need
				));
			}
		}
	}

	/** Chat only when setting enabled. */
	private static void chat(Minecraft client, Component message) {
		if (client == null || client.player == null || message == null) {
			return;
		}
		if (!ModSettings.get().gatherChatMessages()) {
			return;
		}
		client.player.sendSystemMessage(message);
	}

	// ── panel list ─────────────────────────────────────────────────────────

	/**
	 * Scheme panel entries. Stock from <b>live</b> memory so filters match gather.
	 */
	public static List<ItemSummary> buildPanelList(
		Minecraft client,
		String query,
		ListScope scope,
		DimensionChoice dimensionFilter,
		double rangeBlocks
	) {
		return buildPanelList(client, query, scope, dimensionFilter, rangeBlocks, filter);
	}

	/**
	 * Same list under an explicit filter. The gather screen always wants ALL, and must not
	 * inherit whatever the Ё panel's cycling filter happens to be set to at the moment.
	 */
	public static List<ItemSummary> buildPanelList(
		Minecraft client,
		String query,
		ListScope scope,
		DimensionChoice dimensionFilter,
		double rangeBlocks,
		BuildFilter useFilter
	) {
		List<LitematicaCompat.MaterialNeed> needs = LitematicaAccess.missingMaterials();
		if (needs.isEmpty()) {
			return List.of();
		}

		String dim = client.level != null ? ChestMemoryStorage.dimensionId(client.level) : null;
		Vec3 pos = client.player != null ? client.player.position() : null;
		// Honour Ё dimension filter (CURRENT / farm / …) — not always ALL worlds
		DimensionChoice dimFilter = dimensionFilter != null ? dimensionFilter : DimensionChoice.ALL;
		// Nearby still only affects distance/range of "nearest", stock uses dimFilter + whole profile in that dim
		ListScope stockScope = scope == ListScope.NEARBY ? ListScope.NEARBY : ListScope.WORLD_TOTAL;

		String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
		List<ItemSummary> out = new ArrayList<>();

		// Pre-aggregate stock with filters (same as main Ё list)
		Map<String, ItemSummary> stockById = new HashMap<>();
		for (ItemSummary s : ChestMemoryStorage.get().listItems(
			"",
			ContainerFilter.ALL,
			dimFilter,
			stockScope,
			dim,
			pos,
			rangeBlocks,
			SortMode.COUNT
		)) {
			stockById.put(s.itemId(), s);
		}

		for (LitematicaCompat.MaterialNeed need : needs) {
			if (!q.isEmpty()) {
				String name = ChestMemoryStorage.itemDisplayName(need.itemId()).toLowerCase(Locale.ROOT);
				if (!need.itemId().toLowerCase(Locale.ROOT).contains(q) && !name.contains(q)) {
					continue;
				}
			}

			ItemSummary stock = stockById.get(need.itemId());
			int inChests = stock != null ? stock.totalCount() : 0;
			int containers = stock != null ? stock.containerCount() : 0;
			double dist = stock != null && stock.hasDistance()
				? stock.nearestDistance()
				: nearestLiveDist(need.itemId(), pos, dim, dimFilter);
			int inPlayer = countInPlayer(client.player, need.itemId());
			// Still need after inv + staging warehouse
			int missing = remainingNeed(need.itemId(), client.player);

			ItemSummary summary = new ItemSummary(
				need.itemId(),
				inChests,
				containers,
				dist,
				missing,
				inPlayer,
				need.total()
			);

			if (!useFilter.matches(summary)) {
				continue;
			}
			out.add(summary);
		}

		// As in rankPhase: reverse only the `neededForBuild` key. Reversing the whole
		// chain would list DONE rows before READY ones and sort by farthest first.
		out.sort(Comparator
			.comparingInt(BuildFilter::gatherPriority)
			.thenComparingDouble((ItemSummary s) -> s.hasDistance() ? s.nearestDistance() : Double.MAX_VALUE)
			.thenComparing(Comparator.comparingInt(ItemSummary::neededForBuild).reversed())
			.thenComparing(s -> ChestMemoryStorage.itemDisplayName(s.itemId()), String.CASE_INSENSITIVE_ORDER));
		return out;
	}

	// ── HUD ────────────────────────────────────────────────────────────────

	private static void refreshHud(Minecraft client) {
		listName = LitematicaAccess.activeListName();
		List<LitematicaCompat.MaterialNeed> needs = LitematicaAccess.missingMaterials();

		// Live stock for HUD
		Map<String, ItemSummary> byId = new HashMap<>();
		String dim = client.level != null ? ChestMemoryStorage.dimensionId(client.level) : null;
		Vec3 pos = client.player != null ? client.player.position() : null;
		for (LitematicaCompat.MaterialNeed n : needs) {
			int inChests = countInChestsLive(n.itemId());
			if (inChests > 0 || remainingNeed(n.itemId(), client.player) > 0) {
				byId.put(n.itemId(), new ItemSummary(
					n.itemId(),
					inChests,
					0,
					nearestLiveDist(n.itemId(), pos, dim, DimensionChoice.ALL)
				));
			}
		}

		List<HudLine> lines = new ArrayList<>();
		if (currentItemId != null) {
			addHudLine(lines, currentItemId, needs, byId, true, client);
		}

		// Rest of current phase queue
		for (String id : queue) {
			if (lines.size() >= 5) {
				break;
			}
			if (id == null || id.equals(currentItemId)) {
				continue;
			}
			if (remainingNeed(id, client.player) <= 0) {
				continue;
			}
			addHudLine(lines, id, needs, byId, false, client);
		}

		// Fill with other phase-matching items
		for (RankedItem r : rankPhase(client, phase)) {
			if (lines.size() >= 5) {
				break;
			}
			boolean already = false;
			for (HudLine l : lines) {
				if (l.itemId().equals(r.itemId)) {
					already = true;
					break;
				}
			}
			if (!already) {
				addHudLine(lines, r.itemId, needs, byId, false, client);
			}
		}

		hudLines = List.copyOf(lines);
	}

	private static void addHudLine(
		List<HudLine> lines,
		String itemId,
		List<LitematicaCompat.MaterialNeed> needs,
		Map<String, ItemSummary> byId,
		boolean current,
		Minecraft client
	) {
		int inPlayer = countInPlayer(client != null ? client.player : null, itemId);
		int inStaging = countInStaging(itemId);
		int clanDel = com.chestmemory.client.clan.ClanSessionManager.clanDelivered(itemId);
		int total = 0;
		for (LitematicaCompat.MaterialNeed n : needs) {
			if (n.itemId().equals(itemId)) {
				total = n.total();
				break;
			}
		}
		if (total <= 0) {
			total = queueMissing.getOrDefault(itemId, 0);
		}
		if (total <= 0) {
			total = com.chestmemory.client.clan.ClanSessionManager.clanNeed(itemId);
		}
		int missing = Math.max(0, total - inPlayer - Math.max(inStaging, clanDel));
		if (missing <= 0 && !current) {
			return;
		}
		int inChests = countInChestsLive(itemId);
		ItemSummary st = byId.get(itemId);
		double dist = st != null && st.hasDistance() ? st.nearestDistance() : -1;
		lines.add(new HudLine(
			itemId,
			ChestMemoryStorage.itemDisplayName(itemId),
			missing,
			inChests,
			inPlayer,
			dist,
			current,
			inStaging
		));
	}

	private record RankedItem(String itemId, int need, int inChests, int band, double dist, int total) {
	}

	public record HudLine(
		String itemId,
		String displayName,
		int missing,
		int inChests,
		int inPlayer,
		double nearestDist,
		boolean current,
		int inStaging
	) {
		public boolean availableSomewhere() {
			return inChests > 0 || inPlayer > 0 || inStaging > 0;
		}
	}
}
