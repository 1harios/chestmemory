package com.chestmemory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Several clan gathers at once: a code for the farm, another for the house.
 * <p>
 * The hub already supported this — sessions live in a map keyed by code, each with its own
 * materials, warehouse and progress — so the work is entirely client side: remember the codes,
 * follow exactly one, and hand over the per-gather state when switching.
 * <p>
 * The roster itself reads and writes settings, which needs Minecraft, so these tests pin the
 * parts that can be checked without booting the game: the progress arithmetic, and the wiring
 * that decides what happens on create / join / leave / switch.
 */
class ClanRosterTest {
	private static String read(String path) throws Exception {
		return Files.readString(Path.of(path));
	}

	private static final String MANAGER =
		"src/client/java/com/chestmemory/client/clan/ClanSessionManager.java";
	private static final String ROSTER =
		"src/client/java/com/chestmemory/client/clan/ClanRoster.java";
	private static final String SCREEN =
		"src/client/java/com/chestmemory/client/gui/ClanGatherScreen.java";

	@Nested
	@DisplayName("Progress shown in the switcher")
	class Progress {

		@Test
		@DisplayName("Percent is delivered over need")
		void percent() {
			assertEquals(50, new com.chestmemory.client.clan.ClanRoster.Entry("CM-A", "Farm", 16, 32).percent());
			assertEquals(100, new com.chestmemory.client.clan.ClanRoster.Entry("CM-A", "Farm", 32, 32).percent());
			assertEquals(0, new com.chestmemory.client.clan.ClanRoster.Entry("CM-A", "Farm", 0, 32).percent());
		}

		@Test
		@DisplayName("A gather with no materials yet reports 0, not a division by zero")
		void noNeedIsZero() {
			assertEquals(0, new com.chestmemory.client.clan.ClanRoster.Entry("CM-A", "", 0, 0).percent());
		}
	}

	@Nested
	@DisplayName("Roster lifecycle")
	class Lifecycle {

		@Test
		@DisplayName("Creating and joining both remember the gather")
		void createAndJoinRemember() throws Exception {
			String src = read(MANAGER);
			int created = src.indexOf("message.chestmemory.clan_created");
			int joined = src.indexOf("message.chestmemory.clan_joined");
			assertTrue(created > 0 && joined > 0, "create/join paths not found");
			assertTrue(
				src.substring(Math.max(0, created - 400), created).contains("ClanRoster.remember("),
				"creating a gather must add it to the switcher"
			);
			assertTrue(
				src.substring(Math.max(0, joined - 400), joined).contains("ClanRoster.remember("),
				"joining a gather must add it to the switcher"
			);
		}

		@Test
		@DisplayName("Leaving forgets only that gather, so the others stay switchable")
		void leaveForgetsOne() throws Exception {
			String src = read(MANAGER);
			int closed = src.indexOf("message.chestmemory.clan_closed");
			assertTrue(closed > 0, "leave path not found");
			String around = src.substring(Math.max(0, closed - 900), closed);
			assertTrue(around.contains("ClanRoster.forget(code)"), "leaving must forget that code");
			assertTrue(
				!around.contains("ClanRoster.clearAll()"),
				"leaving one gather must not wipe the whole list"
			);
		}

		@Test
		@DisplayName("A gather closed by the host disappears from the list")
		void hostCloseForgets() throws Exception {
			String src = read(MANAGER);
			int ended = src.indexOf("message.chestmemory.clan_ended");
			assertTrue(ended > 0, "session-ended path not found");
			assertTrue(
				src.substring(Math.max(0, ended - 700), ended).contains("ClanRoster.forget(code)"),
				"a gather the host closed must leave the switcher"
			);
		}

		@Test
		@DisplayName("Polling keeps the active gather's progress fresh")
		void pollRefreshesProgress() throws Exception {
			String src = read(MANAGER);
			assertTrue(
				src.contains("recordDeliveryDiffs(prev, session)")
					&& src.indexOf("ClanRoster.remember(", src.indexOf("recordDeliveryDiffs(prev, session)")) > 0,
				"the poll must refresh the switcher's numbers for the gather being followed"
			);
		}

		@Test
		@DisplayName("The remembered list is capped")
		void listIsCapped() throws Exception {
			assertTrue(
				read(ROSTER).contains("MAX_REMEMBERED"),
				"an abandoned gather must not grow the list forever"
			);
		}
	}

