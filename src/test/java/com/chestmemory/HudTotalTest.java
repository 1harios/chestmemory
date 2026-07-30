package com.chestmemory;

import com.chestmemory.client.litematica.BuildGatherSession;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How much of this material the job wants, as opposed to how much is left of it.
 * <p>
 * The HUD printed one number — the remainder — so "Нужно ×1600" could not say whether 1600
 * was the whole job or the tail of 31096. The total was being computed three lines above the
 * constructor and thrown away.
 */
class HudTotalTest {
	private static final String HUD =
		"src/client/java/com/chestmemory/client/litematica/BuildGatherHud.java";
	private static final String SESSION =
		"src/client/java/com/chestmemory/client/litematica/BuildGatherSession.java";
	private static final String HINT =
		"src/client/java/com/chestmemory/client/highlight/SlotHighlighter.java";
	private static final String RU = "src/main/resources/assets/chestmemory/lang/ru_ru.json";
	private static final String EN = "src/main/resources/assets/chestmemory/lang/en_us.json";

	private static String read(String path) throws Exception {
		return Files.readString(Path.of(path));
	}

	private static BuildGatherSession.HudLine line(int missing, int total) {
		return new BuildGatherSession.HudLine(
			"minecraft:glass", "Стекло", missing, 0, 0, -1, true, 0, total
		);
	}

	@Nested
	@DisplayName("The line carries both numbers")
	class Carried {
		@Test
		@DisplayName("The total survives the trip to the HUD")
		void totalPresent() {
			assertEquals(31096, line(1600, 31096).total());
			assertEquals(1600, line(1600, 31096).missing());
		}

		@Test
		@DisplayName("What is already covered is the difference, never negative")
		void doneIsDerived() {
			assertEquals(29496, line(1600, 31096).done());
			assertEquals(31096, line(0, 31096).done(), "nothing left means all of it is in");
			assertEquals(
				0, line(500, 100).done(),
				"a remainder larger than the total is nonsense data, not a negative count"
			);
		}

		@Test
		@DisplayName("A material nothing recorded a need for reports zero, not a bad fraction")
		void unknownTotal() {
			assertEquals(0, line(1600, 0).total());
			assertEquals(0, line(1600, 0).done());
		}
	}

	@Nested
	@DisplayName("The HUD prints the pair")
	class Hud {
		@Test
		@DisplayName("The need row shows remainder and total together")
		void needRowShowsBoth() throws Exception {
			String hud = read(HUD);
			assertTrue(
				hud.contains("\"hud.chestmemory.val_need_of\""),
				"one row, two numbers — the panel must not grow a line for this"
			);
			assertTrue(
				hud.contains("current.total() > 0"),
				"a zero total has nothing to divide by, so the bare remainder stays"
			);
			assertFalse(
				hud.contains("rows.add(stat(font, \"hud.chestmemory.lbl_need\", "
					+ "formatCount(current.missing()),"),
				"the single-number row is what the report was about"
			);
		}

		@Test
		@DisplayName("This material gets its own bar, separate from the whole list")
		void perItemBar() throws Exception {
			String hud = read(HUD);
			assertTrue(hud.contains("\"hud.chestmemory.item_progress\""), "its own label");
			assertTrue(
				hud.contains("current.done() / (float) current.total()"),
				"filled from this material, not from the build"
			);
			assertTrue(
				hud.contains("\"hud.chestmemory.overall\""),
				"and the list-wide bar stays — they answer different questions"
			);
		}

		@Test
		@DisplayName("Compact mode shows the pair too, and keeps the list-wide bar")
		void compactCarriesBoth() throws Exception {
			String hud = read(HUD);
			int compact = hud.indexOf("gatherHudCompact()");
			assertTrue(compact > 0);
			String slim = hud.substring(compact);
			assertTrue(
				slim.contains("\"hud.chestmemory.val_need_of\""),
				"compact is where one bare number was most ambiguous"
			);
			// Adding a per-item bar above the overall one would have silently swapped compact
			// mode onto the per-item figure its head line already spells out.
			assertTrue(
				slim.contains("if (overall != null) {"),
				"compact takes the list-wide bar by reference, not whichever bar comes first"
			);
			assertFalse(
				slim.contains("if (r.isBar()) {"),
				"'first bar in the list' stopped being unambiguous the moment there were two"
			);
		}
	}

	@Nested
	@DisplayName("The inventory hint says the same thing")
	class Hint {
		@Test
		@DisplayName("The hint shows the pair, from the same source as the HUD")
		void hintShowsTotal() throws Exception {
			String hint = read(HINT);
			assertTrue(
				hint.contains("BuildGatherSession.totalNeed(itemId)"),
				"one accessor, so the two overlays cannot disagree"
			);
			assertTrue(
				hint.contains("total > stillNeed"),
				"a total equal to the remainder adds nothing — nothing has been gathered yet"
			);
			assertFalse(
				hint.contains("String need = \"↓ \" + stillNeed;"),
				"the bare remainder is what the report was about"
			);
		}

		@Test
		@DisplayName("totalNeed walks the same chain, in the same order, as remainingNeed")
		void sameChain() throws Exception {
			String session = read(SESSION);
			int at = session.indexOf("public static int totalNeed(");
			assertTrue(at > 0);
			String body = session.substring(at, session.indexOf("\n\t}", at));
			int live = body.indexOf("LitematicaAccess.missingMaterialsById()");
			int snapshot = body.indexOf("queueTotals.getOrDefault");
			int clan = body.indexOf("clanNeed(itemId)");
			assertTrue(live > 0 && snapshot > live && clan > snapshot,
				"live list, then snapshot, then clan need — any other order lets the HUD and "
					+ "the hint print different totals for one material");
		}
	}

	@Nested
	@DisplayName("The field that started this")
	class Naming {
		@Test
		@DisplayName("The schematic snapshot is no longer called a remainder")
		void honestName() throws Exception {
			String session = read(SESSION);
			assertTrue(session.contains("queueTotals"), "it holds totals, so it says totals");
			// The comment explaining the rename may mention the old name; a live reference
			// would be a field access followed by a dot or a bracket.
			assertFalse(
				session.contains("queueMissing.") || session.contains("queueMissing ="),
				"a field named Missing that holds totals is how this class of bug starts"
			);
		}

		@Test
		@DisplayName("Both languages carry the new strings")
		void strings() throws Exception {
			for (String file : new String[]{RU, EN}) {
				String lang = read(file);
				for (String key : new String[]{
					"hud.chestmemory.val_need_of",
					"hud.chestmemory.item_progress",
				}) {
					assertTrue(lang.contains('"' + key + '"'), file + " is missing " + key);
				}
			}
			assertTrue(
				read(RU).contains("\"hud.chestmemory.val_need_of\": \"×%s / %s\""),
				"remainder first, total second — the order the player was promised"
			);
		}
	}
}
