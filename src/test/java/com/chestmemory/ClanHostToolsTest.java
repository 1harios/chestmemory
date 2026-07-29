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
	/** Shared grid palette — both item grids read their state colours from here. */
	private static final String STOCK_STYLE =
		"src/client/java/com/chestmemory/client/gui/ChestGuiStyle.java";

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
		@DisplayName("READY is green — GO — in both grids, and said in words on hover")
		void readyMarkExists() throws Exception {
			String src = read(CLAN_SCREEN);
			int clanBody = src.indexOf("private void drawClanGather");
			int soloBody = src.indexOf("private void drawSoloGather");
			// The palette lives in ChestGuiStyle now, so the grids name the state instead of
			// repeating a hex. Asserting on the name is what we actually care about — the two
			// grids used to drift apart (orange here, yellow there) precisely because each
			// carried its own literal, and a shared constant is what stops that recurring.
			assertTrue(
				src.indexOf("ChestGuiStyle.STOCK_READY", clanBody) > 0
					&& src.indexOf("ChestGuiStyle.STOCK_READY", soloBody) > 0,
				"both grids must mark items whose need is fully covered by chest stock"
			);
			assertTrue(
				read(STOCK_STYLE).contains("STOCK_READY = 0x4430E060"),
				"READY stays green — the colour moved, it did not change"
			);
			assertTrue(
				src.contains("screen.chestmemory.clan.mat_ready_hint")
					&& src.contains("screen.chestmemory.clan.solo_hover_ready"),
				"the hover line must say it in words, not only in colour"
			);
		}

		@Test
		@DisplayName("Hover carries live stock: chests, this world, nearest, backpack")
		void hoverCarriesStock() throws Exception {
			String src = read(CLAN_SCREEN);
			assertTrue(
				src.contains("screen.chestmemory.clan.hover_stock")
					&& src.contains("screen.chestmemory.clan.hover_stock_solo"),
				"the numbers a player gathers by must be one hover away"
			);
			// The grid asks per cell per frame; an uncached walk over every container
			// per cell is a stutter waiting to happen.
			assertTrue(
				src.contains("stockCacheAt > 500L"),
				"chest stock must be briefly cached"
			);
			assertTrue(
				read("src/client/java/com/chestmemory/client/litematica/BuildGatherSession.java")
					.contains("public static double nearestChestDistance("),
				"the nearest-chest distance helper must exist"
			);
		}

		@Test
		@DisplayName("The pencil in the corner is the settings entry, like the main screen's gear")
		void pencilIsTheSettingsEntry() throws Exception {
			String src = read(CLAN_SCREEN);
			assertTrue(src.contains("new PencilIconButton("), "the corner pencil is missing");
			assertTrue(
				src.contains("screen.chestmemory.clan.settings_icon_tip"),
				"an icon without a tooltip is a guessing game"
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
	@Nested
	@DisplayName("Round four: claim order, one-key entry, search, full colour coding")
	class GatherFlow {
		private static final String SESSION =
			"src/client/java/com/chestmemory/client/litematica/BuildGatherSession.java";
		private static final String CLIENT =
			"src/client/java/com/chestmemory/client/ChestMemoryClient.java";

		@Test
		@DisplayName("Claims are worked in click order: glass before wool because it was first")
		void claimOrderIsClickOrder() throws Exception {
			String manager = read(CLAN);
			assertTrue(
				manager.contains("myClaimOrder") && manager.contains("rememberClaimOrder(itemId, unclaim)"),
				"the manager must record the order claims were clicked in"
			);
			String session = read(SESSION);
			int decl = session.indexOf("private static @Nullable String firstOwnClaim");
			String body = session.substring(decl, session.indexOf("\n\t}", decl));
			assertTrue(
				body.indexOf("myClaimOrder") < body.indexOf("session.materials.entrySet()"),
				"click order must be consulted before the hub map's arbitrary order"
			);
		}

		@Test
		@DisplayName("A second claim queues; it does not steal the current target")
		void secondClaimDoesNotSteal() throws Exception {
			String src = read(CLAN_SCREEN);
			int decl = src.indexOf("private void claimFromList");
			String body = src.substring(decl, src.indexOf("\n\t}", decl));
			assertTrue(
				body.contains("keepCurrent"),
				"claiming wool while gathering glass must leave glass as the target"
			);
			assertTrue(
				body.contains("if (!keepCurrent) {"),
				"the pending focus must not be planted when the target is kept"
			);
		}

		@Test
		@DisplayName("Mid-gather, the panel key opens the gather screen directly")
		void panelKeyGoesToGather() throws Exception {
			String client = read(CLIENT);
			int decl = client.indexOf("while (openPanelKey.consumeClick())");
			String body = client.substring(decl, decl + 900);
			assertTrue(
				body.contains("BuildGatherSession.isActive()")
					&& body.contains("new com.chestmemory.client.gui.ClanGatherScreen(new ChestMemoryScreen())"),
				"reopening the panel and clicking «Сбор» every time was the complaint"
			);
			assertTrue(
				body.contains("open instanceof com.chestmemory.client.gui.ClanGatherScreen"),
				"the same key must close what it opened"
			);
		}

		@Test
		@DisplayName("Search filters the gather and appends memory matches, dimmed")
		void searchSpansGatherAndMemory() throws Exception {
			String src = read(CLAN_SCREEN);
			assertTrue(src.contains("private void addGatherSearch("), "search box missing");
			assertTrue(
				src.contains("private void appendExternalMatches("),
				"memory matches must be appended after the gather cells"
			);
			assertTrue(
				src.contains("externalHighlight(clicked)"),
				"clicking a memory match must glow its chests, not try to claim it"
			);
			assertTrue(
				src.contains("gatherIds.contains(sum.itemId())"),
				"items already in the gather must not appear twice"
			);
		}

		@Test
		@DisplayName("Every cell carries a stock colour; claims and the target ride the rim")
		void fullColourCoding() throws Exception {
			String src = read(CLAN_SCREEN);
			int clanBody = src.indexOf("private void drawClanGather");
			int soloBody = src.indexOf("private void drawSoloGather");
			// Traffic light in both modes: green GO, yellow partial, red none, done dims
			// with a green check — finished work retires instead of glowing like GO.
			for (int at : new int[]{clanBody, soloBody}) {
				String body = src.substring(at, src.indexOf("\n\t}", at));
				assertTrue(
					body.contains("ChestGuiStyle.STOCK_READY") && body.contains("ChestGuiStyle.STOCK_PARTIAL")
						&& body.contains("ChestGuiStyle.STOCK_NONE") && body.contains("ChestGuiStyle.STOCK_DONE"),
					"all four stock states need a colour"
				);
				assertTrue(body.contains("\"✓\""), "done cells carry the check badge");
			}
			// Both grids read one palette, so a retune can never desync them again.
			String style = read(STOCK_STYLE);
			assertTrue(
				style.contains("STOCK_READY = 0x4430E060") && style.contains("STOCK_PARTIAL = 0x44FFE040")
					&& style.contains("STOCK_NONE = 0x44E03030") && style.contains("STOCK_DONE = 0x99101010"),
				"the four traffic-light values live in one place"
			);
			assertTrue(
				src.contains("int border = mine ? 0xFFFFD56A : taken ? 0xFFB48CB4 : 0;")
					&& src.contains("int border = isFocus ? 0xFFFFD56A : 0;"),
				"people-state must ride the rim so it never fights the stock tint"
			);
			int grid = src.indexOf("private int drawMaterialGrid(");
			assertTrue(
				src.substring(grid, src.indexOf("\n\t}", grid)).contains("cell.border()"),
				"the grid must actually paint the ring"
			);
		}

		@Test
		@DisplayName("The pencil sits in the right corner, like the main screen's gear")
		void pencilOnTheRight() throws Exception {
			String src = read(CLAN_SCREEN);
			assertTrue(
				src.contains("this.panelLeft + this.panelW - 16 - 6, this.panelTop + 9, 16"),
				"the settings icon mirrors the gear's corner"
			);
		}
	}
	@Nested
	@DisplayName("Round five: cursor tooltips, the panel's filter, one-row join, readable grey")
	class PolishRound {
		private static final String SESSION =
			"src/client/java/com/chestmemory/client/litematica/BuildGatherSession.java";
		private static final String STYLE =
			"src/client/java/com/chestmemory/client/gui/ChestGuiStyle.java";

		@Test
		@DisplayName("The hovered cell explains itself in a vanilla tooltip, not a bottom strip")
		void hoverIsATooltip() throws Exception {
			String src = read(CLAN_SCREEN);
			assertTrue(
				src.contains("setTooltipForNextFrame("),
				"the tooltip is the panel's own reading gesture — the gather must match it"
			);
			for (String m : new String[]{
				"clanCellTooltip", "soloCellTooltip", "externalCellTooltip", "tooltipHead"
			}) {
				assertTrue(src.contains(m), "missing tooltip builder: " + m);
			}
			assertTrue(
				src.contains("this.gridBottom = row2 - 16;"),
				"with the facts on the cursor, the bottom strip keeps only the legend line"
			);
		}

		@Test
		@DisplayName("The gather honours the panel filter: nearby means nearby")
		void gatherHonoursPanelFilter() throws Exception {
			String session = read(SESSION);
			assertTrue(
				session.contains("private static List<ContainerRecord> filteredSources(")
					&& session.contains("filterScope()") && session.contains("filterRange()"),
				"«Рядом ≤256м» on the panel is a promise the gather must keep"
			);
			int route = session.indexOf("private static void highlightCurrent");
			String body = session.substring(route, session.indexOf("\n\t}", route));
			assertTrue(
				body.contains("filteredSources(currentItemId)"),
				"routes must not lead to chests the filter hides"
			);
			int count = session.indexOf("public static int countInChestsLive(String itemId) {");
			String countBody = session.substring(count, session.indexOf("\n\t}", count));
			assertTrue(
				countBody.contains("filteredSources(itemId)"),
				"the counts the gather shows must match what it will route to"
			);
		}

		@Test
		@DisplayName("The gather search behaves like the main panel's")
		void searchLikeTheMainPanel() throws Exception {
			String src = read(CLAN_SCREEN);
			assertTrue(
				src.contains("this.font, left, y, w, 18,"),
				"same height as the panel's search row"
			);
			int typed = src.indexOf("public boolean charTyped");
			assertTrue(typed > 0, "type-to-search is missing");
			String body = src.substring(typed, src.indexOf("\n\t}", typed));
			assertTrue(
				body.contains("gatherSearchBox.charTyped(event)"),
				"typing anywhere on the tab must land in the search box"
			);
		}

		@Test
		@DisplayName("Joining reads left to right in one row; the list rows carry mini bars")
		void listTabLayout() throws Exception {
			String src = read(CLAN_SCREEN);
			assertTrue(
				src.contains("int codeW = w - pasteW - joinW - 2 * gap;"),
				"код → вставить → вступить, one row — the join button sat a row below its box"
			);
			int list = src.indexOf("private void drawGatherList");
			String body = src.substring(list, src.indexOf("\n\t}", list));
			assertTrue(
				body.contains("ChestGuiStyle.drawProgressBar("),
				"a number said 34%; the bar shows a third at a glance"
			);
		}

		@Test
		@DisplayName("Shift-click peeks: glow the chests, claim nothing")
		void shiftClickPeeks() throws Exception {
			String src = read(CLAN_SCREEN);
			assertTrue(
				src.contains("event.hasShiftDown()"),
				"the modifier rides the click event in these mappings"
			);
		}

		@Test
		@DisplayName("Muted text is dark enough to read on the light panel")
		void mutedTextReadable() throws Exception {
			String style = read(STYLE);
			assertTrue(
				style.contains("TEXT_MUTED = 0xFF525252"),
				"0xFF6E6E6E on 0xFFC6C6C6 was ~2.9:1 — under the 4.5:1 floor"
			);
			assertTrue(
				style.contains("TEXT_ON_WOOD_MUTED = 0xFFE2E2E2"),
				"the wood-row secondary column had the same problem"
			);
		}
	}
	@Nested
	@DisplayName("Round six: scan order, stock detail, and two claim bugs")
	class ClaimPolish {
		private static final String SESSION =
			"src/client/java/com/chestmemory/client/litematica/BuildGatherSession.java";
		private static final String GRID =
			"src/client/java/com/chestmemory/client/gui/ItemGridWidget.java";

		@Test
		@DisplayName("The clan grid scans ready → partial → none → done")
		void bandOrder() throws Exception {
			String src = read(CLAN_SCREEN);
			assertTrue(src.contains("private int clanBand("), "the band classifier is missing");
			int sort = src.indexOf("rows.sort((a, b) -> {");
			String body = src.substring(sort, sort + 400);
			assertTrue(
				body.contains("clanBand(s, a.getKey())"),
				"the sort must lead with the stock band, remainder second"
			);
		}

		@Test
		@DisplayName("Tooltips carry full stacks and shulker contents")
		void tooltipStockDetail() throws Exception {
			String src = read(CLAN_SCREEN);
			assertTrue(src.contains("private void addStockDetail("), "stock detail missing");
			assertTrue(
				src.contains("tooltip.gather_stacks") && src.contains("tooltip.gather_shulkers"),
				"stacks and shulkers must be one hover away"
			);
			assertTrue(
				src.contains("WorldBreakdown.shulkerCount("),
				"shulker counts come from the shared breakdown, chests included"
			);
			// 27 slots to a box: 1728 of a 64-stack item IS one shulker. The equivalence
			// respects real stack sizes — 432 pearls, 27 tools.
			assertTrue(
				src.contains("int boxCap = per * 27;")
					&& src.contains("tooltip.gather_boxes"),
				"big stock must be readable as shulker boxes"
			);
		}

		@Test
		@DisplayName("The main list never promises a claim on an item outside the gather")
		void mainListClaimGated() throws Exception {
			String grid = read(GRID);
			assertTrue(
				grid.contains("ClanSessionManager.isInActiveGather(s.itemId())"),
				"«Клан: свободно — клик = взять» on a random remembered item was a lie"
			);
		}

		@Test
		@DisplayName("Releasing a claim refocuses only after the hub confirms")
		void unclaimRefocusAfterConfirm() throws Exception {
			String src = read(CLAN_SCREEN);
			int mine = src.indexOf("// Giving the item back also drops it as the gather target");
			assertTrue(mine > 0);
			String body = src.substring(mine, mine + 700);
			int toggleAt = body.indexOf("claimToggleAsync(mc, itemId, () -> {");
			int dropAt = body.indexOf("dropCurrentClaimFocus(mc)");
			assertTrue(
				toggleAt >= 0 && dropAt > toggleAt,
				"refocusing before the hub answers reads the stale session and re-picks "
					+ "the item that was just released"
			);
		}

		@Test
		@DisplayName("Dropping the target moves to the next OWN claim, or goes idle and says so")
		void dropIsClanAware() throws Exception {
			String session = read(SESSION);
			int drop = session.indexOf("public static void dropCurrentClaimFocus");
			String body = session.substring(drop, session.indexOf("\n\t}", drop));
			assertTrue(
				body.contains("firstOwnClaim(client, null)"),
				"in a clan the ranking must not pick the next target"
			);
			assertTrue(
				body.contains("clan_no_target"),
				"an idle gather must say it went idle, not stay silently on the old glow"
			);
		}
	}
	@Nested
	@DisplayName("The ender chest is with you — never metres away")
	class EnderIsPersonal {
		private static final String STORAGE =
			"src/client/java/com/chestmemory/client/data/ChestMemoryStorage.java";
		private static final String SESSION =
			"src/client/java/com/chestmemory/client/litematica/BuildGatherSession.java";
		private static final String GRID =
			"src/client/java/com/chestmemory/client/gui/ItemGridWidget.java";

		@Test
		@DisplayName("distanceTo answers 0 for the ender chest, glow position or not")
		void enderDistanceIsZero() throws Exception {
			String storage = read(STORAGE);
			int at = storage.indexOf("reachable from ANY ender chest");
			assertTrue(at > 0, "the rationale comment anchors the rule");
			String around = storage.substring(at, at + 400);
			assertTrue(
				around.contains("\"ender_chest\".equals(record.virtualId())")
					&& around.contains("return 0;")
					&& !around.contains("hasHighlightPos()"),
				"the old code fell through to metres when a glow position was remembered"
			);
		}

		@Test
		@DisplayName("«До ближайшего» measures world chests only, everywhere")
		void nearestIsWorldChestsOnly() throws Exception {
			assertTrue(
				read(STORAGE).contains("if (dist >= 0 && !record.isVirtual()) {"),
				"the list aggregation must not let a personal record win 'nearest'"
			);
			String grid = read(GRID);
			assertTrue(
				grid.contains("tooltip.containers_none"),
				"all-personal stock says so instead of showing metres"
			);
			String session = read(SESSION);
			int near = session.indexOf("private static double nearestLiveDist");
			String body = session.substring(near, session.indexOf("\n\t}", near));
			assertTrue(
				body.contains("if (r.isVirtual())"),
				"gather distances must skip personal records too"
			);
		}

		@Test
		@DisplayName("Routes and chest stock are world chests; ender rides its own line")
		void routesSkipEnder() throws Exception {
			String session = read(SESSION);
			int filtered = session.indexOf("private static List<ContainerRecord> filteredSources");
			String body = session.substring(filtered, session.indexOf("\n\t}", filtered));
			assertTrue(
				body.contains("if (!r.isVirtual())"),
				"a route stop at 'the ender chest you once opened 300м away' is nonsense"
			);
			assertTrue(
				read(CLAN_SCREEN).contains("WorldBreakdown.enderCount("),
				"ender holdings must stay visible — on the tooltip's own ender line"
			);
		}

		@Test
		@DisplayName("Chat never reports metres to a virtual record")
		void chatSkipsVirtuals() throws Exception {
			String panel = read(PANEL);
			assertTrue(
				panel.contains(".filter(r -> r.isWorldBlock())"),
				"the nearest-chest chat line must not point at the remembered ender spot"
			);
		}
	}
}
