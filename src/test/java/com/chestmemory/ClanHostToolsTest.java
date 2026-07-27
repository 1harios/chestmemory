package com.chestmemory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The host's tools and the single gather entry.
 * <p>
 * This round merged the panel's schematic mode into the gather screen (one «Сбор», not
 * two), moved warehouse assignment there, and gave the host real controls: rename, kick,
 * release-all-claims — each backed by a hub endpoint that checks who is asking.
 */
class ClanHostToolsTest {
	private static String read(String path) throws Exception {
		return Files.readString(Path.of(path));
	}

	private static final String CLAN_SCREEN =
		"src/client/java/com/chestmemory/client/gui/ClanGatherScreen.java";
	private static final String PANEL =
		"src/client/java/com/chestmemory/client/gui/ChestMemoryScreen.java";
	private static final String CLAN =
		"src/client/java/com/chestmemory/client/clan/ClanSessionManager.java";
	private static final String HUB_CLIENT =
		"src/client/java/com/chestmemory/client/clan/ClanHubClient.java";
	private static final String HUB = "hub/clan_hub.py";

	@Nested
	@DisplayName("The hub grew host-only commands")
	class HubSide {
		@Test
		@DisplayName("update / kick / release_claims are routed and guarded by one host check")
		void hostActionsExist() throws Exception {
			String hub = read(HUB);
			for (String h : new String[]{"def _update(", "def _kick(", "def _release_claims("}) {
				assertTrue(hub.contains(h), "missing handler: " + h);
			}
			for (String r : new String[]{
				"action == \"update\"", "action == \"kick\"", "action == \"release_claims\""
			}) {
				assertTrue(hub.contains(r), "missing route: " + r);
			}
			// The absent-uuid hole _close once had applies to every host action, so the
			// check lives in exactly one place.
			assertTrue(hub.contains("def _host_session("), "shared host check missing");
			assertTrue(
				hub.contains("host cannot kick self"),
				"a session without a host is a session nobody can ever close"
			);
		}

		@Test
		@DisplayName("A kicked member stays out: the heartbeat cannot re-add them")
		void kickedStaysOut() throws Exception {
			String hub = read(HUB);
			int upsert = hub.indexOf("def _member_upsert(");
			assertTrue(upsert > 0);
			String body = hub.substring(upsert, hub.indexOf("\nclass ", upsert));
			assertTrue(
				body.contains("kicked"),
				"every poll upserts the member — without this check a kick lasted three seconds"
			);
			// A deliberate re-join by code is allowed and lifts the flag: kicks are a
			// moderation tool, not a permanent ban list nobody can edit.
			int join = hub.indexOf("def _join(");
			String joinBody = hub.substring(join, hub.indexOf("def _claim(", join));
			assertTrue(joinBody.contains("kicked"), "join must lift the kicked flag");
		}

		@Test
		@DisplayName("The client has a call per command, and the manager guards each with busy")
		void clientSide() throws Exception {
			String client = read(HUB_CLIENT);
			for (String m : new String[]{"/update\"", "/kick\"", "/release_claims\""}) {
				assertTrue(client.contains(m), "missing client call: " + m);
			}
			String manager = read(CLAN);
			for (String m : new String[]{
				"public static void renameAsync(", "public static void kickAsync(",
				"public static void releaseClaimsAsync("
			}) {
				assertTrue(manager.contains(m), "missing manager method: " + m);
			}
		}

		@Test
		@DisplayName("A kicked player's own client notices and leaves cleanly")
		void kickedYouDetected() throws Exception {
			String manager = read(CLAN);
			assertTrue(
				manager.contains("containsMember("),
				"the poll must check the roster still lists this player"
			);
			assertTrue(
				manager.contains("clan_kicked_you"),
				"silently polling a gather you were removed from explains nothing"
			);
		}
	}

	@Nested
	@DisplayName("Host settings on the gather screen")
	class SettingsView {
		@Test
		@DisplayName("Rename, release-all and close live in one host-only view")
		void settingsExist() throws Exception {
			String src = read(CLAN_SCREEN);
			assertTrue(src.contains("private void initHostSettings("), "settings view missing");
			assertTrue(src.contains("ClanSessionManager.renameAsync("), "rename not wired");
			assertTrue(src.contains("ClanSessionManager.releaseClaimsAsync("), "release-all not wired");
			// Closing the session moved off the everyday row: ending the build for everyone
			// must not be one misclick from «Копировать код».
			int settings = src.indexOf("private void initHostSettings(");
			String body = src.substring(settings, src.indexOf("\n\t}", settings));
			assertTrue(
				body.contains("ClanSessionManager.leaveAsync("),
				"the settings view owns the close action now"
			);
		}

		@Test
		@DisplayName("Both destructive rows arm on the first click")
		void destructivesArmFirst() throws Exception {
			String src = read(CLAN_SCREEN);
			assertTrue(
				src.contains("this.releaseArmed = true") && src.contains("this.closeArmed = true"),
				"someone's evening of mining hangs off these — ask twice"
			);
		}

		@Test
		@DisplayName("The host kicks from the Members tab, with a two-click arm")
		void kickFromMembersTab() throws Exception {
			String src = read(CLAN_SCREEN);
			int click = src.indexOf("public boolean mouseClicked");
			String body = src.substring(click, src.indexOf("\n\t}", click));
			assertTrue(
				body.contains("ClanSessionManager.kickAsync("),
				"the roster is already on screen — kicking happens there, not in a submenu"
			);
			assertTrue(body.contains("kickArmUuid"), "a roster row is too easy to hit for one-click removal");
			assertTrue(
				body.contains("!target.uuid.equalsIgnoreCase(me)"),
				"the host's own row must not be kickable"
			);
		}
	}

	@Nested
	@DisplayName("The warehouse is assigned from the gather screen")
	class Warehouse {
		@Test
		@DisplayName("Assign closes the screen into pick mode; clear syncs the empty list")
		void stagingOnGatherScreen() throws Exception {
			String src = read(CLAN_SCREEN);
			int toggle = src.indexOf("private void toggleStagingPick()");
			assertTrue(toggle > 0, "staging toggle missing");
			String body = src.substring(toggle, src.indexOf("\n\t}", toggle));
			assertTrue(
				body.contains("this.onClose()"),
				"the chests to mark stand in the world — the screen must get out of the way"
			);
			assertTrue(
				src.contains("StagingPickMode.toggle()"),
				"the screen drives the same pick mode the scanner listens to"
			);
		}

		@Test
		@DisplayName("The panel lost its scheme tools — the gather screen is the only home")
		void panelHasNoStagingLeft() throws Exception {
			String panel = read(PANEL);
			assertFalse(
				panel.contains("StagingPickMode"),
				"warehouse buttons on the panel would duplicate the gather screen's"
			);
			assertFalse(
				panel.contains("buildPanelList"),
				"the panel shows chest memory; the schematic grid lives on the gather screen"
			);
			assertFalse(panel.contains("clanBtnW"), "the 52px header gather button is gone");
		}
	}
}
