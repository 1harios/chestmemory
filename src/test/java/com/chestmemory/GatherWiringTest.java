package com.chestmemory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards on how the gather is wired together.
 * <p>
 * The behaviour these cover lives behind Minecraft classes — claims, warehouse marks, HUD
 * targets — so exercising them for real would mean booting the game. What actually broke in
 * practice was never the arithmetic, though: it was a call that was never made. The portal
 * fix shipped dead because the cache was armed from a method the gather does not use, and
 * these checks are aimed squarely at that failure mode.
 * <p>
 * They read source, which is blunt, and they will need updating if these methods are renamed.
 * That is the intended trade: a test that fails loudly on a rename beats a silent regression.
 */
class GatherWiringTest {
	private static String read(String path) throws Exception {
		return Files.readString(Path.of(path));
	}

	private static String methodBody(String src, String signature) {
		int start = src.indexOf(signature);
		assertTrue(start > 0, "not found: " + signature + " — renamed? update this test");
		int end = src.indexOf("\n\tpublic ", start + signature.length());
		int endPrivate = src.indexOf("\n\tprivate ", start + signature.length());
		if (endPrivate > 0 && (end < 0 || endPrivate < end)) {
			end = endPrivate;
		}
		return end > start ? src.substring(start, end) : src.substring(start);
	}

	private static final String SESSION =
		"src/client/java/com/chestmemory/client/litematica/BuildGatherSession.java";
	private static final String SCREEN =
		"src/client/java/com/chestmemory/client/gui/ChestMemoryScreen.java";
	private static final String CLAN =
		"src/client/java/com/chestmemory/client/clan/ClanSessionManager.java";

	@Nested
	@DisplayName("Claims drive the gather")
	class Claims {

		@Test
		@DisplayName("Target selection prefers what this player claimed")
		void ownClaimWinsOverRanking() throws Exception {
			// The complaint: claimed Peony and Sunflower, HUD said "Dirt / free" because the
			// ranking ignored claims entirely.
			String body = methodBody(read(SESSION), "private static @Nullable String bestIdForPhase(");
			assertTrue(
				body.contains("firstOwnClaim("),
				"bestIdForPhase must consult the player's own claims before its ranking"
			);
		}

		@Test
		@DisplayName("A claimed item with nothing in chests is still kept as the target")
		void staysOnClaimWithNoStock() throws Exception {
			// The user chose this explicitly: stay on it, let them decide to mine it or drop
			// the claim. So the claim lookup must not filter on chest stock.
			String body = methodBody(read(SESSION), "private static @Nullable String firstOwnClaim(");
			assertTrue(
				!body.contains("countInChestsLive("),
				"firstOwnClaim must not skip claims that have no stock — the user asked to stay"
			);
			assertTrue(
				body.contains("remainingNeed("),
				"but a claim that is already fully delivered should not be re-targeted"
			);
		}

		@Test
		@DisplayName("Claiming from the panel moves the HUD onto that item at once")
		void claimingFocusesImmediately() throws Exception {
			// Second complaint: "I take an item and the HUD still names the old one."
			assertTrue(
				read(SCREEN).contains("BuildGatherSession.focusClaimed("),
				"the panel must retarget the gather when a claim is made"
			);
		}

		@Test
		@DisplayName("Clicking your own claim gives it up")
		void claimCanBeReleased() throws Exception {
			String src = read(SCREEN);
			assertTrue(
				src.contains("isClaimedByMe(client, summary.itemId())")
					&& src.contains("claimToggleAsync"),
				"clicking an item you already claimed must release it"
			);
			assertTrue(
				src.contains("dropCurrentClaimFocus("),
				"releasing the current target must move the gather off it"
			);
		}
	}

	@Nested
	@DisplayName("Warehouse marks belong to the gather")
	class Warehouse {

		@Test
		@DisplayName("Finishing the gather clears the warehouse")
		void finishClearsWarehouse() throws Exception {
			// Reported: the warehouse chest kept glowing after the clan gather was finished.
			String body = methodBody(read(SCREEN), "private void finishGatherMode()");
			assertTrue(
				body.contains("clearStaging()"),
				"finishGatherMode must clear warehouse marks"
			);
		}

		@Test
		@DisplayName("Leaving the clan clears the warehouse")
		void leaveClearsWarehouse() throws Exception {
			String src = read(CLAN);
			int leave = src.indexOf("clan_closed");
			assertTrue(leave > 0, "leave path not found");
			String around = src.substring(Math.max(0, leave - 900), leave);
			assertTrue(
				around.contains("clearStaging()"),
				"leaving or closing a clan session must clear warehouse marks"
			);
		}

		@Test
		@DisplayName("The host ending the gather clears it for everyone")
		void remoteEndClearsWarehouse() throws Exception {
			String src = read(CLAN);
			int ended = src.indexOf("clan_ended");
			assertTrue(ended > 0, "session-ended path not found");
			String around = src.substring(Math.max(0, ended - 700), ended);
			assertTrue(
				around.contains("clearStaging()"),
				"a session ended remotely must clear the shared warehouse for this client too"
			);
		}
	}

	@Nested
	@DisplayName("Material list cache wiring")
	class Cache {

		@Test
		@DisplayName("startQueue arms the cache")
		void startQueueArms() throws Exception {
			String body = methodBody(read(SESSION), "public static void startQueue(");
			assertTrue(
				body.contains("MaterialListCache.setArmed(true)"),
				"startQueue must arm the cache, otherwise a portal empties the gather list"
			);
		}

		@Test
		@DisplayName("The HUD warning keys on the dimension, not on an empty list")
		void hudUsesDimensionCheck() throws Exception {
			String hud = read("src/client/java/com/chestmemory/client/litematica/BuildGatherHud.java");
			assertTrue(
				hud.contains("isAwayFromSchematic()"),
				"the HUD must ask whether we are away from the schematic's world"
			);
			assertTrue(
				!hud.contains("isUsingCachedList()"),
				"the old empty-list check never cleared once Litematica dropped its list"
			);
		}
	}
}
