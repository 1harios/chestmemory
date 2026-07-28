package com.chestmemory;

import com.chestmemory.client.gui.ScrollList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The clan screen was information-poor: the main tab showed a bar and two sentences, no tab
 * showed a single item icon, no list scrolled, and reserving a material meant leaving the
 * screen entirely. This covers the rebuild.
 */
class ClanRedesignTest {
	private static String read(String path) throws Exception {
		return Files.readString(Path.of(path));
	}

	private static final String CLAN_SCREEN =
		"src/client/java/com/chestmemory/client/gui/ClanGatherScreen.java";
	private static final String RU =
		"src/main/resources/assets/chestmemory/lang/ru_ru.json";

	private static int px(String text) {
		return text.length() * 6;
	}

	private static String lang(String key) throws Exception {
		var m = java.util.regex.Pattern
			.compile("\"" + java.util.regex.Pattern.quote(key) + "\"\\s*:\\s*\"([^\"]*)\"")
			.matcher(read(RU));
		assertTrue(m.find(), "missing translation: " + key);
		return m.group(1);
	}

	@Nested
	@DisplayName("Lists scroll instead of stopping at the panel edge")
	class Scrolling {
		private ScrollList list(int total) {
			ScrollList l = new ScrollList();
			// 180px of body at 22px per row — the real geometry of a tab in a session.
			l.layout(12, 90, 316, 270, 22, total);
			return l;
		}

		@Test
		@DisplayName("A short list does not scroll and uses the full row width")
		void shortListIsStatic() {
			ScrollList l = list(3);
			assertFalse(l.canScroll());
			assertEquals(0, l.maxScroll());
			assertEquals(316, l.rowWidth(), "no scrollbar means no reserved strip");
			assertEquals(0, l.firstVisible());
			assertEquals(3, l.lastVisible());
		}

		@Test
		@DisplayName("A long list scrolls and leaves room for the scrollbar")
		void longListScrolls() {
			ScrollList l = list(30);
			assertTrue(l.canScroll());
			assertEquals(8, l.visibleRows(), "180px / 22px");
			assertEquals(22, l.maxScroll(), "30 rows, 8 visible");
			assertEquals(310, l.rowWidth(), "6px reserved so text does not run under the bar");
		}

		@Test
		@DisplayName("The wheel moves one row and stops at both ends")
		void wheelClamps() {
			ScrollList l = list(30);
			assertFalse(l.scrolled(20, 100, 1), "already at the top");
			assertTrue(l.scrolled(20, 100, -1));
			assertEquals(1, l.firstVisible());
			for (int i = 0; i < 50; i++) {
				l.scrolled(20, 100, -1);
			}
			assertEquals(22, l.firstVisible(), "must not scroll past the end");
			assertFalse(l.scrolled(20, 100, -1), "nothing left to scroll");
		}

		@Test
		@DisplayName("The wheel is ignored outside the list")
		void wheelIgnoredOutside() {
			ScrollList l = list(30);
			assertFalse(l.scrolled(20, 50, -1), "above the list");
			assertFalse(l.scrolled(20, 280, -1), "below the list");
			assertFalse(l.scrolled(400, 100, -1), "to the right of it");
		}

		@Test
		@DisplayName("A click maps to the row under it, offset included")
		void hitTestFollowsTheOffset() {
			ScrollList l = list(30);
			assertEquals(0, l.rowAt(20, 91, 20), "first row");
			assertEquals(1, l.rowAt(20, 113, 20), "second row");
			l.scrolled(20, 100, -1);
			l.scrolled(20, 100, -1);
			assertEquals(2, l.rowAt(20, 91, 20),
				"scrolled by two, so the top row is index 2 — computing this in the screen "
					+ "against a visible-only list is what would map clicks to the wrong item");
		}

		@Test
		@DisplayName("Clicks on the tab strip above the list are not swallowed")
		void aboveTheListIsNotRowZero() {
			ScrollList l = list(30);
			assertEquals(-1, l.rowAt(20, 80, 20), "(int) of a negative offset is 0 in Java");
			assertEquals(-1, l.rowAt(20, 271, 20), "below the list");
		}

		@Test
		@DisplayName("The 2px seam between rows is dead space")
		void seamIsDead() {
			ScrollList l = list(30);
			// Rows are 20px of body inside a 22px pitch.
			assertEquals(0, l.rowAt(20, 109, 20), "last pixel of the row body");
			assertEquals(-1, l.rowAt(20, 111, 20), "the seam must not hit a row");
		}

