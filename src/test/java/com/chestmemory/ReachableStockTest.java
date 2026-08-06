package com.chestmemory;

import com.chestmemory.client.data.BulkAmount;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two reports from one screenshot pair, both about a tooltip quoting a number that belongs
 * to something else.
 * <p>
 * The cell said "В сундуках: 21 · ближайший: 954м" and, in green, "В сундуках хватает —
 * клик: маршрут". All 21 were in another world; the click answered "нет в сундуках". The
 * rows are built deliberately unfiltered — hiding a far-away material would read as losing
 * it — but the tooltip quoted that unfiltered count while the click routed over the
 * filtered sources.
 * <p>
 * And under a remainder of 5754 hoppers sat "стаками: 126 ст. + 33 шт", which is not 5754
 * in stacks. It is an exact reading of the 8097 in the chests, two lines up, printed with
 * no label to say so.
 */
class ReachableStockTest {
	private static final String SESSION =
		"src/client/java/com/chestmemory/client/litematica/BuildGatherSession.java";
	private static final String SCREEN =
		"src/client/java/com/chestmemory/client/gui/ClanGatherScreen.java";
	private static final String STORAGE =
		"src/client/java/com/chestmemory/client/data/ChestMemoryStorage.java";
	private static final String BULK =
		"src/client/java/com/chestmemory/client/gui/BulkTooltip.java";
	private static final String RU = "src/main/resources/assets/chestmemory/lang/ru_ru.json";
	private static final String EN = "src/main/resources/assets/chestmemory/lang/en_us.json";

	private static String read(String path) throws Exception {
		return Files.readString(Path.of(path));
	}

	private static String body(String src, String signature) {
		int at = src.indexOf(signature);
		assertTrue(at > 0, "not found: " + signature);
		return src.substring(at, src.indexOf("\n\t}", at));
	}

	@Nested
	@DisplayName("The numbers in the report, checked as arithmetic")
	class TheScreenshot {
		@Test
		@DisplayName("126 stacks and 33 is the chest stock, not the remainder")
		void hopperFigures() {
			// What the tooltip printed: "стаками: 126 ст. + 33 шт" under "Осталось: 5754".
			BulkAmount stock = BulkAmount.of(8097, 64);
			// stacks() counts the stacks left AFTER the boxes are taken out; the plain
			// stack reading is totalStacks(). Getting that backwards is how a reader
			// arrives at "126 stacks of 5754" in the first place.
			assertEquals(126, stock.totalStacks(), "126 × 64 + 33 = 8097 — the chests, exactly");
			assertEquals(33, stock.looseAfterStacks());
			// And in boxes: 4 × 1728 + 18 × 64 + 33 = 8097, the same number again.
			assertEquals(4, stock.boxes());
			assertEquals(18, stock.stacks());
			assertEquals(33, stock.items());
			assertEquals(4 * 1728 + 18 * 64 + 33, 8097, "the shulker reading is the same total");
		}

		@Test
		@DisplayName("The remainder the player wanted is 89 stacks and 58, and nothing showed it")
		void remainderFigures() {
			BulkAmount need = BulkAmount.of(5754, 64);
			assertEquals(89, need.totalStacks());
			assertEquals(58, need.looseAfterStacks());
			assertEquals(89 * 64 + 58, 5754, "the arithmetic the report did by hand");
		}
	}

	@Nested
	@DisplayName("One number, and a click that obeys it")
	class Reach {
		@Test
		@DisplayName("Reachable stock is measured over the very list a route walks")
		void builtFromFilteredSources() throws Exception {
			String reach = body(read(SESSION), "public static Reachable reachable(");
			assertTrue(
				reach.contains("filteredSources(itemId)"),
				"the click routes over these; a tooltip quoting anything else can contradict it"
			);
			assertTrue(
				reach.contains("if (d >= 0 && (best < 0 || d < best))"),
				"a negative distance means unreachable and must not win by being smallest"
			);
		}

		@Test
		@DisplayName("The solo tooltip quotes it — count, distance and verdict alike")
		void tooltipUsesReach() throws Exception {
			String tip = body(read(SCREEN), "private List<Component> soloCellTooltip(");
			assertTrue(tip.contains("var reach = reach(r.itemId());"),
				"through the shared 500ms cache, so the tooltip and the grid agree and the "
					+ "containers are walked once");
			assertTrue(tip.contains("reach.count(), dist,"), "the headline number");
			assertTrue(tip.contains("reach.nearest() >= 0"), "and the metres beside it");
			assertTrue(tip.contains("reach.count() >= missing"), "and the green verdict");
			assertFalse(
				tip.contains("r.totalCount() >= missing"),
				"that comparison is what promised stock in another world"
			);
			assertFalse(
				tip.contains("r.hasDistance()"),
				"the row's own distance ignores the range filter — 954m under 'рядом'"
			);
		}

		@Test
		@DisplayName("Out of reach is said differently from not owned at all")
		void twoDifferentRefusals() throws Exception {
			String start = read(SESSION);
			assertTrue(
				start.contains("message.chestmemory.build_out_of_reach"),
				"'нет в сундуках' about 21 sand in another world sends the player mining"
			);
			assertTrue(
				start.contains("int anywhere = countInChestsLive(first, DimensionChoice.ALL, dim);"),
				"the two cases are told apart by whether the world has any at all"
			);
			String tip = body(read(SCREEN), "private List<Component> soloCellTooltip(");
			assertTrue(
				tip.contains("screen.chestmemory.clan.solo_hover_far"),
				"and the tooltip says the same thing the click will"
			);
		}

