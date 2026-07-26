package com.chestmemory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Five bugs reported from a live clan gather, all with one root cause: the local gather
 * (queue, target, warehouse) and the clan session (materials, claims, roster) were separate
 * things that nobody kept in step.
 * <p>
 * Worst of them was not in that list, but explains most of it: on a multiworld server a portal
 * is a full reconnect, and the disconnect handler sent "leave" to the hub — so walking through
 * the Nether dropped the player out of the clan, released their claims and wiped the activity
 * feed, every single time.
 */
class ClanFlowTest {
	private static String read(String path) throws Exception {
		return Files.readString(Path.of(path));
	}

	private static final String CLAN =
		"src/client/java/com/chestmemory/client/clan/ClanSessionManager.java";
	private static final String SCREEN =
		"src/client/java/com/chestmemory/client/gui/ChestMemoryScreen.java";
	private static final String CLAN_SCREEN =
		"src/client/java/com/chestmemory/client/gui/ClanGatherScreen.java";

	private static String methodBody(String src, String signature) {
		int start = src.indexOf(signature);
		assertTrue(start > 0, "not found: " + signature + " — renamed? update this test");
		int pub = src.indexOf("\n\tpublic ", start + signature.length());
		int priv = src.indexOf("\n\tprivate ", start + signature.length());
		int end = pub;
		if (priv > 0 && (end < 0 || priv < end)) {
			end = priv;
		}
		return end > start ? src.substring(start, end) : src.substring(start);
	}

	@Nested
	@DisplayName("A portal must not drop you out of the clan")
	class PortalKeepsSession {

		@Test
		@DisplayName("Disconnect no longer sends leave to the hub")
		void noLeaveOnDisconnect() throws Exception {
			// This is the bug behind "the activity feed is always empty" and half the odd
			// gather behaviour: a Multiverse portal disconnects, and leaving here removed the
			// player from the session every time.
			String body = methodBody(read(CLAN), "public static void releaseOnDisconnect()");
			assertTrue(
				!body.contains("client().leave("),
				"a world change must not leave the clan session"
			);
			assertTrue(
				body.contains("pausedCode"),
				"the code has to be remembered so the session can be picked up again"
			);
		}

		@Test
		@DisplayName("The feed survives a world change")
		void feedSurvivesDisconnect() throws Exception {
			String body = methodBody(read(CLAN), "public static void releaseOnDisconnect()");
			assertTrue(
				!body.contains("ClanEventLog.clear()"),
				"the feed describes the gather, not the connection"
			);
		}

		@Test
		@DisplayName("The tick rejoins a paused gather")
		void tickResumes() throws Exception {
			String src = read(CLAN);
			assertTrue(src.contains("resumePausedAsync("), "a paused gather must be picked up again");
			int tick = src.indexOf("public static void tick(");
			assertTrue(tick > 0, "tick not found");
			String body = src.substring(tick, src.indexOf("\n\tprivate static", tick));
			assertTrue(
				body.contains("resumePausedAsync(mc)"),
				"the tick is the only place that runs once we are in a world again"
			);
		}

		@Test
		@DisplayName("Rejoining is silent — a portal trip should not spam chat")
		void resumeIsSilent() throws Exception {
			String body = methodBody(read(CLAN), "private static void resumePausedAsync(");
			assertTrue(
				!body.contains("clan_joined"),
				"this is the gather the player was already in"
			);
		}
	}

	@Nested
	@DisplayName("Switching gathers keeps the two sides in step")
	class Switching {

		@Test
		@DisplayName("The local gather is reset, so the panel matches the active session")
		void switchResetsLocalGather() throws Exception {
			// Without this the panel listed one schematic's materials while the session
			// described another: old items looked claimed, and clicking a new one made the hub
			// answer "unknown item".
			String body = methodBody(read(CLAN), "public static void switchToAsync(");
			assertTrue(
				body.contains("BuildGatherSession.clear()"),
				"the queue belongs to the schematic being left behind"
			);
		}