		@Test
		@DisplayName("An offset past the end is clamped when the list shrinks")
		void shrinkingListClampsTheOffset() {
			ScrollList l = list(30);
			for (int i = 0; i < 22; i++) {
				l.scrolled(20, 100, -1);
			}
			assertEquals(22, l.firstVisible());
			// A gather ends, a material is finished: the list gets shorter under the view.
			l.layout(12, 90, 316, 270, 22, 10);
			assertEquals(2, l.firstVisible(), "the view must not be stranded past the end");
			assertEquals(10, l.lastVisible());
		}

		@Test
		@DisplayName("An empty list hit-tests to nothing")
		void emptyListIsSafe() {
			ScrollList l = list(0);
			assertFalse(l.canScroll());
			assertEquals(-1, l.rowAt(20, 91, 20));
			assertEquals(0, l.lastVisible());
		}
	}

	@Nested
	@DisplayName("The screen finally shows what the build needs")
	class MaterialsTab {
		@Test
		@DisplayName("The grid lives in the gather tab; the Materials tab is gone")
		void gridLivesInTheGatherTab() throws Exception {
			String src = read(CLAN_SCREEN);
			assertFalse(src.contains("TAB_MATERIALS"), "the merged tab must not keep the old id");
			assertTrue(
				src.contains("default -> drawGatherBody"),
				"the gather tab must draw the working body"
			);
			assertTrue(
				src.contains("private int drawMaterialGrid("),
				"the grid renderer must exist — it is the tab now"
			);
			assertFalse(read(RU).contains("tab_materials"), "no stray caption may remain");
		}

		@Test
		@DisplayName("Items are drawn as icons, not only as names")
		void itemsHaveIcons() throws Exception {
			String src = read(CLAN_SCREEN);
			assertTrue(
				src.contains("graphics.item(icon("),
				"not one item icon appeared anywhere on this screen before"
			);
			assertTrue(
				src.contains("this.iconCache.computeIfAbsent"),
				"resolving a stack hits the registry, so it must be cached"
			);
		}

		@Test
		@DisplayName("Materials scan ready → partial → none, done last, remainder inside")
		void sortedByWhatMatters() throws Exception {
			String src = read(CLAN_SCREEN);
			int decl = src.indexOf("private void drawClanGather");
			String body = src.substring(decl, src.indexOf("\n\t}", decl));
			assertTrue(
				body.contains("rows.sort(") && body.contains("clanBand(s, a.getKey())"),
				"the stock band leads the sort; hub order is not useful order"
			);
			assertTrue(
				body.contains("s.remaining(b.getKey()), s.remaining(a.getKey())"),
				"inside a band the biggest remainder comes first"
			);
		}

		@Test
		@DisplayName("A material can be reserved without leaving the screen")
		void claimFromTheList() throws Exception {
			String src = read(CLAN_SCREEN);
			assertTrue(src.contains("private void claimFromList("), "claiming is missing");
			int decl = src.indexOf("private void claimFromList(");
			String body = src.substring(decl, src.indexOf("\n\t}", decl));
			assertTrue(
				body.contains("s.remaining(itemId) <= 0"),
				"a finished item must not be claimable"
			);
			assertTrue(
				body.contains("!m.claimedBy.equals(me)"),
				"someone else's claim must be refused locally, with their name"
			);
			assertTrue(
				body.contains("claimToggleAsync"),
				"clicking your own claim has to give it up again"
			);
		}

		@Test
		@DisplayName("The icon cache is dropped when the gather changes")
		void cacheIsScopedToTheGather() throws Exception {
			String src = read(CLAN_SCREEN);
			assertTrue(
				src.contains("this.iconCache.clear()") && src.contains("this.materialScroll.reset()"),
				"a different gather has different items; keeping the offset would drop the "
					+ "player into the middle of a list they have not seen"
			);
		}
	}

	@Nested
	@DisplayName("The summary tab says something")
	class SummaryTab {
		@Test
		@DisplayName("It names the build being gathered")
		void showsTheSchematic() throws Exception {
			String src = read(CLAN_SCREEN);
			int decl = src.indexOf("private void drawClanInfo");
			assertTrue(decl > 0, "the info tab body is missing");
			String body = src.substring(decl, src.indexOf("\n\t}", decl));
			assertTrue(
				body.contains("s.schemaName"),
				"two gathers looked identical apart from their code"
			);
		}

