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
		return new ClanHubClient(s.clanHubUrl(), s.clanToken());
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
				var res = client().create(body);
				mc.execute(() -> {
					busy.set(false);
					if (res.ok && res.value != null) {
						session = res.value;
						lastError = null;
						lastPollMillis = System.currentTimeMillis();
						// Upload existing warehouse marks so clan sees drop-off
						pushStagingKeysAsync(mc, true);
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
				var res = client().join(code, body);
				mc.execute(() -> {
					busy.set(false);
					if (res.ok && res.value != null) {
						session = res.value;
						lastError = null;
						lastPollMillis = System.currentTimeMillis();
						applyClanStagingKeys(session);
						// Merge our local warehouse marks into clan list
						pushStagingKeysAsync(mc, false);
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
				session = null;
				lastError = null;
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
		if (mc.player == null || session == null) {
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
				var res = client().get(code);
				if (res.ok && res.value != null) {
					mc.execute(() -> {
						ClanSession prev = session;
						session = res.value;
						lastError = null;
						applyClanStagingKeys(session);
						// Tell player when someone else claimed / released items
						announceClaimDiffs(mc, prev, session, false);
					});
				} else if (res.error != null && res.error.toLowerCase().contains("not found")) {
					mc.execute(() -> {
						session = null;
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
		for (Map.Entry<String, ClanSession.ClanMaterial> e : next.materials.entrySet()) {
			if (printed >= maxPrint) {
				chat(mc, Component.translatable("message.chestmemory.clan_claim_more"));
				break;
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
			if (skipSelf && (newIsMe || oldIsMe)) {
				continue;
			}
			String itemName = ChestMemoryStorage.itemDisplayName(itemId);
			if (!newBy.isEmpty() && oldBy.isEmpty()) {
				// New claim
				if (newIsMe) {
					chat(mc, Component.translatable("message.chestmemory.clan_claimed_self", itemName));
				} else {
					chat(mc, Component.translatable("message.chestmemory.clan_claimed_other", newName, itemName));
				}
				printed++;
			} else if (newBy.isEmpty() && !oldBy.isEmpty()) {
				// Released
				String oldName = "?";
				if (prev != null) {
					ClanSession.ClanMaterial om = prev.materials.get(itemId);
					if (om != null && om.claimedName != null) {
						oldName = om.claimedName;
					}
				}
				if (oldIsMe) {
					chat(mc, Component.translatable("message.chestmemory.clan_unclaimed_self", itemName));
				} else {
					chat(mc, Component.translatable("message.chestmemory.clan_unclaimed_other", oldName, itemName));
				}
				printed++;
			} else if (!newBy.isEmpty()) {
				// Steal / transfer (shouldn't happen often)
				chat(mc, Component.translatable("message.chestmemory.clan_claimed_other", newName, itemName));
				printed++;
			}
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

	public static void clearLocal() {
		session = null;
		lastError = null;
	}
}