		@Test
		@DisplayName("Switching does not wipe the activity feed")
		void switchKeepsFeed() throws Exception {
			String body = methodBody(read(CLAN), "public static void switchToAsync(");
			assertTrue(
				!body.contains("ClanEventLog.clear()"),
				"clearing on every switch is why activity was never readable"
			);
		}

		@Test
		@DisplayName("The warehouse is still handed over")
		void switchHandsOverWarehouse() throws Exception {
			String body = methodBody(read(CLAN), "public static void switchToAsync(");
			assertTrue(body.contains("clearStaging()"), "one gather's drop-off is not another's");
		}
	}

	@Nested
	@DisplayName("Claiming an item the gather does not have")
	class UnknownItem {

		@Test
		@DisplayName("Refused locally instead of asking the hub")
		void claimGuarded() throws Exception {
			String body = methodBody(read(CLAN), "public static void claimToggleAsync(");
			assertTrue(
				body.contains("isInActiveGather("),
				"the hub answered 'unknown item'; check before sending"
			);
			assertTrue(
				body.contains("clan_not_in_gather"),
				"the player needs to be told why the click did nothing"
			);
		}

		@Test
		@DisplayName("The message exists in both languages")
		void messageTranslated() throws Exception {
			for (String lang : new String[] {"ru_ru", "en_us"}) {
				String json = read("src/main/resources/assets/chestmemory/lang/" + lang + ".json");
				assertTrue(
					json.contains("message.chestmemory.clan_not_in_gather"),
					"missing translation in " + lang
				);
			}
		}
	}

	@Nested
	@DisplayName("Finishing a gather")
	class Finish {

		@Test
		@DisplayName("Finish ends the clan session too, not only the local queue")
		void finishLeavesClan() throws Exception {
			// Reported: pressing "Завершить" left the gather running, because only the local
			// side was cleared and the session kept polling.
			String body = methodBody(read(SCREEN), "private void finishGatherMode()");
			assertTrue(
				body.contains("ClanSessionManager.leaveAsync("),
				"finishing has to end the clan side as well"
			);
			assertTrue(body.contains("clearStaging()"), "and drop the warehouse marks");
		}
	}

	@Nested
	@DisplayName("Gathers tab hit-testing")
	class TabClicks {

		@Test
		@DisplayName("Clicks above the first row are left to the tab strip")
		void clicksAboveListIgnored() throws Exception {
			// (int) of a negative offset is 0 in Java, so a click on the tab strip mapped to
			// row 0 and switched gathers — which is why the tab could not be left.
			String body = methodBody(read(CLAN_SCREEN), "private @org.jspecify.annotations.Nullable String gatherAt(");
			assertTrue(
				body.contains("my < this.listRowsTop"),
				"without this guard the tab strip is swallowed by the gather list"
			);
		}

		@Test
		@DisplayName("Row hit-testing maths, including the tab strip above it")
		void hitTestMaths() {
			int listTop = 480;
			int rowH = 22;
			int rows = 3;
			assertNull(rowAt(458, listTop, rowH, rows), "tab strip");
			assertNull(rowAt(473, listTop, rowH, rows), "bottom of the tab strip");
			assertNull(rowAt(478, listTop, rowH, rows), "gap above the first row");
			assertEquals(Integer.valueOf(0), rowAt(482, listTop, rowH, rows));
			assertEquals(Integer.valueOf(1), rowAt(504, listTop, rowH, rows));
			assertNull(rowAt(501, listTop, rowH, rows), "2px seam between rows");
			assertNull(rowAt(560, listTop, rowH, rows), "below the last row");
		}

		/** Mirrors gatherAt's arithmetic so the boundaries can be checked without the game. */
		private static Integer rowAt(double my, int listTop, int rowH, int rows) {
			if (my < listTop) {
				return null;
			}
			int idx = (int) ((my - listTop) / rowH);
			if (idx < 0 || idx >= rows) {
				return null;
			}
			if (my > listTop + idx * rowH + 20) {
				return null;
			}
			return idx;
		}
	}
}