		@Test
		@DisplayName("The facts live on the Info tab, drawn by a cursor that cannot overlap")
		void factsCannotOverlap() throws Exception {
			// The original bug: two detail rows drew on the same y — «Склад» under «В сети».
			// The info rows advance their own cursor (return y + 12), so a forgotten
			// increment is impossible by construction, not by discipline.
			String src = read(CLAN_SCREEN);
			assertFalse(
				src.contains("ChestGuiStyle.drawDetailRow("),
				"the old manually-advanced rows must stay gone"
			);
			assertTrue(
				src.contains("private int infoRow(") && src.contains("return y + 12;"),
				"info rows must advance their own cursor"
			);
			for (String key : new String[]{
				"info_host", "info_updated", "info_warehouse", "info_last_delivery"
			}) {
				assertTrue(src.contains("screen.chestmemory.clan." + key), "missing: " + key);
			}
		}

		@Test
		@DisplayName("The idle caption carries the numbers: items, done, free, online")
		void legendCarriesTheNumbers() throws Exception {
			String src = read(CLAN_SCREEN);
			int decl = src.indexOf("private void drawClanGather");
			String body = src.substring(decl, src.indexOf("\n\t}", decl));
			assertTrue(
				body.contains("screen.chestmemory.clan.legend"),
				"with the grid in the tab, one line replaces six detail rows"
			);
			assertTrue(
				body.contains("isMemberAway"),
				"the online count must use hub-clock away detection, not wall clocks"
			);
		}

		@Test
		@DisplayName("Measured: the grid owns the tab — six rows fit at the smallest height")
		void gatherTabFits() {
			int panelH = 300;
			int tabsY = 36 + 8;              // header only: the hub strip became a lamp
			int gridTop = tabsY + 22;        // no plate, no bar, no meta — all on Инфо
			int row2 = panelH - 26;
			int row0 = row2 - 2 * (18 + 4);  // warehouse row above the two session rows
			int gridBottom = row0 - 34;      // three hover lines above the buttons
			assertTrue(
				gridTop + 6 + 6 * 18 <= gridBottom,
				"six rows of slots must fit at the smallest panel height"
			);
		}

		@Test
		@DisplayName("Measured: both idle captions fit the panel width")
		void legendsFit() throws Exception {
			int content = 340 - 24;
			String clan = lang("screen.chestmemory.clan.legend").replace("%s", "88");
			assertTrue(px(clan) <= content, "clan legend clipped: " + clan);
			String solo = lang("screen.chestmemory.clan.solo_legend").replace("%s", "888");
			assertTrue(px(solo) <= content, "solo legend clipped: " + solo);
		}
	}

	@Nested
	@DisplayName("The screen is built from the chest panel's own pieces")
	class MatchesTheMainScreen {
		private static final String STYLE =
			"src/client/java/com/chestmemory/client/gui/ChestGuiStyle.java";
		private static final String GRID =
			"src/client/java/com/chestmemory/client/gui/ItemGridWidget.java";

		@Test
		@DisplayName("The slot pitch is shared, not copied")
		void slotPitchIsShared() throws Exception {
			String style = read(STYLE);
			assertTrue(style.contains("GRID_SLOT = 18"), "the shared slot size is missing");
			assertTrue(
				read(GRID).contains("SLOT = 18"),
				"the reference grid must still agree with it"
			);
			assertTrue(
				read(CLAN_SCREEN).contains("CELL = ChestGuiStyle.GRID_SLOT"),
				"the clan grid invented 24px cells, which is why it looked like another mod"
			);
		}

		@Test
		@DisplayName("Items sit on the same recessed tray")
		void gridHasATray() throws Exception {
			String style = read(STYLE);
			int decl = style.indexOf("public static void drawGridTray");
			assertTrue(decl > 0, "the tray helper is missing");
			String body = style.substring(decl, style.indexOf("\n\t}", decl));
			// The exact two fills the chest panel uses: shared border and face constants,
			// so a palette change recolours every tray at once instead of drifting.
			assertTrue(body.contains("PANEL_BORDER"), "border colour differs from the reference");
			assertTrue(body.contains("PANEL_INNER"), "face colour differs from the reference");
			assertTrue(
				read(CLAN_SCREEN).contains("ChestGuiStyle.drawGridTray("),
				"items on the bare panel read as loose icons, not as an inventory"
			);
		}

		@Test
		@DisplayName("Counts are scaled down the same way")
		void countsMatch() throws Exception {
			String style = read(STYLE);
			int decl = style.indexOf("public static void drawSlotCount");
			String body = style.substring(decl, style.indexOf("\n\t}", decl));
			// Counts are player-configurable now; the reference is the shared settings-driven
			// renderer, not a hardcoded scale.
			assertTrue(body.contains("slotCountScalePct"), "counts must use the configured scale");
			assertTrue(
				read(CLAN_SCREEN).contains("ChestGuiStyle.drawSlotCount("),
				"a full-size count does not fit an 18px slot"
			);
		}

