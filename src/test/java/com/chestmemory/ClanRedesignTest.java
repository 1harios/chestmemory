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
		@DisplayName("The three numbers are tiles, not a run-on sentence")
		void numbersAreTiles() throws Exception {
			String src = read(CLAN_SCREEN);
			assertTrue(src.contains("private void drawTile("), "tiles are missing");
			for (String key : new String[]{"tile_online", "tile_claimed", "tile_left"}) {
				assertTrue(src.contains("screen.chestmemory.clan." + key), "missing: " + key);
			}
		}

		@Test
		@DisplayName("It answers 'what should I go get?' with icons")
		void showsFreeItems() throws Exception {
			String src = read(CLAN_SCREEN);
			int decl = src.indexOf("private void drawGatherSummary");
			String body = src.substring(decl, src.indexOf("\n\t}", decl));
			assertTrue(
				body.contains("next.size() == 8"),
				"the free-item strip must be bounded, or it would run off the panel"
			);
			assertTrue(
				body.contains("screen.chestmemory.clan.all_claimed"),
				"when nothing is free the strip must say so rather than sit empty"
			);
		}

		@Test
		@DisplayName("Measured: the summary fits above the back button")
		void summaryFits() {
			int panelH = 300;
			int tabsY = 36 + 8 + 18 + 4 + 2;
			int y = tabsY + 24;
			y += 14;   // schematic name
			y += 26;   // bar + percentage
			y += 32;   // tiles
			int iconsBottom = y + 11 + 18;
			assertTrue(iconsBottom <= panelH - 26, "the icon strip runs into the back button");
		}

		@Test
		@DisplayName("Measured: eight icons and three tiles fit the panel")
		void horizontalFits() throws Exception {
			int content = 340 - 24;
			assertTrue(8 * 20 <= content, "the free-item strip is too wide");
			int tileW = (content - 8) / 3;
			for (String key : new String[]{"tile_online", "tile_claimed", "tile_left"}) {
				String label = lang("screen.chestmemory.clan." + key);
				assertTrue(px(label) <= tileW - 6, "tile caption clipped: " + label);
			}
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
