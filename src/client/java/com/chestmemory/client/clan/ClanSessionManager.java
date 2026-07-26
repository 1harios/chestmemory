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
	private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "chestmemory-clan-io");
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
		return new ClanHubClient(
			ClanDefaults.effectiveUrl(s.clanHubUrl()),
			ClanDefaults.effectiveToken(s.clanToken())
		);
	}

	public static String localUuid(Minecraft mc) {
		if (mc.player != null && mc.player.getUUID() != null) {
			return mc.player.getUUID().toString();
		}
		if (mc.getUser() != null && mc.getUser().getProfileId() != null) {
			return mc.getUser().getProfileId().toString();
		}
		return UUID.randomUUID().toString();
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
		if (!LitematicaAccess.hasActiveMaterialList()) {
			fail("no_list", mc);
			if (onDone != null) {
				onDone.run();
			}
			return;
		}
		if (!busy.compareAndSet(false, true)) {
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
				ClanHubClient c = client();
				// Prove who we are before acting; the hub may require it.
				ClanAuth.ensureAuthenticated(c, mc);
				var res = c.create(body);
				mc.execute(() -> {
					busy.set(false);
					if (res.ok && res.value != null) {
						session = res.value;
						lastError = null;
						lastPollMillis = System.currentTimeMillis();
						// A new gather starts with no warehouse. Uploading the local marks here
						// handed the farm's drop-off chest to the house build — every schematic
						// ended up sharing one warehouse. The host marks a chest for this build.
						com.chestmemory.client.data.StagingPickMode.stopQuiet();
						ChestMemoryStorage.get().clearStaging();
						ClanRoster.remember(session.code, session.schemaName, session.totalDelivered(), session.totalNeed());
						ModSettings.get().setClanActiveCode(session.code);
						chat(mc, Component.translatable("message.chestmemory.clan_created", session.code));
						// Also paste-friendly line
						chat(mc, Component.translatable("message.chestmemory.clan_code_line", session.code));
					} else {
						failRaw(res.error != null ? res.error : "create failed", mc);
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
		if (!busy.compareAndSet(false, true)) {
			return;
		}
		JsonObject body = new JsonObject();
		body.addProperty("name", localName(mc));
		body.addProperty("uuid", localUuid(mc));
		IO.execute(() -> {
			try {
				ClanHubClient c = client();
				ClanAuth.ensureAuthenticated(c, mc);
				var res = c.join(code, body);
				mc.execute(() -> {
					busy.set(false);
					if (res.ok && res.value != null) {
						session = res.value;
						lastError = null;
						lastPollMillis = System.currentTimeMillis();
						// Adopt this gather's warehouse; do not push our own marks into it. A
						// member joining used to merge whatever they had marked locally into the
						// session, which is how one chest leaked across every schematic.
						com.chestmemory.client.data.StagingPickMode.stopQuiet();
						ChestMemoryStorage.get().clearStaging();
						applyClanStagingKeys(session);
						ClanRoster.remember(session.code, session.schemaName, session.totalDelivered(), session.totalNeed());
						ModSettings.get().setClanActiveCode(session.code);
						chat(mc, Component.translatable("message.chestmemory.clan_joined", session.code));
						if (session.stagingKeys != null && !session.stagingKeys.isEmpty()) {
							chat(mc, Component.translatable(
								"message.chestmemory.clan_warehouse",
								session.stagingKeys.size()
							));
						}
					} else {
						failRaw(res.error != null ? res.error : "join failed", mc);
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
		// Drop what belonged to the previous gather before the new one arrives, so a failed
		// switch cannot leave the old warehouse glowing under the new build's name.
		com.chestmemory.client.data.StagingPickMode.stopQuiet();
		ChestMemoryStorage.get().clearStaging();
		// The feed is NOT cleared here. Switching gathers used to wipe it, and since a portal
		// also rejoins, activity never survived long enough to be read. Entries name the item
		// and the player, so a few lines from the previous gather are harmless.
		// The local gather queue belongs to the schematic of the gather we are leaving. Keeping
		// it made the panel show one build's materials while the session described another —
		// old items still looked claimed, and clicking a new one made the hub answer
		// "unknown item". Stop it; the player restarts the gather for the new build.
		com.chestmemory.client.litematica.BuildGatherSession.clear();
		// join is idempotent on the hub: a member who is already in the session just gets the
		// current snapshot back, so this doubles as "switch to".
		joinAsync(mc, code, onDone);
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
			return;
		}
		JsonObject body = new JsonObject();
		body.addProperty("uuid", localUuid(mc));
		body.addProperty("name", localName(mc));
		IO.execute(() -> {
			try {
				if (host) {
					client().close(code, body);
				} else {
					client().leave(code, body);
				}
			} catch (Exception e) {
				ChestMemoryMod.LOGGER.debug("Clan leave: {}", e.toString());
			}
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
				// Warehouse marks came from this gather — shared ones from the clan, local ones
				// made for it. Leaving means they are no longer drop-off points, so stop
				// glowing over them. Quiet: session is already null, and pushing here would
				// only be an upload into a gather we just left.
				com.chestmemory.client.data.StagingPickMode.stopQuiet();
				ChestMemoryStorage.get().clearStaging();
				chat(mc, Component.translatable(
					host ? "message.chestmemory.clan_closed" : "message.chestmemory.clan_left"
				));
				if (onDone != null) {
					onDone.run();
				}
			});
		});
	}

	public static boolean isHost(Minecraft mc) {
		if (session == null) {
			return false;
		}
		return localUuid(mc).equalsIgnoreCase(session.hostUuid);
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
		ClanSession.ClanMaterial m = session.material(itemId);
		boolean unclaim = m != null && localUuid(mc).equalsIgnoreCase(m.claimedBy);
		if (!busy.compareAndSet(false, true)) {
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
				var res = client().claim(code, body);
				mc.execute(() -> {
					busy.set(false);
					if (res.ok && res.value != null) {
						ClanSession prev = session;
						session = res.value;
						lastError = null;
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
						failRaw(res.error != null ? res.error : "claim failed", mc);
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
		JsonObject body = new JsonObject();
		body.addProperty("itemId", itemId);
		body.addProperty("amount", amount);
		body.addProperty("uuid", localUuid(mc));
		body.addProperty("name", localName(mc));
		String code = session.code;
		IO.execute(() -> {
			try {
				var res = client().deliver(code, body);
				if (res.ok && res.value != null) {
					mc.execute(() -> {
						session = res.value;
						lastError = null;
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
	public static void pushStagingProgress(Minecraft mc) {
		if (session == null) {
			return;
		}
		for (String itemId : session.materials.keySet()) {
			int staging = ChestMemoryStorage.get().countInStaging(itemId);
			if (staging <= 0) {
				continue;
			}
			ClanSession.ClanMaterial m = session.material(itemId);
			if (m != null && staging > m.delivered) {
				reportDeliveredAsync(mc, itemId, staging);
			}
		}
	}

	/**
	 * Upload local warehouse chest keys so clan members can glow the same drop-off.
	 * @param replace if true, hub list becomes exactly local keys; else merge-add
	 */
	public static void pushStagingKeysAsync(Minecraft mc, boolean replace) {
		if (session == null || !client().isConfigured()) {
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
		IO.execute(() -> {
			try {
				var res = client().staging(code, body);
				if (res.ok && res.value != null) {
					mc.execute(() -> {
						session = res.value;
						applyClanStagingKeys(res.value);
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
		String code = session.code;
		long now = System.currentTimeMillis();
		if (now - lastPollMillis < 2500L) {
			return;
		}
		lastPollMillis = now;
		IO.execute(() -> {
			try {
				ClanHubClient c = client();
				var res = c.get(code);
				if (res.ok && res.value != null) {
					mc.execute(() -> {
						ClanSession prev = session;
						session = res.value;
						lastError = null;
						applyClanStagingKeys(session);
						// Tell player when someone else claimed / released items
						announceClaimDiffs(mc, prev, session, false);
						// Roster and delivery changes are feed-only: they are useful when you
						// open the screen, but not worth a chat line every few seconds.
						recordMemberDiffs(prev, session);
						recordDeliveryDiffs(prev, session);
						// Keep the switcher's progress numbers current for the active gather.
						ClanRoster.remember(
							session.code, session.schemaName,
							session.totalDelivered(), session.totalNeed()
						);
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
			// Name the carrier when the hub knows it; otherwise report the amount alone
			// rather than inventing an attribution.
			String who = nm.claimedName != null && !nm.claimedName.isBlank() ? nm.claimedName : null;
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
		if (!client().isConfigured() || !busy.compareAndSet(false, true)) {
			return;
		}
		pausedCode = null;
		JsonObject body = new JsonObject();
		body.addProperty("name", localName(mc));
		body.addProperty("uuid", localUuid(mc));
		IO.execute(() -> {
			try {
				ClanHubClient c = client();
				ClanAuth.ensureAuthenticated(c, mc);
				var res = c.join(code, body);
				mc.execute(() -> {
					busy.set(false);
					if (res.ok && res.value != null) {
						session = res.value;
						lastError = null;
						lastPollMillis = System.currentTimeMillis();
						applyClanStagingKeys(session);
					} else if (res.isNotFound()) {
						// Gather ended while we were between worlds.
						ClanRoster.forget(code);
					}
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