		@Test
		@DisplayName("Compact counts fit the slot at every magnitude")
		void compactCounts() {
			// 18px of slot leaves room for about three glyphs.
			for (int n : new int[]{9, 64, 999, 1000, 1500, 12000, 999_999, 1_500_000}) {
				String text = com.chestmemory.client.gui.ChestGuiStyle.formatCount(n);
				assertTrue(px(text) <= 24, n + " renders as '" + text + "', too wide");
			}
			assertEquals("1k", com.chestmemory.client.gui.ChestGuiStyle.formatCount(1000));
			assertEquals("1.5k", com.chestmemory.client.gui.ChestGuiStyle.formatCount(1500));
			assertEquals("999", com.chestmemory.client.gui.ChestGuiStyle.formatCount(999));
		}

		@Test
		@DisplayName("The header carries a subtitle, and the code is not drawn twice")
		void headerHasASubtitle() throws Exception {
			String src = read(CLAN_SCREEN);
			assertTrue(
				src.contains("this.panelTop + 8") && src.contains("this.panelTop + 20"),
				"title and subtitle sit on the two lines the chest panel uses"
			);
			assertFalse(
				src.contains("drawCodePlate"),
				"the plate sat at +22, straight on top of the new subtitle"
			);
			assertTrue(
				src.contains("screen.chestmemory.clan.header_in"),
				"the subtitle must name the gather being viewed"
			);
		}
	}

	@Nested
	@DisplayName("A gather says who, when and what")
	class GatherDetails {
		@Test
		@DisplayName("The list of gathers names the host, not just a code")
		void listShowsTheHost() throws Exception {
			String src = read(CLAN_SCREEN);
			assertTrue(
				src.contains("if (!e.host().isBlank())"),
				"a column of bare CM-XXXX codes says nothing about which gather is which"
			);
		}

		@Test
		@DisplayName("The roster remembers the host across restarts")
		void hostIsPersisted() throws Exception {
			String src = read("src/client/java/com/chestmemory/client/clan/ClanRoster.java");
			assertTrue(
				src.contains("String host, long seenAt"),
				"the entry has to carry it to survive a restart"
			);
			assertTrue(
				src.contains("prev != null ? prev.host()"),
				"a poll refreshing progress must not erase the host recorded on join"
			);
		}

		@Test
		@DisplayName("Older saved entries still load after the format grew")
		void oldEntriesSurvive() throws Exception {
			String src = read("src/client/java/com/chestmemory/client/clan/ClanRoster.java");
			assertTrue(
				src.contains("parts.length > 4 ? parts[4]") && src.contains("parts.length > 5"),
				"two fields were added later; a shorter line is an older entry, not a broken one"
			);
		}
	}

	@Nested
	@DisplayName("Four tabs fit, and read differently")
	class Tabs {
		@Test
		@DisplayName("Measured: every caption fits its fifth of the strip")
		void captionsFit() throws Exception {
			int tabW = (340 - 24) / 5;
			for (String key : new String[]{
				"tab_gather", "tab_info", "tab_members", "tab_feed", "tab_list"
			}) {
				String label = lang("screen.chestmemory.clan." + key);
				assertTrue(px(label) <= tabW - 6, "tab caption clipped: " + label);
			}
		}

		@Test
		@DisplayName("The working tab and the list tab are not named alike")
		void tabsAreDistinguishable() throws Exception {
			String gather = lang("screen.chestmemory.clan.tab_gather");
			String gathers = lang("screen.chestmemory.clan.tab_list");
			assertFalse(
				gather.equalsIgnoreCase(gathers),
				"adjacent tabs must not read the same"
			);
			assertFalse(
				gathers.toLowerCase().startsWith(gather.toLowerCase().substring(0, 3)),
				"«Сбор» next to «Сборы» was too close to tell apart at a glance"
			);
		}

		@Test
		@DisplayName("Every list tab is reachable by the wheel")
		void everyListScrolls() throws Exception {
			String src = read(CLAN_SCREEN);
			int decl = src.indexOf("public boolean mouseScrolled(");
			String body = src.substring(decl, src.indexOf("\n\t}", decl));
			for (String tab : new String[]{
				"TAB_GATHER", "TAB_MEMBERS", "TAB_FEED", "TAB_LIST"
			}) {
				assertTrue(body.contains(tab), "no scrolling on " + tab);
			}
		}