	@Nested
	@DisplayName("Switching gathers")
	class Switching {

		@Test
		@DisplayName("Switching hands over the warehouse, or the farm's chest glows in the house build")
		void switchClearsWarehouse() throws Exception {
			String body = read(MANAGER);
			int sw = body.indexOf("public static void switchToAsync(");
			assertTrue(sw > 0, "switchToAsync not found");
			String method = body.substring(sw, body.indexOf("\n\tpublic static", sw + 10));
			assertTrue(method.contains("clearStaging()"), "warehouse marks belong to one gather");
			// The feed is deliberately NOT cleared here any more. Clearing on every switch —
			// and a portal rejoin counts as one — meant activity was never readable, which is
			// what the user reported. Entries name the item and the player, so a few lines
			// carried over from the previous gather are harmless.
			assertTrue(
				!method.contains("ClanEventLog.clear()"),
				"clearing the feed on every switch is why activity never showed up"
			);
			assertTrue(method.contains("joinAsync("), "switching reuses join, which the hub treats as idempotent");
			assertTrue(
				method.contains("BuildGatherSession.clear()"),
				"the local queue belongs to the schematic being left behind"
			);
		}

		@Test
		@DisplayName("Switching to the gather already being followed does nothing")
		void switchToSameIsNoop() throws Exception {
			String body = read(MANAGER);
			int sw = body.indexOf("public static void switchToAsync(");
			String method = body.substring(sw, body.indexOf("\n\tpublic static", sw + 10));
			assertTrue(
				method.contains("equalsIgnoreCase(session.code)"),
				"re-following the current gather would be a pointless request"
			);
		}

		@Test
		@DisplayName("Only the active gather is polled")
		void onlyActiveIsPolled() throws Exception {
			// Polling every known gather would multiply request volume by their count, and the
			// hub counts a session poll in its tightest rate-limit bucket.
			String src = read(MANAGER);
			int poll = src.indexOf("private static void pollAsync(");
			assertTrue(poll > 0, "pollAsync not found");
			String method = src.substring(poll, src.indexOf("\n\tprivate static", poll + 10));
			assertTrue(
				method.contains("session.code") && !method.contains("ClanRoster.all()"),
				"pollAsync must follow the active session only"
			);
		}
	}

	@Nested
	@DisplayName("Gathers tab")
	class Ui {

		@Test
		@DisplayName("The tab exists and rows can be clicked to switch")
		void tabWired() throws Exception {
			String src = read(SCREEN);
			assertTrue(src.contains("TAB_LIST"), "a tab is needed to see the other gathers");
			assertTrue(src.contains("gatherAt("), "rows must be clickable");
			assertTrue(
				src.contains("ClanSessionManager.switchToAsync("),
				"clicking a row must switch to that gather"
			);
		}

		@Test
		@DisplayName("A second gather can be started without leaving the first")
		void canCreateMore() throws Exception {
			assertTrue(
				read(SCREEN).contains("screen.chestmemory.clan.create_more"),
				"the host has to be able to open another build while one is running"
			);
		}

		@Test
		@DisplayName("The list stops above its own buttons")
		void listDoesNotOverlapButtons() throws Exception {
			// The shared "panelH - 32" bottom runs underneath this tab's two button rows.
			String src = read(SCREEN);
			int draw = src.indexOf("private void drawGatherList(");
			assertTrue(draw > 0, "drawGatherList not found");
			String method = src.substring(draw, Math.min(src.length(), draw + 900));
			assertTrue(
				method.contains("panelH - 52"),
				"the list must end above the new-gather / join / back rows"
			);
		}

		@Test
		@DisplayName("Four tab labels still fit the strip")
		void tabLabelsFit() throws Exception {
			// 316px of content split four ways is 79px per tab, minus 8px padding.
			String ru = read("src/main/resources/assets/chestmemory/lang/ru_ru.json");
			for (String key : new String[] {"tab_gather", "tab_members", "tab_feed", "tab_list"}) {
				java.util.regex.Matcher m = java.util.regex.Pattern
					.compile("\"screen\\.chestmemory\\.clan\\." + key + "\":\\s*\"([^\"]*)\"")
					.matcher(ru);
				assertTrue(m.find(), "missing lang key: " + key);
				String text = m.group(1);
				assertTrue(
					text.length() * 6 <= 71,
					"tab label too wide: '" + text + "' ≈ " + (text.length() * 6) + "px of 71"
				);
			}
		}
	}
}
