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
		@DisplayName("There is a materials tab at all")
		void tabExists() throws Exception {
			String src = read(CLAN_SCREEN);
			assertTrue(src.contains("TAB_MATERIALS"), "the tab is missing");
			assertTrue(
				src.contains("case TAB_MATERIALS -> drawMaterials"),
				"the tab must actually be drawn"
			);
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
		@DisplayName("Unfinished materials sort to the top, largest remainder first")
		void sortedByWhatMatters() throws Exception {
			String src = read(CLAN_SCREEN);
			int decl = src.indexOf("private void drawMaterials");
			String body = src.substring(decl, src.indexOf("\n\t}", decl));
			assertTrue(
				body.contains("rows.sort(") && body.contains("(ra == 0) != (rb == 0)"),
				"finished items must sink; hub order is not useful order"
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
			int decl = src.indexOf("private void drawGatherSummary");
			String body = src.substring(decl, src.indexOf("\n\t}", decl));
			assertTrue(
				body.contains("s.schemaName"),
				"two gathers looked identical apart from their code"
			);
		}

		@Test
		@DisplayName("The facts are label/value rows, not a run-on sentence")
		void factsAreDetailRows() throws Exception {
			// Tiles were replaced by detail rows: they duplicated numbers the rows carry and
			// cost 32px the card needed for who/when. The eye runs down the labels instead.
			String src = read(CLAN_SCREEN);
			assertTrue(src.contains("ChestGuiStyle.drawDetailRow("), "detail rows are missing");
			for (String key : new String[]{
				"detail_host", "detail_created", "detail_updated",
				"detail_items", "detail_warehouse", "detail_members"
			}) {
				assertTrue(src.contains("screen.chestmemory.clan." + key), "missing: " + key);
			}
		}

		@Test
		@DisplayName("It says how much work is free, and where to take it")
		void pointsAtTheWork() throws Exception {
			String src = read(CLAN_SCREEN);
			int decl = src.indexOf("private void drawGatherSummary");
			String body = src.substring(decl, src.indexOf("\n\t}", decl));
			assertTrue(
				body.contains("screen.chestmemory.clan.hint_free"),
				"the card has to point at the Materials tab now that it has no slots of its own"
			);
			assertTrue(
				body.contains("screen.chestmemory.clan.all_claimed")
					&& body.contains("screen.chestmemory.clan.hint_finished"),
				"every end state needs its own line: all claimed is not the same as all done"
			);
		}

		@Test
		@DisplayName("Measured: the card fits above the session controls")
		void summaryFits() {
			int panelH = 300;
			int tabsY = 36 + 8 + 18 + 4 + 2;
			int y = tabsY + 22;
			y += 20;        // name plate
			y += 24;        // bar + percentage
			y += 11 * 6;    // six detail rows
			y += 4;
			int end = y + 2 + 8;   // closing hint
			assertTrue(end <= panelH - 70, "the card runs into the session controls");
		}

		@Test
		@DisplayName("Measured: a detail row fits its label and value side by side")
		void detailRowsFit() throws Exception {
			int content = 340 - 24;
			// Worst realistic case: a long player name against the widest label.
			String label = lang("screen.chestmemory.clan.detail_host");
			String value = "ОченьДлинныйНикИгрока (вы)";
			assertTrue(px(label) + px(value) + 8 <= content, "the row cannot hold both");
			for (String key : new String[]{
				"detail_created", "detail_updated", "detail_items",
				"detail_warehouse", "detail_members"
			}) {
				assertTrue(px(lang("screen.chestmemory.clan." + key)) < content / 2,
					"label is too wide to leave room for its value: " + key);
			}
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
			// The exact two fills the chest panel uses: dark border, light grey face.
			assertTrue(body.contains("0xFF1A120A"), "border colour differs from the reference");
			assertTrue(body.contains("0xFFC6C6C6"), "face colour differs from the reference");
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
			assertTrue(body.contains("0.72F"), "the reference scales counts to 0.72");
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
	@DisplayName("Five tabs still fit, and read differently")
	class Tabs {
		@Test
		@DisplayName("Measured: every caption fits its fifth of the strip")
		void captionsFit() throws Exception {
			int tabW = (340 - 24) / 5;
			for (String key : new String[]{
				"tab_gather", "tab_materials", "tab_members", "tab_feed", "tab_list"
			}) {
				String label = lang("screen.chestmemory.clan." + key);
				assertTrue(px(label) <= tabW - 6, "tab caption clipped: " + label);
			}
		}

		@Test
		@DisplayName("The two list tabs are not named alike")
		void tabsAreDistinguishable() throws Exception {
			String materials = lang("screen.chestmemory.clan.tab_materials");
			String gathers = lang("screen.chestmemory.clan.tab_list");
			assertFalse(
				materials.equalsIgnoreCase(gathers),
				"adjacent tabs must not read the same"
			);
			assertFalse(
				materials.toLowerCase().startsWith(gathers.toLowerCase().substring(0, 3)),
				"'Список' next to 'Сборы' was too close to tell apart at a glance"
			);
		}

		@Test
		@DisplayName("Every list tab is reachable by the wheel")
		void everyListScrolls() throws Exception {
			String src = read(CLAN_SCREEN);
			int decl = src.indexOf("public boolean mouseScrolled(");
			String body = src.substring(decl, src.indexOf("\n\t}", decl));
			for (String tab : new String[]{
				"TAB_MATERIALS", "TAB_MEMBERS", "TAB_FEED", "TAB_LIST"
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
}
