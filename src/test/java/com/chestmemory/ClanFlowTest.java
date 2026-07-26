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
			//
			// The reset now happens when the new gather ARRIVES, not when the switch is
			// requested: doing it up front left a slow or failed switch with no gather at all,
			// which is what made switching feel like a freeze followed by a jump. This test
			// asks that the queue is dropped on a real change of gather, wherever that lives.
			String src = read(CLAN);
			assertTrue(
				src.contains("if (differentGather) {")
					&& src.contains("BuildGatherSession.clear()"),
				"the queue belongs to the schematic being left behind"
			);
			assertTrue(
				src.contains("boolean differentGather = previous != null")
					&& src.contains("!previous.code.equalsIgnoreCase(res.value.code)"),
				"and it must only be dropped when the gather actually changed — re-joining the "
					+ "same one after a portal must not wipe the queue"
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
			// Handled on the join success path, which switching goes through — so this checks
			// the behaviour rather than the line's location.
			String src = read(CLAN);
			int join = src.indexOf("public static void joinAsync(");
			String body = src.substring(join, src.indexOf("\n\tpublic ", join + 10));
			assertTrue(body.contains("clearStaging()"), "one gather's drop-off is not another's");
			assertTrue(
				body.contains("applyClanStagingKeys(session)"),
				"and the new gather's own warehouse has to be adopted"
			);
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
			// The guard moved into ScrollList when the lists gained scrolling — it has to be
			// there now, because the offset means the screen can no longer do this maths.
			String scroll = read("src/client/java/com/chestmemory/client/gui/ScrollList.java");
			int decl = scroll.indexOf("public int rowAt(");
			assertTrue(decl > 0, "ScrollList.rowAt is missing");
			String body = scroll.substring(decl, scroll.indexOf("\n\t}", decl));
			assertTrue(
				body.contains("mouseY < top"),
				"without this guard the tab strip is swallowed by the gather list"
			);
			assertTrue(
				read(CLAN_SCREEN).contains("this.gatherScroll.rowAt("),
				"the screen must delegate, not redo the maths against a scrolled list"
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

	@Nested
	@DisplayName("Each gather has its own warehouse")
	class PerGatherWarehouse {

		@Test
		@DisplayName("Creating a gather does not upload the previous build's chest")
		void createStartsWithNoWarehouse() throws Exception {
			// Reported: "на все схемы назначает один склад". createAsync pushed the local marks
			// into the fresh session with replace=true, so the farm's drop-off became the
			// house's.
			String body = methodBody(read(CLAN), "public static void createAsync(");
			assertTrue(
				!body.contains("pushStagingKeysAsync(mc, true)"),
				"a new gather must not inherit the previous one's warehouse"
			);
			assertTrue(body.contains("clearStaging()"), "and must start from a clean slate");
		}

		@Test
		@DisplayName("Joining adopts the gather's warehouse instead of pushing your own")
		void joinAdoptsWarehouse() throws Exception {
			String body = methodBody(read(CLAN), "public static void joinAsync(");
			assertTrue(
				!body.contains("pushStagingKeysAsync(mc, false)"),
				"merging local marks into someone else's gather is how one chest leaked everywhere"
			);
			assertTrue(body.contains("applyClanStagingKeys("), "the session's own marks are adopted");
		}

		@Test
		@DisplayName("Replacing a session stops marking quietly, or the old chest is uploaded anyway")
		void quietStopWhenReplacingSession() throws Exception {
			// stop(false) syncs the full local warehouse to the active session. Calling it while
			// swapping sessions uploaded the previous build's chest into the new gather — the fix
			// would have looked right locally and been wrong on the hub.
			String staging = read("src/client/java/com/chestmemory/client/data/StagingPickMode.java");
			assertTrue(staging.contains("public static void stopQuiet()"), "a non-syncing stop is needed");
			int quiet = staging.indexOf("public static void stopQuiet()");
			String quietBody = staging.substring(quiet, staging.indexOf("\n\tpublic static void stop(", quiet));
			assertTrue(
				!quietBody.contains("pushStagingKeysAsync"),
				"stopQuiet must not sync to the hub"
			);

			String clan = read(CLAN);
			for (String method : new String[] {"createAsync(", "joinAsync(", "switchToAsync("}) {
				String body = methodBody(clan, "public static void " + method);
				assertTrue(
					!body.contains("StagingPickMode.stop(false)"),
					method + " must use stopQuiet, otherwise the old warehouse is uploaded"
				);
			}
		}

		@Test
		@DisplayName("Marking a chest still shares it with the clan")
		void markingStillSyncs() throws Exception {
			// The point of the shared warehouse: everyone glows the same drop-off. Only the
			// session-swap paths went quiet; finishing a marking run still uploads.
			String staging = read("src/client/java/com/chestmemory/client/data/StagingPickMode.java");
			assertTrue(
				staging.contains("pushStagingKeysAsync"),
				"marking must still reach the hub, or the clan cannot see the drop-off"
			);
		}
	}

	@Nested
	@DisplayName("The gather button stays usable")
	class ButtonStaysAlive {

		@Test
		@DisplayName("Finishing a gather does not throw away what the schematic needs")
		void cacheOutlivesGather() throws Exception {
			// Reported: after a world change the «Сбор» button was dead. hasActiveMaterialList()
			// falls back to the cached list, the cache was dropped when a gather ended, and
			// Litematica only recreates its own list when the player opens it by hand — so
			// neither side had one and the button could not be pressed again.
			String body = methodBody(
				read("src/client/java/com/chestmemory/client/litematica/MaterialListCache.java"),
				"public static void setArmed(boolean on)"
			);
			assertTrue(
				!body.contains("clear()"),
				"disarming must keep the copy, or the gather cannot be restarted"
			);
		}

		@Test
		@DisplayName("Serving the copy no longer requires an active gather")
		void servesWithoutArmed() throws Exception {
			String src = read("src/client/java/com/chestmemory/client/litematica/MaterialListCache.java");
			int r = src.indexOf("public static List<LitematicaCompat.MaterialNeed> resolve(\n\t\tList");
			assertTrue(r > 0, "resolve not found");
			assertTrue(
				!src.substring(r).contains("if (!armed || cached == null)"),
				"the copy has to be served after a gather ends too"
			);
		}
	}

	@Nested
	@DisplayName("Delivering a claimed item")
	class Delivery {

		@Test
		@DisplayName("The hand-in is reported at once, not on the 10s tick")
		void deliveryReportedImmediately() throws Exception {
			// Reported: "сдал на склад, но мне не засчитало". The target switched the instant the
			// warehouse covered the need, while the hub only heard about it up to 10s later.
			String session = read("src/client/java/com/chestmemory/client/litematica/BuildGatherSession.java");
			assertTrue(
				session.contains("ClanSessionManager.reportStagedNow("),
				"finishing an item must push the delivery straight away"
			);
			String clan = read(CLAN);
			assertTrue(clan.contains("public static void reportStagedNow("), "the immediate path must exist");
		}

		@Test
		@DisplayName("Finishing a claimed item does not jump to something you never picked")
		void noAutoJumpAfterClaim() throws Exception {
			// Reported: "автоматом перекидывает на новый предмет хотя я не выбирал его".
			String session = read("src/client/java/com/chestmemory/client/litematica/BuildGatherSession.java");
			int tick = session.indexOf("gatherAutoAdvance()");
			assertTrue(tick > 0, "auto-advance block not found");
			String block = session.substring(tick, Math.min(session.length(), tick + 1800));
			assertTrue(
				block.contains("isClaimedByMe(") && block.contains("firstOwnClaim("),
				"auto-advance must stop instead of falling through to the ranking"
			);
		}
	}

	@Nested
	@DisplayName("Rejoining after a world change")
	class Resume {

		@Test
		@DisplayName("A failed rejoin keeps trying instead of losing the session")
		void failedResumeKeepsCode() throws Exception {
			// This is what kicked the player off their claim: pausedCode was cleared before the
			// answer, so one failed attempt meant no session, no heartbeat, and after
			// CLAIM_TIMEOUT_SEC the hub freed the claims and showed them as offline.
			String body = methodBody(read(CLAN), "private static void resumePausedAsync(");
			int ok = body.indexOf("res.ok && res.value != null");
			int firstClear = body.indexOf("pausedCode = null");
			assertTrue(ok > 0 && firstClear > ok, "the code must only be dropped after a definite answer");
			assertTrue(
				body.contains("res.isNotFound()"),
				"a gather that really ended should stop the retries"
			);
		}

		@Test
		@DisplayName("Retries are throttled — the tick runs 20 times a second")
		void resumeThrottled() throws Exception {
			String body = methodBody(read(CLAN), "private static void resumePausedAsync(");
			assertTrue(
				body.contains("lastResumeAttemptMillis"),
				"without a throttle an unreachable hub is hammered every tick"
			);
		}
	}

	@Nested
	@DisplayName("Reaching your gathers")
	class Reachability {

		@Test
		@DisplayName("The gather button is always clickable")
		void gatherButtonAlwaysClickable() throws Exception {
			// A clan member who relogged could not continue: Litematica had no material list
			// yet, so the only button that opens the materials view was disabled with no
			// explanation. Pressing it now reports why.
			String body = methodBody(read(SCREEN), "private void updateLitematicaButtonState()");
			assertTrue(
				body.contains("this.litematicaButton.active = true"),
				"the button must not be gated on having a list right now"
			);
		}

		@Test
		@DisplayName("The gathers list is reachable when not in a session")
		void listReachableOutsideSession() throws Exception {
			// Reported: after leaving one gather there was no way to pick another already joined.
			String src = read(CLAN_SCREEN);
			assertTrue(
				src.contains("drawGatherList(graphics, null, left, contentW)"),
				"the list has to render with no active gather"
			);
			int init = src.indexOf("boolean in = ClanSessionManager.isInSession();");
			assertTrue(init > 0, "init not found");
			String tail = src.substring(init, src.indexOf("if (!in) {", init));
			assertTrue(
				!tail.contains("this.tabsY = -1;"),
				"the tab strip must exist outside a session, or the list cannot be opened"
			);
		}
	}

	@Nested
	@DisplayName("Host rights")
	class HostRights {

		@Test
		@DisplayName("Only the creator sees the delete button, and it asks twice")
		void hostCanDelete() throws Exception {
			String src = read(CLAN_SCREEN);
			assertTrue(src.contains("ClanSessionManager.isHost("), "the button is host-only");
			assertTrue(src.contains("clan.delete_confirm"), "deleting ends the build for everyone");
			// Check the two-step guard in the button body, not just anywhere in the file: the
			// field name alone still matched after the arming logic was gutted.
			int del = src.indexOf("clan.delete_confirm");
			assertTrue(del > 0, "delete button not found");
			String around = src.substring(Math.max(0, del - 400), Math.min(src.length(), del + 1200));
			assertTrue(
				around.contains("if (!this.deleteArmed)") && around.contains("this.deleteArmed = true"),
				"a single misclick must not end a gather for everyone"
			);
		}

		@Test
		@DisplayName("The hub already enforces host-only close")
		void hubEnforcesHost() throws Exception {
			// Worth pinning: the client-side button is convenience, not the security boundary.
			String hub = read("hub/clan_hub.py");
			assertTrue(hub.contains("only host can close"), "the hub must reject a non-host close");
		}

		@Test
		@DisplayName("Delete labels fit the half-width row")
		void deleteLabelsFit() throws Exception {
			// 316px content, two columns with a 4px gap, minus padding.
			String ru = read("src/main/resources/assets/chestmemory/lang/ru_ru.json");
			for (String key : new String[] {"delete", "delete_confirm"}) {
				java.util.regex.Matcher m = java.util.regex.Pattern
					.compile("\"screen\\.chestmemory\\.clan\\." + key + "\":\\s*\"([^\"]*)\"")
					.matcher(ru);
				assertTrue(m.find(), "missing lang key: " + key);
				String text = m.group(1);
				assertTrue(
					text.length() * 6 <= 144,
					"label too wide: '" + text + "' ~" + (text.length() * 6) + "px of 144"
				);
			}
		}
	}
}