		@Test
		@DisplayName("The feed shows the whole log, not just what happened to fit")
		void feedIsComplete() throws Exception {
			String src = read(CLAN_SCREEN);
			int decl = src.indexOf("private void drawFeed");
			String body = src.substring(decl, src.indexOf("\n\t}", decl));
			assertTrue(
				body.contains("ClanEventLog.all()"),
				"asking for only the visible count made the rest unreachable"
			);
		}

		@Test
		@DisplayName("The roster no longer promises rows it cannot show")
		void noMoreDeadEndCounter() throws Exception {
			String src = read(CLAN_SCREEN);
			int decl = src.indexOf("private void drawMembers");
			String body = src.substring(decl, src.indexOf("\n\t}", decl));
			assertFalse(
				body.contains("more_members"),
				"'+3 more' named a number the player had no way to reach; it scrolls now"
			);
		}
	}

	@Nested
	@DisplayName("The gather tab works solo, off the player's own schematic")
	class SoloMode {
		@Test
		@DisplayName("Solo is a first-class mode, not a clan-session special case")
		void soloModeExists() throws Exception {
			String src = read(CLAN_SCREEN);
			assertTrue(src.contains("enum GatherMode"), "the mode must be explicit");
			for (String m : new String[]{"GatherMode.CLAN", "GatherMode.SOLO", "GatherMode.EMPTY"}) {
				assertTrue(src.contains(m), "missing mode: " + m);
			}
			assertTrue(
				src.contains("private void drawSoloGather"),
				"solo needs its own body — progress and routing, no claims"
			);
			assertTrue(
				src.contains("private void drawEmptyGather"),
				"no session and no schematic must explain itself, not show a blank panel"
			);
		}

		@Test
		@DisplayName("A solo click aims the gather; clicking the target again stops it")
		void soloClickTogglesTheTarget() throws Exception {
			String src = read(CLAN_SCREEN);
			int decl = src.indexOf("private void soloClickMaterial");
			assertTrue(decl > 0, "solo click handler missing");
			String body = src.substring(decl, src.indexOf("\n\t}", decl));
			assertTrue(
				body.contains("BuildGatherSession.startQueue"),
				"the click must start the existing flow, not invent a parallel one"
			);
			assertTrue(
				body.contains("BuildGatherSession.clear()"),
				"clicking the current target must stop the gather"
			);
			assertTrue(
				body.contains("focusNow != null ? focusNow : itemId"),
				"craft-only clicks re-aim; the status must name the item actually focused"
			);
		}

		@Test
		@DisplayName("Solo pulls the full list — never the Ё panel's cycling filter")
		void soloIgnoresPanelFilter() throws Exception {
			String src = read(CLAN_SCREEN);
			assertTrue(
				src.contains("BuildFilter.ALL"),
				"inheriting the panel filter would read as lost materials"
			);
			String session = read(
				"src/client/java/com/chestmemory/client/litematica/BuildGatherSession.java"
			);
			assertTrue(
				session.contains("BuildFilter useFilter"),
				"the explicit-filter overload must exist"
			);
		}

		@Test
		@DisplayName("Members and feed are not offered outside a session")
		void sessionTabsHideSolo() throws Exception {
			String src = read(CLAN_SCREEN);
			assertTrue(
				src.contains("new int[]{TAB_GATHER, TAB_INFO, TAB_MEMBERS, TAB_FEED, TAB_LIST}")
					&& src.contains("new int[]{TAB_GATHER, TAB_INFO, TAB_LIST}")
					&& src.contains("return new int[]{TAB_GATHER, TAB_LIST};"),
				"tabs describing a session must vanish with it, not sit empty"
			);
		}

		@Test
		@DisplayName("Solo progress counts inventory and staging, not raw chest stock")
		void progressUsesCoverage() throws Exception {
			// The bar lives on the Info tab and the numbers ride the cell tooltip now.
			String src = read(CLAN_SCREEN);
			int decl = src.indexOf("private void drawSoloInfo");
			String body = src.substring(decl, src.indexOf("\n\t}", decl));
			assertTrue(
				body.contains("neededForBuild()") && body.contains("schematicTotal()"),
				"collected = total - still-missing; chest stock is supply, not progress"
			);
			int tip = src.indexOf("private List<Component> soloCellTooltip");
			assertTrue(tip > 0, "solo cell tooltip missing");
			String tipBody = src.substring(tip, src.indexOf("\n\t}", tip));
			assertTrue(
				tipBody.contains("gather_collected") && tipBody.contains("gather_left"),
				"the hover must carry collected and left"
			);
		}
	}
}
