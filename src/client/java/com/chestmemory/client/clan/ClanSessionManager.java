package com.chestmemory.client.clan;

import com.chestmemory.ChestMemoryMod;
import com.chestmemory.client.data.ChestMemoryStorage;
import com.chestmemory.client.data.ModSettings;
import com.chestmemory.client.litematica.LitematicaAccess;
import com.chestmemory.client.litematica.LitematicaCompat;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Client-side clan gather session: create / join by code / claim / deliver / poll.
 */
public final class ClanSessionManager {
	/**
	 * Interactive lane: everything a click waits on (create, join, claim, leave, host
	 * tools). Single-threaded on purpose — two actions from this player can never
	 * reorder in flight.
	 */
	private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "chestmemory-clan-io");
		t.setDaemon(true);
		return t;
	});

	/**
	 * Background lane: the periodic poll, warehouse pushes and the health probe.
	 * <p>
	 * When everything shared one thread, a click serialized behind whatever poll was
	 * already in flight — a slow-but-alive hub (requests time out at 10–12s) made every
	 * button feel dead for seconds. With two lanes a poll can never delay a click.
	 * Ordering stays safe: interactive actions still serialize on {@link #IO} plus the
	 * {@link #busy} gate, and a background response that lost the race is dropped by
	 * the revision check in {@link #adoptSession} and the {@link #isFollowing} guard,
	 * so a stale poll cannot overwrite fresher interactive state.
	 */
	private static final ExecutorService SYNC = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "chestmemory-clan-sync");
		t.setDaemon(true);
		return t;
	});

	/** Written on the client thread, read from render/IO paths — must be volatile. */
	private static volatile @Nullable ClanSession session;
	private static @Nullable String lastError;
	private static long lastPollMillis;
	private static final AtomicBoolean busy = new AtomicBoolean(false);
	private static int tickCounter;
	/**
	 * Code of a gather interrupted by a world change, waiting to be rejoined.
	 * <p>
	 * A multiworld portal is a full reconnect, so the session has to be picked up again rather
	 * than abandoned.
	 */
	private static @Nullable String pausedCode;
	/** Throttle for resume attempts; the tick fires 20×/s. */
	private static long lastResumeAttemptMillis;
	/**
	 * Gather code being switched to, or null when idle.
	 * <p>
	 * Switching used to give no feedback at all: the click tore the old gather down straight
	 * away and the panel sat on stale rows until the hub answered, so it read as a freeze and
	 * then a jump. The UI shows this code as "switching…" and keeps the current gather intact
	 * until the new one actually arrives.
	 */
	private static volatile @Nullable String switchingTo;

	/** Code of the gather currently being switched to, or null when not switching. */
	public static @Nullable String switchingTo() {
		return switchingTo;
	}

	/** True while any hub request is in flight, so the UI can disable what must not be clicked. */
	public static boolean isBusy() {
		return busy.get();
	}

	/**
	 * Epoch millis until which the hub asked us to stay quiet (429 + Retry-After).
	 * <p>
	 * The poll fires every ~3s; without honouring the header a rate-limited client just
	 * kept hammering and looked broken. While this is in the future the background
	 * traffic pauses and interactive clicks are refused with a "slow down" message
	 * instead of a raw failure.
	 */
	private static volatile long rateLimitedUntilMillis;

	/** Extend the backoff from a 429 response. Header capped so a bad hub cannot mute us for good. */
	private static void noteRateLimit(ClanHubClient.Result<?> res) {
		int sec = res.retryAfterSeconds > 0 ? Math.min(res.retryAfterSeconds, 120) : 5;
		long until = System.currentTimeMillis() + sec * 1000L;
		if (until > rateLimitedUntilMillis) {
			rateLimitedUntilMillis = until;
		}
	}

	/** Seconds left of a hub-imposed backoff, or 0 when requests may flow. */
	public static int rateLimitRemainingSeconds() {
		long left = rateLimitedUntilMillis - System.currentTimeMillis();
		return left <= 0 ? 0 : (int) ((left + 999) / 1000);
	}

	/**
	 * Refuse an interactive action while the hub's backoff runs, saying so once.
	 * Only clicks get the message — the background poll backs off silently, or the
	 * chat would repeat it every three seconds.
	 */
	private static boolean refuseWhileRateLimited(Minecraft mc, @Nullable Runnable onDone) {
		int left = rateLimitRemainingSeconds();
		if (left <= 0) {
			return false;
		}
		lastError = "rate limited";
		chat(mc, Component.translatable("message.chestmemory.clan_err_slow_down", left));
		if (onDone != null) {
			onDone.run();
		}
		return true;
	}

	/** Result of the last hub reachability check. */
	public enum HubState {
		/** Never checked, or a check is running. */
		UNKNOWN,
		/** The hub answered. */
		ONLINE,
		/** The hub could not be reached, or refused the build's token. */
		OFFLINE
	}

	private static volatile HubState hubState = HubState.UNKNOWN;
	private static volatile @Nullable String hubError;
	private static long lastHealthMillis;
	private static final AtomicBoolean healthBusy = new AtomicBoolean(false);

	public static HubState hubState() {
		return hubState;
	}

	/** Drop the 15s throttle, so a manual retry actually reaches the hub. */
	public static void forceHubRecheck() {
		lastHealthMillis = 0L;
		hubState = HubState.UNKNOWN;
	}

	/** Why the hub is unreachable, when it is. */
	public static @Nullable String hubError() {
		return hubError;
	}

	/**
	 * Check that the hub answers, at most once every 15s.
	 * <p>
	 * The clan screen used to claim "hub: built in" whether or not anything was actually
	 * there, so a dead hub looked identical to a working one and the first sign of trouble
	 * was a failed create. This runs off the health endpoint, which needs no session.
	 * <p>
	 * Deliberately on its own flag rather than {@code busy}: a status check must never block
	 * a real request, nor be blocked by one.
	 */
	public static void checkHubAsync(Minecraft mc, @Nullable Runnable onDone) {
		if (!client().isConfigured()) {
			hubState = HubState.OFFLINE;
			hubError = "no_hub";
			if (onDone != null) {
				onDone.run();
			}
			return;
		}
		long now = System.currentTimeMillis();
		if (hubState != HubState.UNKNOWN && now - lastHealthMillis < 15_000L) {
			if (onDone != null) {
				onDone.run();
			}
			return;
		}
		if (!healthBusy.compareAndSet(false, true)) {
			if (onDone != null) {
				onDone.run();
			}
			return;
		}
		lastHealthMillis = now;
		SYNC.execute(() -> {
			var res = client().health();
			mc.execute(() -> {
				healthBusy.set(false);
				// A 429 is the hub speaking, not the hub missing: keep the lamp green
				// and note the backoff instead of reporting an outage.
				boolean limited = res.isRateLimited();
				if (limited) {
					noteRateLimit(res);
				}
				hubState = res.ok || limited ? HubState.ONLINE : HubState.OFFLINE;
				hubError = res.ok || limited ? null : res.error;
				if (onDone != null) {
					onDone.run();
				}
			});
		});
	}

	private ClanSessionManager() {
	}

	public static boolean isInSession() {
		return session != null && session.code != null && !session.code.isBlank();
	}

	public static @Nullable ClanSession session() {
		return session;
	}

	public static @Nullable String lastError() {
		return lastError;
	}

	public static @Nullable String code() {
		return session != null ? session.code : null;
	}

	public static boolean isConfigured() {
		return client().isConfigured();
	}

	private static ClanHubClient client() {
		ModSettings s = ModSettings.get();
		// A build can ship the clan's hub, so members only ever type a session code.
		// An explicit setting still wins, for anyone pointing at a different hub.
		return ClanHubClient.of(
			ClanDefaults.effectiveUrl(s.clanHubUrl()),
			ClanDefaults.effectiveToken(s.clanToken())
		);
	}

	/**
	 * The hub refused because it does not know who we are — as opposed to knowing us
	 * and denying the action ("only host"). Two spellings: 401 {@code auth required}
	 * while the hub enforces verification for everything, and the unconditional
	 * 403 {@code host actions require verified identity} for host tools.
	 */
	private static boolean isIdentityRefusal(ClanHubClient.Result<?> res) {
		if (res.status == 401 && res.error != null && res.error.contains("auth")) {
			return true;
		}
		return res.status == 403 && res.error != null && res.error.contains("verified identity");
	}

	/**
	 * Run a session mutation with a proven identity, retrying once after a fresh
	 * handshake if the hub turns us away.
	 * <p>
	 * A hub restart invalidates every session token, and the poll (a GET) never sees a
	 * 401 to heal it — so the first click after a restart used to fail with a raw
	 * "auth required" and only a later create/join fixed things. One forced re-auth and
	 * a single retry makes that hiccup invisible; a client that cannot verify at all
	 * (offline launcher) fails the handshake fast and the caller reports the refusal
	 * for what it is. Runs blocking network I/O — executor lanes only.
	 */
	private static ClanHubClient.Result<ClanSession> authedRequest(
		Minecraft mc,
		ClanHubClient c,
		java.util.function.Function<ClanHubClient, ClanHubClient.Result<ClanSession>> call
	) {
		ClanAuth.ensureAuthenticated(c, mc);
		ClanHubClient.Result<ClanSession> res = call.apply(c);
		if (isIdentityRefusal(res)) {
			ClanAuth.clear();
			if (ClanAuth.authenticate(c, mc)) {
				res = call.apply(c);
			}
		}
		return res;
	}

	/**
	 * Stable last-resort identity. A fresh random UUID per call meant a client without a
	 * player/user (title screen edge cases) changed identity between requests — its own
	 * claims read as someone else's and the host check never matched.
	 */
	private static final String FALLBACK_UUID = UUID.randomUUID().toString();

	/**
	 * The uuid string, cached against the UUID it was built from.
	 * <p>
	 * {@code UUID.toString()} allocates every call, and the grid asks
	 * {@code isClaimedByMe}/{@code isClaimedByOther} per visible slot per frame — in a
	 * clan session that was ~200 fresh strings a frame for an identity that never
	 * changes mid-session. Keyed on the UUID value itself, so logging into another
	 * account (different UUID) recomputes and a dimension change (same UUID, new
	 * player instance) keeps the hit.
	 */
	private record CachedUuid(UUID id, String text) {
	}

	private static volatile @Nullable CachedUuid cachedUuid;

	public static String localUuid(Minecraft mc) {
		UUID id = mc.player != null ? mc.player.getUUID() : null;
		if (id == null && mc.getUser() != null) {
			id = mc.getUser().getProfileId();
		}
		if (id == null) {
			return FALLBACK_UUID;
		}
		CachedUuid c = cachedUuid;
		if (c == null || !c.id.equals(id)) {
			c = new CachedUuid(id, id.toString());
			cachedUuid = c;
		}
		return c.text;
	}

	public static String localName(Minecraft mc) {
		if (mc.player != null) {
			return mc.player.getGameProfile().name();
		}
		if (mc.getUser() != null) {
			return mc.getUser().getName();
		}
		return "Player";
	}

	/** Create session from current Litematica material list. */
	public static void createAsync(Minecraft mc, @Nullable Runnable onDone) {
		if (!client().isConfigured()) {
			fail("no_hub", mc);
			if (onDone != null) {
				onDone.run();
			}
			return;
		}
		if (refuseWhileRateLimited(mc, onDone)) {
			return;
		}
		if (!LitematicaAccess.hasActiveMaterialList()) {
			fail("no_list", mc);
			if (onDone != null) {
				onDone.run();
			}
			return;
		}
		if (!busy.compareAndSet(false, true)) {
			if (onDone != null) {
				onDone.run();
			}
			return;
		}
		Map<String, Integer> needs = new HashMap<>();
		for (LitematicaCompat.MaterialNeed n : LitematicaAccess.missingMaterials()) {
			if (n.total() > 0) {
				needs.merge(n.itemId(), n.total(), Integer::sum);
			}
		}
		if (needs.isEmpty()) {
			busy.set(false);
			fail("empty_list", mc);
			if (onDone != null) {
				onDone.run();
			}
			return;
		}
		String name = LitematicaAccess.activeListName();
		if (name == null || name.isBlank()) {
			name = "Build";
		}
		JsonObject body = new JsonObject();
		body.addProperty("name", name);
		body.addProperty("schemaName", name);
		body.addProperty("hostName", localName(mc));
		body.addProperty("hostUuid", localUuid(mc));
		body.add("materials", ClanHubClient.materialsJson(needs));

		IO.execute(() -> {
			try {
				// Prove who we are before acting; the hub requires it by default.
				var res = authedRequest(mc, client(), c -> c.create(body));
				mc.execute(() -> {
					busy.set(false);
					if (res.ok && res.value != null) {
						adoptSession(res.value);
						lastError = null;
						lastPollMillis = System.currentTimeMillis();
						// A new gather starts with no warehouse. Uploading the local marks here
						// handed the farm's drop-off chest to the house build — every schematic
						// ended up sharing one warehouse. The host marks a chest for this build.
						com.chestmemory.client.data.StagingPickMode.stopQuiet();
						ChestMemoryStorage.get().clearStaging();
						ClanRoster.remember(session.code, session.schemaName, session.totalDelivered(), session.totalNeed(), session.hostName);
						ModSettings.get().setClanActiveCode(session.code);
						chat(mc, Component.translatable("message.chestmemory.clan_created", session.code));
						// Also paste-friendly line
						chat(mc, Component.translatable("message.chestmemory.clan_code_line", session.code));
					} else {
						failResult(mc, res, "create failed");
					}
					if (onDone != null) {
						onDone.run();
					}
				});
			} catch (Exception e) {
				mc.execute(() -> {
					busy.set(false);
					failRaw(e.getMessage(), mc);
					if (onDone != null) {
						onDone.run();
					}
				});
			}
		});
	}

	public static void joinAsync(Minecraft mc, String rawCode, @Nullable Runnable onDone) {
		if (!client().isConfigured()) {
			fail("no_hub", mc);
			if (onDone != null) {
				onDone.run();
			}
			return;
		}
		String code = ClanCodes.normalize(rawCode);
		if (!ClanCodes.isValid(code)) {
			fail("bad_code", mc);
			if (onDone != null) {
				onDone.run();
			}
			return;
		}
		if (refuseWhileRateLimited(mc, onDone)) {
			return;
		}
		if (!busy.compareAndSet(false, true)) {
			// Another request is in flight. Returning without calling onDone left the screen
			// stuck on "working…" for good, because nothing ever refreshed it again.
			if (onDone != null) {
				onDone.run();
			}
			return;
		}
		JsonObject body = new JsonObject();
		body.addProperty("name", localName(mc));
		body.addProperty("uuid", localUuid(mc));
		IO.execute(() -> {
			try {
				var res = authedRequest(mc, client(), c -> c.join(code, body));
				mc.execute(() -> {
					busy.set(false);
					if (res.ok && res.value != null) {
						ClanSession previous = session;
						boolean differentGather = previous != null
							&& !previous.code.equalsIgnoreCase(res.value.code);
						adoptSession(res.value);
						lastError = null;
						lastPollMillis = System.currentTimeMillis();
						if (differentGather) {
							// The local queue belongs to the schematic we are leaving. Keeping it
							// showed one build's materials under another build's session — old
							// items still looked claimed, and clicking a new one made the hub
							// answer "unknown item".
							//
							// Done here rather than before the request: tearing it down up front
							// left a failed switch with no gather at all.
							com.chestmemory.client.litematica.BuildGatherSession.clear();
						}
						// Adopt this gather's warehouse; do not push our own marks into it. A
						// member joining used to merge whatever they had marked locally into the
						// session, which is how one chest leaked across every schematic.
						com.chestmemory.client.data.StagingPickMode.stopQuiet();
						ChestMemoryStorage.get().clearStaging();
						applyClanStagingKeys(session);
						ClanRoster.remember(session.code, session.schemaName, session.totalDelivered(), session.totalNeed(), session.hostName);
						ModSettings.get().setClanActiveCode(session.code);
						chat(mc, Component.translatable("message.chestmemory.clan_joined", session.code));
						if (session.stagingKeys != null && !session.stagingKeys.isEmpty()) {
							chat(mc, Component.translatable(
								"message.chestmemory.clan_warehouse",
								session.stagingKeys.size()
							));
						}
					} else {
						failResult(mc, res, "join failed");
					}
					if (onDone != null) {
						onDone.run();
					}
				});
			} catch (Exception e) {
				mc.execute(() -> {
					busy.set(false);
					failRaw(e.getMessage(), mc);
					if (onDone != null) {
						onDone.run();
					}
				});
			}
		});
	}

	/**
	 * Follow a different gather without leaving it.
	 * <p>
	 * A clan runs several builds at once, and a member moves between them: help with the farm
	 * for an hour, then switch to the house. This is not the same as leaving and re-joining —
	 * your claims in the other gather stay yours, and the hub keeps its progress.
	 * <p>
	 * Exactly one gather is followed at a time, and switching has to hand over the things that
	 * only make sense for one of them:
	 * <ul>
	 *   <li>the warehouse marks, or the farm's drop-off chest would glow during the house
	 *       build;</li>
	 *   <li>the activity feed, which describes one gather;</li>
	 *   <li>the gather target, since the new build needs different materials.</li>
	 * </ul>
	 * Polling also follows the active gather only: it runs every 3s, so following every known
	 * gather would multiply request volume, and the hub counts a session poll in its tightest
	 * rate-limit bucket.
	 */
	public static void switchToAsync(Minecraft mc, String rawCode, @Nullable Runnable onDone) {
		String code = ClanCodes.normalize(rawCode);
		if (!ClanCodes.isValid(code)) {
			fail("bad_code", mc);
			if (onDone != null) {
				onDone.run();
			}
			return;
		}
		if (session != null && code.equalsIgnoreCase(session.code)) {
			// Already following it — nothing to do, and re-joining would be a pointless request.
			if (onDone != null) {
				onDone.run();
			}
			return;
		}
		if (busy.get()) {
			// A switch already in flight. Starting a second one tore down the first one's state
			// mid-request, which is what made rapid clicking feel broken.
			if (onDone != null) {
				onDone.run();
			}
			return;
		}
		// Teardown happens only once the new gather has actually arrived. Doing it up front
		// meant a failed or slow switch left the player with no gather and a blank panel — the
		// old warehouse already unmarked, the old queue already gone, and nothing to show yet.
		switchingTo = code;
		if (onDone != null) {
			onDone.run();
		}
		// join is idempotent on the hub: a member who is already in the session just gets the
		// current snapshot back, so this doubles as "switch to".
		joinAsync(mc, code, () -> {
			switchingTo = null;
			if (onDone != null) {
				onDone.run();
			}
		});
	}

	public static void leaveAsync(Minecraft mc, @Nullable Runnable onDone) {
		if (session == null) {
			if (onDone != null) {
				onDone.run();
			}
			return;
		}
		String code = session.code;
		boolean host = isHost(mc);
		if (!busy.compareAndSet(false, true)) {
			if (onDone != null) {
				onDone.run();
			}
			return;
		}
		JsonObject body = new JsonObject();
		body.addProperty("uuid", localUuid(mc));
		body.addProperty("name", localName(mc));
		IO.execute(() -> {
			boolean refused = false;
			try {
				ClanHubClient c = client();
				if (host) {
					// Closing is a host action: the hub demands a verified identity for it
					// unconditionally, so keep the answer — a refusal must not be reported
					// as "closed" when the session is in fact still running for everyone.
					var res = authedRequest(mc, c, cl -> cl.close(code, body));
					refused = !res.ok && isIdentityRefusal(res);
				} else {
					ClanAuth.ensureAuthenticated(c, mc);
					c.leave(code, body);
				}
			} catch (Exception e) {
				ChestMemoryMod.LOGGER.debug("Clan leave: {}", e.toString());
			}
			boolean closeRefused = refused;
			mc.execute(() -> {
				busy.set(false);
				// Leaving drops this gather from the list; the others stay so the player can
				// switch back to them.
				ClanRoster.forget(code);
				if (code.equalsIgnoreCase(ModSettings.get().clanActiveCode())) {
					ModSettings.get().setClanActiveCode("");
				}
				session = null;
				lastError = null;
				ClanEventLog.clear();
				clearClaimOrder();
				// Warehouse marks came from this gather — shared ones from the clan, local ones
				// made for it. Leaving means they are no longer drop-off points, so stop
				// glowing over them. Quiet: session is already null, and pushing here would
				// only be an upload into a gather we just left.
				com.chestmemory.client.data.StagingPickMode.stopQuiet();
				ChestMemoryStorage.get().clearStaging();
				chat(mc, Component.translatable(
					host && !closeRefused ? "message.chestmemory.clan_closed" : "message.chestmemory.clan_left"
				));
				if (host && closeRefused) {
					// The hub kept the session alive: closing needs a Mojang-verified
					// sign-in this client could not produce. Locally we still left.
					fail("need_verified", mc);
				}
				if (onDone != null) {
					onDone.run();
				}
			});
		});
	}

	/** Items this player claimed, in click order — the order the gather works them in. */
	private static final java.util.List<String> myClaimOrder = new java.util.ArrayList<>();

	/**
	 * The claim order as clicked, pruned to claims the session still shows as this
	 * player's. Glass claimed before wool means glass is gathered first — the ranking
	 * has no say between a player's own claims.
	 */
	public static java.util.List<String> myClaimOrder(Minecraft mc) {
		ClanSession s = session;
		if (s == null) {
			return java.util.List.of();
		}
		String me = localUuid(mc);
		synchronized (myClaimOrder) {
			myClaimOrder.removeIf(id -> {
				ClanSession.ClanMaterial m = s.material(id);
				return m == null || m.claimedBy == null || !me.equalsIgnoreCase(m.claimedBy);
			});
			return java.util.List.copyOf(myClaimOrder);
		}
	}

	private static void rememberClaimOrder(String itemId, boolean unclaim) {
		synchronized (myClaimOrder) {
			myClaimOrder.remove(itemId);
			if (!unclaim) {
				myClaimOrder.add(itemId);
			}
		}
	}

	private static void clearClaimOrder() {
		synchronized (myClaimOrder) {
			myClaimOrder.clear();
		}
	}

	public static boolean isHost(Minecraft mc) {
		if (session == null) {
			return false;
		}
		return localUuid(mc).equalsIgnoreCase(session.hostUuid);
	}

	/** Rename the gather on the hub (host only); members pick it up on the next poll. */
	public static void renameAsync(Minecraft mc, String newName, @Nullable Runnable onDone) {
		if (session == null || newName == null || newName.isBlank()) {
			if (onDone != null) {
				onDone.run();
			}
			return;
		}
		if (refuseWhileRateLimited(mc, onDone)) {
			return;
		}
		if (!busy.compareAndSet(false, true)) {
			if (onDone != null) {
				onDone.run();
			}
			return;
		}
		JsonObject body = new JsonObject();
		body.addProperty("uuid", localUuid(mc));
		body.addProperty("name", newName.trim());
		String code = session.code;
		IO.execute(() -> {
			try {
				var res = authedRequest(mc, client(), c -> c.update(code, body));
				mc.execute(() -> {
					busy.set(false);
					if (res.ok && res.value != null) {
						adoptSession(res.value);
						lastError = null;
						ClanRoster.remember(
							code, res.value.schemaName,
							res.value.totalDelivered(), res.value.totalNeed()
						);
						chat(mc, Component.translatable(
							"message.chestmemory.clan_renamed", res.value.schemaName
						));
					} else {
						failResult(mc, res, "rename failed");
					}
					if (onDone != null) {
						onDone.run();
					}
				});
			} catch (Exception e) {
				mc.execute(() -> {
					busy.set(false);
					failRaw(e.getMessage(), mc);
					if (onDone != null) {
						onDone.run();
					}
				});
			}
		});
	}

	/** Remove a member from the gather (host only). Their claims are released with them. */
	public static void kickAsync(Minecraft mc, String targetUuid, String targetName, @Nullable Runnable onDone) {
		if (session == null || targetUuid == null || targetUuid.isBlank()) {
			if (onDone != null) {
				onDone.run();
			}
			return;
		}
		if (refuseWhileRateLimited(mc, onDone)) {
			return;
		}
		if (!busy.compareAndSet(false, true)) {
			if (onDone != null) {
				onDone.run();
			}
			return;
		}
		JsonObject body = new JsonObject();
		body.addProperty("uuid", localUuid(mc));
		body.addProperty("name", localName(mc));
		body.addProperty("target", targetUuid);
		String code = session.code;
		IO.execute(() -> {
			try {
				var res = authedRequest(mc, client(), c -> c.kick(code, body));
				mc.execute(() -> {
					busy.set(false);
					if (res.ok && res.value != null) {
						adoptSession(res.value);
						lastError = null;
						chat(mc, Component.translatable(
							"message.chestmemory.clan_kicked",
							targetName == null || targetName.isBlank() ? "?" : targetName
						));
					} else {
						failResult(mc, res, "kick failed");
					}
					if (onDone != null) {
						onDone.run();
					}
				});
			} catch (Exception e) {
				mc.execute(() -> {
					busy.set(false);
					failRaw(e.getMessage(), mc);
					if (onDone != null) {
						onDone.run();
					}
				});
			}
		});
	}

	/** Clear every claim on the hub (host only) — the reset for a stalled evening. */
	public static void releaseClaimsAsync(Minecraft mc, @Nullable Runnable onDone) {
		if (session == null) {
			if (onDone != null) {
				onDone.run();
			}
			return;
		}
		if (refuseWhileRateLimited(mc, onDone)) {
			return;
		}
		if (!busy.compareAndSet(false, true)) {
			if (onDone != null) {
				onDone.run();
			}
			return;
		}
		JsonObject body = new JsonObject();
		body.addProperty("uuid", localUuid(mc));
		body.addProperty("name", localName(mc));
		String code = session.code;
		IO.execute(() -> {
			try {
				var res = authedRequest(mc, client(), c -> c.releaseClaims(code, body));
				mc.execute(() -> {
					busy.set(false);
					if (res.ok && res.value != null) {
						adoptSession(res.value);
						lastError = null;
						chat(mc, Component.translatable("message.chestmemory.clan_claims_released"));
					} else {
						failResult(mc, res, "release failed");
					}
					if (onDone != null) {
						onDone.run();
					}
				});
			} catch (Exception e) {
				mc.execute(() -> {
					busy.set(false);
					failRaw(e.getMessage(), mc);
					if (onDone != null) {
						onDone.run();
					}
				});
			}
		});
	}

	/** Toggle claim on item for local player. */
	public static void claimToggleAsync(Minecraft mc, String itemId, @Nullable Runnable onDone) {
		if (session == null || itemId == null) {
			return;
		}
		// Refuse locally rather than letting the hub answer "unknown item": the panel can show
		// a schematic that is not the active gather's.
		if (!isInActiveGather(itemId)) {
			chat(mc, Component.translatable(
				"message.chestmemory.clan_not_in_gather",
				ChestMemoryStorage.itemDisplayName(itemId)
			));
			if (onDone != null) {
				onDone.run();
			}
			return;
		}
		if (refuseWhileRateLimited(mc, onDone)) {
			return;
		}
		ClanSession.ClanMaterial m = session.material(itemId);
		boolean unclaim = m != null && localUuid(mc).equalsIgnoreCase(m.claimedBy);
		if (!busy.compareAndSet(false, true)) {
			if (onDone != null) {
				onDone.run();
			}
			return;
		}
		JsonObject body = new JsonObject();
		body.addProperty("itemId", itemId);
		body.addProperty("uuid", localUuid(mc));
		body.addProperty("name", localName(mc));
		body.addProperty("unclaim", unclaim);
		String code = session.code;
		IO.execute(() -> {
			try {
				var res = authedRequest(mc, client(), c -> c.claim(code, body));
				mc.execute(() -> {
					busy.set(false);
					if (res.ok && res.value != null) {
						ClanSession prev = session;
						adoptSession(res.value);
						lastError = null;
						rememberClaimOrder(itemId, unclaim);
						// Always confirm locally with clear who/what
						String itemName = ChestMemoryStorage.itemDisplayName(itemId);
						if (unclaim) {
							chat(mc, Component.translatable("message.chestmemory.clan_unclaimed_self", itemName));
						} else {
							chat(mc, Component.translatable(
								"message.chestmemory.clan_claimed_self",
								itemName
							));
						}
						// Also announce other claim deltas from full snapshot (if any)
						announceClaimDiffs(mc, prev, session, true);
					} else {
						failResult(mc, res, "claim failed");
					}
					if (onDone != null) {
						onDone.run();
					}
				});
			} catch (Exception e) {
				mc.execute(() -> {
					busy.set(false);
					failRaw(e.getMessage(), mc);
					if (onDone != null) {
						onDone.run();
					}
				});
			}
		});
	}

	/** Report delivered amount for one item (merge max on hub). */
	public static void reportDeliveredAsync(Minecraft mc, String itemId, int amount) {
		if (session == null || itemId == null || amount <= 0) {
			return;
		}
		if (rateLimitRemainingSeconds() > 0) {
			// Totals are absolute and re-pushed every ~10s: skipping during a hub
			// backoff loses nothing, the next push heals the count.
			return;
		}
		JsonObject body = new JsonObject();
		body.addProperty("itemId", itemId);
		body.addProperty("amount", amount);
		body.addProperty("uuid", localUuid(mc));
		body.addProperty("name", localName(mc));
		String code = session.code;
		int before = clanDelivered(itemId);
		SYNC.execute(() -> {
			try {
				var res = authedRequest(mc, client(), c -> c.deliver(code, body));
				if (res.isRateLimited()) {
					noteRateLimit(res);
				}
				if (res.ok && res.value != null) {
					mc.execute(() -> {
						if (!isFollowing(code)) {
							// Switched or left while in flight — a snapshot of another
							// gather must not be adopted.
							return;
						}
						adoptSession(res.value);
						lastError = null;
						// Deliveries were the one thing the feed never mentioned: it logged
						// claims and arrivals, so a gather where everyone was actually working
						// looked idle. Only real progress is logged — the periodic push
						// re-reports the same warehouse totals and would otherwise spam it.
						ClanSession.ClanMaterial m = session.material(itemId);
						int now = m == null ? 0 : Math.max(0, m.delivered);
						if (now > before) {
							int added = now - before;
							ClanEventLog.add(
								ClanEventLog.Kind.DELIVER,
								Component.translatable(
									"message.chestmemory.clan_feed_delivered",
									localName(mc),
									added,
									ChestMemoryStorage.itemDisplayName(itemId)
								)
							);
							boolean finished = m != null && m.delivered >= m.need && m.need > 0;
							if (finished) {
								chat(mc, Component.translatable(
									"message.chestmemory.clan_item_complete",
									ChestMemoryStorage.itemDisplayName(itemId)
								));
							}
						}
					});
				}
			} catch (Exception e) {
				ChestMemoryMod.LOGGER.debug("Clan deliver: {}", e.toString());
			}
		});
	}

	/**
	 * Push local staging warehouse counts into session (max merge).
	 * Call after opening staging chests / periodically while in session.
	 */
	/**
	 * Report one item's warehouse total immediately.
	 * <p>
	 * The periodic push runs every ~10s, which is fine for background sync and far too slow
	 * when the gather has just switched away from a finished item: the player saw a new target
	 * before the hub had been told anything was delivered.
	 */
	public static void reportStagedNow(Minecraft mc, @Nullable String itemId) {
		if (session == null || itemId == null) {
			return;
		}
		int staged = ChestMemoryStorage.get().countInStaging(itemId);
		ClanSession.ClanMaterial m = session.material(itemId);
		if (staged > 0 && m != null && staged > m.delivered) {
			reportDeliveredAsync(mc, itemId, staged);
		}
	}

	public static void pushStagingProgress(Minecraft mc) {
		ClanSession cur = session;
		if (cur == null) {
			return;
		}
		if (rateLimitRemainingSeconds() > 0) {
			// Background sync respects the hub's 429 backoff; the next tick retries.
			return;
		}
		// One request for the whole warehouse. The per-item loop used to fire an HTTP POST
		// for every material with progress — a 30-material gather pushed 30 requests every
		// ten seconds into the hub's rate-limit bucket.
		JsonObject amounts = new JsonObject();
		ClanSession prev = cur;
		for (String itemId : cur.materials.keySet()) {
			int staging = ChestMemoryStorage.get().countInStaging(itemId);
			if (staging <= 0) {
				continue;
			}
			ClanSession.ClanMaterial m = cur.material(itemId);
			if (m != null && staging > m.delivered) {
				amounts.addProperty(itemId, staging);
			}
		}
		if (amounts.isEmpty()) {
			return;
		}
		JsonObject body = new JsonObject();
		body.addProperty("uuid", localUuid(mc));
		body.addProperty("name", localName(mc));
		body.add("amounts", amounts);
		String code = cur.code;
		SYNC.execute(() -> {
			try {
				var res = authedRequest(mc, client(), c -> c.deliverBatch(code, body));
				if (res.isRateLimited()) {
					noteRateLimit(res);
				}
				if (res.ok && res.value != null) {
					mc.execute(() -> {
						if (!isFollowing(code)) {
							return;
						}
						ClanSession adopted = adoptSession(res.value);
						lastError = null;
						recordDeliveryDiffs(prev, adopted);
					});
				}
			} catch (Exception e) {
				ChestMemoryMod.LOGGER.debug("Clan staging batch: {}", e.toString());
			}
		});
	}

	/**
	 * Upload local warehouse chest keys so clan members can glow the same drop-off.
	 * @param replace if true, hub list becomes exactly local keys; else merge-add
	 */
	public static void pushStagingKeysAsync(Minecraft mc, boolean replace) {
		if (session == null || !client().isConfigured()) {
			return;
		}
		if (rateLimitRemainingSeconds() > 0) {
			// Silent skip, same as a failed push today: the marks stay local and the
			// next explicit push or replace syncs them once the hub talks again.
			return;
		}
		String code = session.code;
		JsonObject body = new JsonObject();
		body.addProperty("uuid", localUuid(mc));
		body.addProperty("name", localName(mc));
		body.addProperty("replace", replace);
		com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
		for (String k : ChestMemoryStorage.get().stagingKeysSnapshot()) {
			arr.add(k);
		}
		body.add("stagingKeys", arr);
		SYNC.execute(() -> {
			try {
				var res = authedRequest(mc, client(), c -> c.staging(code, body));
				if (res.isRateLimited()) {
					noteRateLimit(res);
				}
				if (res.ok && res.value != null) {
					mc.execute(() -> {
						if (!isFollowing(code)) {
							return;
						}
						// adoptSession, not a bare assignment: with the poll on its own
						// lane a fresher snapshot may already be in — the revision check
						// keeps whichever is newer.
						applyClanStagingKeys(adoptSession(res.value));
					});
				}
			} catch (Exception e) {
				ChestMemoryMod.LOGGER.debug("Clan staging push: {}", e.toString());
			}
		});
	}

	/** Import shared warehouse keys from clan session into local memory + glow. */
	public static void applyClanStagingKeys(@Nullable ClanSession s) {
		if (s == null || s.stagingKeys == null || s.stagingKeys.isEmpty()) {
			return;
		}
		int added = ChestMemoryStorage.get().mergeStagingKeys(s.stagingKeys);
		if (added > 0) {
			ChestMemoryMod.LOGGER.debug("Merged {} clan warehouse keys", added);
		}
	}

	public static int clanDelivered(String itemId) {
		if (session == null || itemId == null) {
			return 0;
		}
		ClanSession.ClanMaterial m = session.material(itemId);
		return m == null ? 0 : Math.max(0, m.delivered);
	}

	/**
	 * Adopt a session snapshot from the hub, unless it is older than what we already have.
	 * <p>
	 * Poll, claim, deliver and staging responses race — now across two executor lanes,
	 * so a poll genuinely can be in flight while a claim lands. A slow response applied
	 * after a fresher one used to rewind the whole session: claims flickered back,
	 * delivered counts dropped for a few seconds. The hub increments {@code revision}
	 * on every change, so "older" is a plain comparison; every application runs on the
	 * client thread, which serializes the checks. Cross-code staleness (a response for
	 * a gather we already left) is {@link #isFollowing}'s job — codes never compare here.
	 *
	 * @return the session now in effect
	 */
	/** True when the snapshot's roster contains this uuid. */
	private static boolean containsMember(ClanSession s, String uuid) {
		for (ClanSession.ClanMember m : s.members) {
			if (m.uuid != null && m.uuid.equalsIgnoreCase(uuid)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * True while the gather with this code is still the one being followed.
	 * <p>
	 * Background responses (poll, deliver, staging) apply against whatever session is
	 * current when they land. With their lane separate from clicks, a response for a
	 * gather the player has since left or switched away from could arrive late and
	 * resurrect it — the revision guard cannot catch that, because different codes
	 * never compare. Callers check this on the client thread before adopting.
	 */
	private static boolean isFollowing(String code) {
		ClanSession cur = session;
		return cur != null && code != null && code.equalsIgnoreCase(cur.code);
	}

	private static @Nullable ClanSession adoptSession(@Nullable ClanSession next) {
		ClanSession cur = session;
		if (next == null) {
			return cur;
		}
		if (cur != null && cur.code.equalsIgnoreCase(next.code)
			&& next.revision > 0 && cur.revision > next.revision) {
			return cur;
		}
		if (next.receivedAt == 0) {
			next.receivedAt = System.currentTimeMillis();
		}
		session = next;
		return next;
	}

	/**
	 * True when the item is part of the gather currently being followed.
	 * <p>
	 * The panel lists whatever schematic Litematica has open, which is not necessarily the
	 * schematic of the active gather — switching gathers changes the session but not the local
	 * material list. Claiming an item the session has never heard of made the hub answer
	 * "unknown item"; asking this first turns that into a clear message instead.
	 */
	public static boolean isInActiveGather(@Nullable String itemId) {
		ClanSession s = session;
		return s != null && itemId != null && s.material(itemId) != null;
	}

	public static int clanNeed(String itemId) {
		if (session == null || itemId == null) {
			return 0;
		}
		ClanSession.ClanMaterial m = session.material(itemId);
		return m == null ? 0 : Math.max(0, m.need);
	}

	/** True if another member claimed this item. */
	public static boolean isClaimedByOther(Minecraft mc, String itemId) {
		if (session == null || itemId == null) {
			return false;
		}
		ClanSession.ClanMaterial m = session.material(itemId);
		if (m == null || m.claimedBy == null || m.claimedBy.isBlank()) {
			return false;
		}
		return !localUuid(mc).equalsIgnoreCase(m.claimedBy);
	}

	public static boolean isClaimedByMe(Minecraft mc, String itemId) {
		if (session == null || itemId == null) {
			return false;
		}
		ClanSession.ClanMaterial m = session.material(itemId);
		return m != null && m.claimedBy != null && localUuid(mc).equalsIgnoreCase(m.claimedBy);
	}

	public static @Nullable String claimName(String itemId) {
		if (session == null || itemId == null) {
			return null;
		}
		ClanSession.ClanMaterial m = session.material(itemId);
		return m != null ? m.claimedName : null;
	}

	public static void tick(Minecraft mc) {
		if (mc.player == null) {
			return;
		}
		// Keep the compatibility hint fresh. Identity is the verified Mojang session;
		// these headers only matter on a hub whose operator explicitly turned
		// verification off, where they let an offline-mode member's poll refresh
		// lastSeen so their claims do not time out mid-game.
		ClanHubClient.setIdentityHint(localUuid(mc), localName(mc));
		if (session == null) {
			// A world change on a multiworld server disconnects us; pick the gather back up
			// instead of silently dropping out of the clan.
			resumePausedAsync(mc);
			return;
		}
		tickCounter++;
		// Poll every ~3s so claim chat/GUI update quickly for the clan
		if (tickCounter % 60 == 0) {
			pollAsync(mc);
		}
		// Staging push every ~10s
		if (tickCounter % 200 == 0) {
			pushStagingProgress(mc);
		}
	}

	private static void pollAsync(Minecraft mc) {
		if (session == null || !client().isConfigured()) {
			return;
		}
		if (busy.get()) {
			return;
		}
		if (rateLimitRemainingSeconds() > 0) {
			// The hub said 429: honour Retry-After instead of knocking every 3s.
			// Silent on purpose — a backoff is not news for chat or the feed.
			return;
		}
		String code = session.code;
		long now = System.currentTimeMillis();
		if (now - lastPollMillis < 2500L) {
			return;
		}
		lastPollMillis = now;
		int sinceRevision = session != null ? session.revision : 0;
		SYNC.execute(() -> {
			try {
				ClanHubClient c = client();
				// since-poll: the hub answers a tiny stub when nothing changed, which is the
				// steady state — no snapshot parse, no diffing, just the heartbeat.
				var res = sinceRevision > 0 ? c.getSince(code, sinceRevision) : c.get(code);
				if (res.isUnchanged()) {
					return;
				}
				if (res.ok && res.value != null) {
					mc.execute(() -> {
						if (!isFollowing(code)) {
							// Left or switched while this poll was in flight; its snapshot
							// must not resurrect a gather we are no longer in.
							return;
						}
						ClanSession prev = session;
						ClanSession adopted = adoptSession(res.value);
						lastError = null;
						if (adopted != prev) {
							// The snapshot no longer lists this player: the host kicked them.
							// The hub will not let the heartbeat re-add us, so leave locally
							// with a clear message instead of polling a gather we are out of.
							if (adopted != null && !adopted.members.isEmpty()
								&& !containsMember(adopted, localUuid(mc))) {
								ClanRoster.forget(code);
								if (code.equalsIgnoreCase(ModSettings.get().clanActiveCode())) {
									ModSettings.get().setClanActiveCode("");
								}
								session = null;
								ClanEventLog.clear();
								clearClaimOrder();
								com.chestmemory.client.data.StagingPickMode.stopQuiet();
								ChestMemoryStorage.get().clearStaging();
								chat(mc, Component.translatable("message.chestmemory.clan_kicked_you"));
								return;
							}
							applyClanStagingKeys(adopted);
							// Tell player when someone else claimed / released items
							announceClaimDiffs(mc, prev, adopted, false);
							// Roster and delivery changes are feed-only: they are useful when
							// you open the screen, but not worth a chat line every few seconds.
							recordMemberDiffs(prev, adopted);
							recordDeliveryDiffs(prev, adopted);
							// Keep the switcher's progress numbers current for this gather.
							ClanRoster.remember(
								adopted.code, adopted.schemaName,
								adopted.totalDelivered(), adopted.totalNeed()
							);
						}
					});
				} else if (res.status == 401) {
					// Session token expired or the hub restarted: re-run the handshake once
					// so a long build session does not silently stop syncing.
					ClanAuth.clear();
					ClanAuth.authenticate(c, mc);
				} else if (res.isNotFound()) {
					// Only a real 404 ends the session. Matching on the words "not found"
					// anywhere in the error also fired on proxy/tunnel HTML error pages, so a
					// transient gateway hiccup silently dropped everyone out of the session.
					mc.execute(() -> {
						if (!isFollowing(code)) {
							return;
						}
						ClanRoster.forget(code);
						if (code.equalsIgnoreCase(ModSettings.get().clanActiveCode())) {
							ModSettings.get().setClanActiveCode("");
						}
						session = null;
						ClanEventLog.clear();
						// The host ended the gather: the shared warehouse is no longer a
						// drop-off point, so it must stop glowing for everyone, not just for
						// whoever pressed the button.
						com.chestmemory.client.data.StagingPickMode.stopQuiet();
						ChestMemoryStorage.get().clearStaging();
						chat(mc, Component.translatable("message.chestmemory.clan_ended"));
					});
				} else if (res.isRateLimited()) {
					// Quiet backoff: no chat, no feed entry — the next poll waits it out.
					noteRateLimit(res);
				}
			} catch (Exception e) {
				ChestMemoryMod.LOGGER.debug("Clan poll: {}", e.toString());
			}
		});
	}

	/**
	 * Compare claim fields between two snapshots and print chat lines so everyone
	 * sees who took / released which material (local system messages).
	 *
	 * @param skipSelf if true, do not re-announce our own claim (already printed)
	 */
	private static void announceClaimDiffs(
		Minecraft mc,
		@Nullable ClanSession prev,
		@Nullable ClanSession next,
		boolean skipSelf
	) {
		if (next == null || next.materials == null) {
			return;
		}
		String me = localUuid(mc);
		int printed = 0;
		final int maxPrint = 6;
		boolean saidMore = false;
		for (Map.Entry<String, ClanSession.ClanMaterial> e : next.materials.entrySet()) {
			// Chat is capped, but the loop must keep going: the activity feed records every
			// change, and bailing out here would silently drop the rest of them.
			if (printed >= maxPrint && !saidMore) {
				chat(mc, Component.translatable("message.chestmemory.clan_claim_more"));
				saidMore = true;
			}
			String itemId = e.getKey();
			ClanSession.ClanMaterial nm = e.getValue();
			String newBy = nm != null && nm.claimedBy != null ? nm.claimedBy : "";
			String newName = nm != null && nm.claimedName != null ? nm.claimedName : "?";
			String oldBy = "";
			if (prev != null && prev.materials != null) {
				ClanSession.ClanMaterial om = prev.materials.get(itemId);
				if (om != null && om.claimedBy != null) {
					oldBy = om.claimedBy;
				}
			}
			if (newBy.equalsIgnoreCase(oldBy)) {
				continue;
			}
			boolean newIsMe = !newBy.isEmpty() && newBy.equalsIgnoreCase(me);
			boolean oldIsMe = !oldBy.isEmpty() && oldBy.equalsIgnoreCase(me);
			// Whether this change is worth a chat line. The feed records it either way.
			boolean chatty = printed < maxPrint && !(skipSelf && (newIsMe || oldIsMe));
			String itemName = ChestMemoryStorage.itemDisplayName(itemId);
			// The screen's activity feed gets every change; chat stays capped and still
			// skips our own actions, which the acting client has already reported.
			if (!newBy.isEmpty() && oldBy.isEmpty()) {
				// New claim
				ClanEventLog.add(
					ClanEventLog.Kind.CLAIM,
					Component.translatable("screen.chestmemory.clan.ev_claim", newName, itemName)
				);
				if (chatty) {
					if (newIsMe) {
						chat(mc, Component.translatable("message.chestmemory.clan_claimed_self", itemName));
					} else {
						chat(mc, Component.translatable("message.chestmemory.clan_claimed_other", newName, itemName));
					}
					printed++;
				}
			} else if (newBy.isEmpty() && !oldBy.isEmpty()) {
				// Released
				String oldName = "?";
				if (prev != null) {
					ClanSession.ClanMaterial om = prev.materials.get(itemId);
					if (om != null && om.claimedName != null) {
						oldName = om.claimedName;
					}
				}
				ClanEventLog.add(
					ClanEventLog.Kind.RELEASE,
					Component.translatable("screen.chestmemory.clan.ev_release", oldName, itemName)
				);
				if (chatty) {
					if (oldIsMe) {
						chat(mc, Component.translatable("message.chestmemory.clan_unclaimed_self", itemName));
					} else {
						chat(mc, Component.translatable("message.chestmemory.clan_unclaimed_other", oldName, itemName));
					}
					printed++;
				}
			} else if (!newBy.isEmpty()) {
				// Steal / transfer (shouldn't happen often)
				ClanEventLog.add(
					ClanEventLog.Kind.CLAIM,
					Component.translatable("screen.chestmemory.clan.ev_claim", newName, itemName)
				);
				if (chatty) {
					chat(mc, Component.translatable("message.chestmemory.clan_claimed_other", newName, itemName));
					printed++;
				}
			}
		}
	}

	/**
	 * Record who joined or left between two snapshots.
	 * <p>
	 * Feed-only on purpose: on a big build the roster churns as people log in and out, and
	 * chat lines for that would bury the claim messages that actually need attention.
	 */
	private static void recordMemberDiffs(@Nullable ClanSession prev, @Nullable ClanSession next) {
		if (prev == null || next == null) {
			// First snapshot of a session: everyone present is not "news".
			return;
		}
		Map<String, String> before = new HashMap<>();
		for (ClanSession.ClanMember m : prev.members) {
			if (m.uuid != null && !m.uuid.isBlank()) {
				before.put(m.uuid.toLowerCase(java.util.Locale.ROOT), m.name == null ? "?" : m.name);
			}
		}
		Map<String, String> after = new HashMap<>();
		for (ClanSession.ClanMember m : next.members) {
			if (m.uuid != null && !m.uuid.isBlank()) {
				after.put(m.uuid.toLowerCase(java.util.Locale.ROOT), m.name == null ? "?" : m.name);
			}
		}
		for (Map.Entry<String, String> e : after.entrySet()) {
			if (!before.containsKey(e.getKey())) {
				ClanEventLog.add(
					ClanEventLog.Kind.JOIN,
					Component.translatable("screen.chestmemory.clan.ev_join", e.getValue())
				);
			}
		}
		for (Map.Entry<String, String> e : before.entrySet()) {
			if (!after.containsKey(e.getKey())) {
				ClanEventLog.add(
					ClanEventLog.Kind.LEAVE,
					Component.translatable("screen.chestmemory.clan.ev_leave", e.getValue())
				);
			}
		}
	}

	/**
	 * Record materials that reached the warehouse between two snapshots.
	 * <p>
	 * The hub counts a delivery when items land in a shared warehouse chest, so this is the
	 * event people actually care about — "is the glass in yet?" — and the one that used to
	 * be invisible unless you happened to watch the numbers change.
	 */
	private static void recordDeliveryDiffs(@Nullable ClanSession prev, @Nullable ClanSession next) {
		if (prev == null || next == null || next.materials == null) {
			return;
		}
		for (Map.Entry<String, ClanSession.ClanMaterial> e : next.materials.entrySet()) {
			ClanSession.ClanMaterial nm = e.getValue();
			if (nm == null) {
				continue;
			}
			ClanSession.ClanMaterial om = prev.materials == null ? null : prev.materials.get(e.getKey());
			int was = om == null ? 0 : Math.max(0, om.delivered);
			int now = Math.max(0, nm.delivered);
			if (now <= was) {
				continue;
			}
			String itemName = ChestMemoryStorage.itemDisplayName(e.getKey());
			// The hub records who actually raised the count; the claim holder is only a
			// guess (anyone can carry to the warehouse), so it is the fallback.
			String who = nm.lastDeliveredBy != null && !nm.lastDeliveredBy.isBlank()
				? nm.lastDeliveredBy
				: (nm.claimedName != null && !nm.claimedName.isBlank() ? nm.claimedName : null);
			ClanEventLog.add(
				ClanEventLog.Kind.DELIVER,
				who != null
					? Component.translatable("screen.chestmemory.clan.ev_deliver", who, now - was, itemName)
					: Component.translatable("screen.chestmemory.clan.ev_deliver_anon", now - was, itemName)
			);
		}
	}

	/** First letter for slot badge (or null). */
	public static @Nullable String claimBadge(String itemId) {
		String who = claimName(itemId);
		if (who == null || who.isBlank()) {
			return null;
		}
		return who.substring(0, 1).toUpperCase(java.util.Locale.ROOT);
	}

	private static void fail(String key, Minecraft mc) {
		lastError = key;
		chat(mc, Component.translatable("message.chestmemory.clan_err_" + key));
	}

	private static void failRaw(@Nullable String msg, Minecraft mc) {
		lastError = msg;
		chat(mc, Component.translatable("message.chestmemory.clan_err",
			msg != null ? msg : "?"));
	}

	/**
	 * Report a failed hub response, translating the conditions the hub now signals
	 * deliberately instead of echoing them as raw server strings:
	 * <ul>
	 *   <li>429 → note the backoff and say "slow down" with the wait;</li>
	 *   <li>identity refusals (401 auth required / 403 host actions need a verified
	 *       identity) → say that a Mojang-verified sign-in is required, which after
	 *       {@link #authedRequest}'s retry means this client genuinely cannot produce
	 *       one (offline launcher) — not that the hub is down;</li>
	 *   <li>a refused plaintext hub URL → point at the https requirement.</li>
	 * </ul>
	 */
	private static void failResult(Minecraft mc, ClanHubClient.Result<?> res, String fallback) {
		if (res.isRateLimited()) {
			noteRateLimit(res);
			lastError = "rate limited";
			chat(mc, Component.translatable(
				"message.chestmemory.clan_err_slow_down", rateLimitRemainingSeconds()
			));
			return;
		}
		if (isIdentityRefusal(res)) {
			fail("need_verified", mc);
			return;
		}
		if (ClanHubClient.ERR_INSECURE_URL.equals(res.error)) {
			fail("insecure_url", mc);
			return;
		}
		failRaw(res.error != null ? res.error : fallback, mc);
	}

	private static void chat(Minecraft mc, Component c) {
		LocalPlayer p = mc.player;
		if (p != null) {
			p.sendSystemMessage(c);
		}
	}

	/**
	 * Leaving a server / quitting to the menu: hand our claims back now.
	 * <p>
	 * The hub would eventually release them on heartbeat timeout, but that leaves the
	 * clan unable to pick up those materials for minutes. Best-effort and fire-and-forget
	 * — the game is shutting down, so there is nobody left to report a failure to.
	 * <p>
	 * Note this is deliberately NOT wired to dimension changes: going to the Nether keeps
	 * the same connection, and the poll keeps the heartbeat alive.
	 */
	public static void releaseOnDisconnect() {
		ClanSession current = session;
		if (current == null || current.code == null || current.code.isBlank()) {
			return;
		}
		// Do NOT send "leave". On a multiworld server a portal between worlds is a full
		// reconnect, so leaving here dropped the player out of the clan every time they
		// walked through the Nether — claims released, roster gone, activity feed wiped.
		// The hub already handles a client that really left: no heartbeat for
		// CLAIM_TIMEOUT_SEC frees the claims on its own.
		//
		// Park the session instead and remember the code, so the next tick in the new world
		// rejoins it. The feed is kept for the same reason: it describes the gather, not the
		// connection.
		pausedCode = current.code;
		session = null;
		lastError = null;
		ClanAuth.clear();
	}

	/**
	 * Rejoin the gather that was interrupted by a world change.
	 * <p>
	 * Called from the tick once a player is in a world again. Silent on purpose: this is the
	 * same gather the player was already in, so announcing a join every portal trip would be
	 * noise.
	 */
	private static void resumePausedAsync(Minecraft mc) {
		String code = pausedCode;
		if (code == null || code.isBlank() || session != null) {
			return;
		}
		if (rateLimitRemainingSeconds() > 0) {
			// A silent retry loop is exactly what a 429 asks to pause.
			return;
		}
		if (!client().isConfigured() || !busy.compareAndSet(false, true)) {
			return;
		}
		// Rate-limit the retry: the tick runs 20×/s, and a hub that is briefly unreachable
		// would otherwise be hammered while the player stands in the new world.
		long now = System.currentTimeMillis();
		if (now - lastResumeAttemptMillis < 3000L) {
			busy.set(false);
			return;
		}
		lastResumeAttemptMillis = now;
		// pausedCode is deliberately NOT cleared here. Clearing before the answer meant one
		// failed attempt lost the session for good: no session means no heartbeat, and after
		// CLAIM_TIMEOUT_SEC the hub released the claims and the player showed up as offline.
		JsonObject body = new JsonObject();
		body.addProperty("name", localName(mc));
		body.addProperty("uuid", localUuid(mc));
		IO.execute(() -> {
			try {
				var res = authedRequest(mc, client(), c -> c.join(code, body));
				if (res.isRateLimited()) {
					noteRateLimit(res);
				}
				mc.execute(() -> {
					busy.set(false);
					if (res.ok && res.value != null) {
						pausedCode = null;
						adoptSession(res.value);
						lastError = null;
						lastPollMillis = System.currentTimeMillis();
						applyClanStagingKeys(session);
					} else if (res.isNotFound()) {
						// Gather really ended while we were between worlds: stop retrying.
						pausedCode = null;
						ClanRoster.forget(code);
					}
					// Any other failure keeps pausedCode so the next attempt tries again.
				});
			} catch (Exception e) {
				mc.execute(() -> busy.set(false));
				ChestMemoryMod.LOGGER.debug("Clan resume: {}", e.toString());
			}
		});
	}


	public static void clearLocal() {
		session = null;
		lastError = null;
		ClanEventLog.clear();
		ClanAuth.clear();
	}
}