		@Test
		@DisplayName("Another dimension is not a distance, whatever the record looks like")
		void noCrossWorldMetres() throws Exception {
			String dist = body(read(STORAGE), "public static double distanceTo(");
			assertTrue(dist.contains("!playerDimension.equals(record.dimension())"));
			assertFalse(
				dist.contains("&& record.isWorldBlock()) {\n\t\t\treturn -1;"),
				"that qualifier measured non-block records straight through the world boundary"
			);
		}
	}

	@Nested
	@DisplayName("The colour is a promise about the click")
	class Tint {
		@Test
		@DisplayName("A solo cell is tinted by what is in reach, not by what the world holds")
		void soloTintUsesReach() throws Exception {
			String screen = read(SCREEN);
			int at = screen.indexOf("int stock = chestStock(r.itemId());");
			assertTrue(
				at > 0,
				"green means click and go, so it must be measured over the reachable chests — "
					+ "seven polished granite in another world lit the cell green and then "
					+ "refused the click"
			);
			assertFalse(
				screen.contains("int stock = r.totalCount();"),
				"the row's own count includes worlds a route cannot enter"
			);
		}

		@Test
		@DisplayName("The clan grid already did this, and must keep doing it")
		void clanTintUnchanged() throws Exception {
			assertTrue(
				read(SCREEN).contains("int stock = done ? 0 : chestStock(e.getKey());"),
				"the two grids disagreeing about one state is what started this"
			);
		}

		@Test
		@DisplayName("The caption counts the same thing the cells are coloured by")
		void captionAgreesWithGrid() throws Exception {
			String screen = read(SCREEN);
			assertFalse(
				screen.contains("} else if (r.totalCount() > 0) {\n\t\t\t\t\tstocked++;"),
				"'есть в сундуках 92' beside 92 red cells is the same lie, one line down"
			);
			assertEquals(
				2, screen.split("chestStock\\(r\\.itemId\\(\\)\\) > 0", -1).length - 1,
				"both tallies — the grid caption and the info tab — count reachable stock"
			);
		}

		@Test
		@DisplayName("The grid is ordered by the same band it is coloured by")
		void orderMatchesColour() throws Exception {
			String screen = read(SCREEN);
			assertTrue(
				screen.contains("shown.sort(java.util.Comparator")
					&& screen.contains(".comparingInt(this::soloBand)"),
				"partial, then green, then partial again is one order with a different "
					+ "colouring painted over it"
			);
			String band = body(screen, "private int soloBand(");
			assertTrue(
				band.contains("int stock = chestStock(s.itemId());"),
				"the band has to read the very number the tint reads"
			);
			String clan = body(screen, "private int clanBand(");
			assertTrue(
				clan.contains("int stock = chestStock(itemId);"),
				"its clan twin reads the same, and the two must not drift apart again"
			);
		}

		@Test
		@DisplayName("Count and distance come from one walk, not two")
		void oneWalk() throws Exception {
			String screen = read(SCREEN);
			assertTrue(screen.contains("private double chestNearest(String itemId) {"));
			assertTrue(
				body(screen, "private int chestStock(String itemId) {")
					.contains("return reach(itemId).count();"),
				"both read the cached Reachable rather than scanning the records apiece"
			);
		}

		@Test
		@DisplayName("The tooltip still reports what lies elsewhere, which is the point of the line")
		void elsewhereStillShown() throws Exception {
			String tip = body(read(SCREEN), "private List<Component> soloCellTooltip(");
			assertTrue(
				tip.contains("r.totalCount() > 0"),
				"a red cell must still be able to say the world has seven of them"
			);
		}
	}

	@Nested
	@DisplayName("Each breakdown says which number it belongs to")
	class Labels {
		@Test
		@DisplayName("Both breakdowns are printed, under their own labels")
		void bothLabelled() throws Exception {
			String detail = body(read(SCREEN), "private void addStockDetail(");
			assertTrue(detail.contains("screen.chestmemory.tooltip.need_stacks"));
			assertTrue(detail.contains("screen.chestmemory.tooltip.stock_stacks"));
			assertTrue(
				detail.contains("BulkTooltip.append(lines, need, per,")
					&& detail.contains("BulkTooltip.append(lines, stock, per,"),
				"the remainder and the chest stock, each with its own words"
			);
		}

		@Test
		@DisplayName("The unlabelled form still exists for callers with only one number")
		void plainFormKept() throws Exception {
			String bulk = read(BULK);
			assertTrue(
				bulk.contains("public static void append(List<Component> lines, int amount, int perStack) {"),
				"the item panel has one number and needs no disambiguation"
			);
			assertTrue(bulk.contains("String stacksKey, String boxesKey"), "and the labelled form");
		}

		@Test
		@DisplayName("Both languages carry every new string")
		void strings() throws Exception {
			for (String file : new String[]{RU, EN}) {
				String lang = read(file);
				for (String key : new String[]{
					"screen.chestmemory.tooltip.need_stacks",
					"screen.chestmemory.tooltip.need_boxes",
					"screen.chestmemory.tooltip.stock_stacks",
					"screen.chestmemory.tooltip.stock_boxes",
					"screen.chestmemory.clan.solo_hover_far",
					"message.chestmemory.build_out_of_reach",
				}) {
					assertTrue(lang.contains('"' + key + '"'), file + " is missing " + key);
				}
			}
		}
	}
}
