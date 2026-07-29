package com.chestmemory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The third round of play-testing: the gather tooltip was reordered, a gather can be taken
 * off the list, and the HUD grew the things a player standing at a chest actually asks for.
 */
class GatherPolishRoundTwoTest {
	private static final String SCREEN =
		"src/client/java/com/chestmemory/client/gui/ClanGatherScreen.java";
	private static final String MANAGER =
		"src/client/java/com/chestmemory/client/clan/ClanSessionManager.java";
	private static final String HUD =
		"src/client/java/com/chestmemory/client/litematica/BuildGatherHud.java";
	private static final String SESSION =
		"src/client/java/com/chestmemory/client/litematica/BuildGatherSession.java";
	private static final String SETTINGS =
		"src/client/java/com/chestmemory/client/data/ModSettings.java";
	private static final String SETTINGS_SCREEN =
		"src/client/java/com/chestmemory/client/gui/ChestMemorySettingsScreen.java";
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
	@DisplayName("The tooltip says where the stock is")
	class WorldSplit {
		@Test
		@DisplayName("The count matches the colour, and the rest is broken out by world")
		void splitLines() throws Exception {
			String screen = read(SCREEN);
			String split = body(screen, "private void addWorldSplit(");
			assertTrue(
				split.contains("DimensionChoice.CURRENT") && split.contains("DimensionChoice.ALL"),
				"this world and every world are both needed to say what is elsewhere"
			);
			assertTrue(
				split.contains("if (here > filtered)"),
				"repeating the number from the line above is noise, not information"
			);
			assertTrue(
				split.contains("elsewhere > 0"),
				"a red cell with thousands in another world is the case worth explaining"
			);
			assertTrue(
				body(screen, "private List<Component> clanCellTooltip(").contains("addWorldSplit("),
				"the clan tooltip must carry it"
			);
			assertTrue(
				body(screen, "private List<Component> soloCellTooltip(").contains("addWorldSplit("),
				"and so must solo — the same question is asked in both"
			);
		}

		@Test
		@DisplayName("The stock figure still follows the panel filter, so it cannot contradict the colour")
		void stockFollowsFilter() throws Exception {
			// The colour comes from chestStock, which goes through the single-argument
			// countInChestsLive — the one that applies the panel's dimension and nearby filter.
			// A tooltip built on any other basis could show 4800 next to a red cell.
			assertTrue(
				body(read(SCREEN), "private int chestStock(").contains("BuildGatherSession::countInChestsLive"),
				"the tooltip and the colour must read the same number"
			);
			String filtered = body(read(SESSION), "private static List<ContainerRecord> filteredSources(");
			assertTrue(
				filtered.contains("filterDim()") && filtered.contains("filterScope()")
					&& filtered.contains("filterRange()"),
				"nearby, radius and dimension all come from the panel's own settings"
			);
		}
	}

	@Nested
	@DisplayName("A gather can be taken off the list")
	class ForgetGather {
		@Test
		@DisplayName("The glyph has its own strip, and a miss falls through to switching")
		void ownHitZone() throws Exception {
			String screen = read(SCREEN);
			assertTrue(screen.contains("CLOSE_W = 12"), "the strip has a width of its own");
			String hit = body(screen, "private @org.jspecify.annotations.Nullable String gatherCloseAt(");
			assertTrue(
				hit.contains("mx < this.gatherCloseX0 || mx > this.gatherCloseX1"),
				"outside the strip the click is not a removal"
			);
			int closeCheck = screen.indexOf("if (forgetClick(event))");
			int pickCheck = screen.indexOf("String pick = gatherAt(");
			assertTrue(
				closeCheck > 0 && closeCheck < pickCheck,
				"the narrow strip must be tested first, or it can never win"
			);
		}

		@Test
		@DisplayName("Removing the active gather arms; removing another does not")
		void armsOnlyForActive() throws Exception {
			String forget = body(read(SCREEN), "private boolean forgetClick(");
			assertTrue(
				forget.contains("isActive && !remove.equalsIgnoreCase(this.forgetArmCode)"),
				"only the row that leaves a hub session asks twice"
			);
			assertTrue(
				read(SCREEN).contains("this.forgetArmCode = null;\n\t\t\tdisarmed = true;"),
				"a stale arm must expire like the others, or it waits forever"
			);
		}

		@Test
		@DisplayName("Removing never closes the gather for the rest of the clan")
		void neverCloses() throws Exception {
			String forget = body(read(MANAGER), "public static void forgetAsync(");
			assertTrue(
				forget.contains("exitAsync(mc, Exit.LEAVE, onDone)"),
				"a host taking a row off their own screen must not end everyone's evening"
			);
			assertFalse(forget.contains("Exit.CLOSE"), "close is a different button entirely");
			assertTrue(
				forget.contains("ClanRoster.forget(key)") && forget.contains("ClanHostSecrets.forget(key)"),
				"a gather off the list leaves nothing behind"
			);
		}
	}

	@Nested
	@DisplayName("The HUD answers what a player at a chest asks")
	class Hud {
		@Test
		@DisplayName("The remaining amount is also given in stacks")
		void bulkRow() throws Exception {
			String hud = read(HUD);
			assertTrue(hud.contains("hud.chestmemory.lbl_bulk"), "the row exists");
			String bulk = body(hud, "private static String bulkText(");
			assertTrue(
				bulk.contains("hasBox()") && bulk.contains("hasStack()"),
				"boxes when there is a whole one, stacks otherwise"
			);
			assertTrue(
				bulk.contains("return \"\";"),
				"an unstackable item has no stack tier and must produce no row"
			);
		}

		@Test
		@DisplayName("Overall progress covers the whole list, not the current item")
		void overallBar() throws Exception {
			String session = read(SESSION);
			assertTrue(
				session.contains("public static int hudTotalNeed()")
					&& session.contains("public static int hudTotalDone()"),
				"the totals have to be exposed"
			);
			assertTrue(
				session.contains("allNeed = clanSession.totalNeed()"),
				"in a clan gather the hub's totals are the shared truth"
			);
			assertFalse(
				body(session, "public static List<HudLine> hudLines()").contains("allNeed"),
				"summing hudLines would make the bar walk backwards as items finish and drop out"
			);
			assertTrue(read(HUD).contains("Row.bar("), "and the HUD has to draw it");
		}

		@Test
		@DisplayName("Scale places the box by its on-screen size, not its unscaled one")
		void scaleAndCorners() throws Exception {
			String hud = read(HUD);
			assertTrue(hud.contains("gatherHudScalePct()"), "the setting is read");
			assertTrue(
				hud.contains("int drawW = Math.round(BOX_W * scale)")
					&& hud.contains("int drawH = Math.round(boxH * scale)"),
				"measuring the unscaled box left a gap at the bottom and right edges"
			);
			assertTrue(
				hud.contains("x = Math.round(x / scale)"),
				"the result has to come back into the scaled coordinate space"
			);
			assertTrue(hud.contains("popMatrix()"), "a pushed matrix must be popped");
		}

		@Test
		@DisplayName("Compact mode keeps the target and the bar, and never draws nothing")
		void compactMode() throws Exception {
			String hud = read(HUD);
			assertTrue(hud.contains("gatherHudCompact()"), "the setting is read");
			int compact = hud.indexOf("gatherHudCompact()");
			String block = hud.substring(compact, Math.min(hud.length(), compact + 1200));
			assertTrue(block.contains("r.isBar()"), "the overall bar survives the trim");
			assertTrue(
				block.contains("slim.isEmpty()"),
				"an empty compact HUD would be an invisible box with a border"
			);
		}

		@Test
		@DisplayName("Both new settings are clamped and reachable from the settings screen")
		void settingsWired() throws Exception {
			String settings = read(SETTINGS);
			assertTrue(
				body(settings, "public int gatherHudScalePct()").contains("Math.max(60"),
				"clamped on read too: the file is hand-editable and a zero would hide the HUD"
			);
			String screen = read(SETTINGS_SCREEN);
			assertTrue(screen.contains("settings.row.hud_scale"), "the slider is on the gather tab");
			assertTrue(screen.contains("settings.row.hud_compact"), "and so is the switch");
			assertTrue(
				screen.contains("() -> ModSettings.get().showGatherHud());"),
				"a HUD option is pointless while the HUD is off — the row greys out"
			);
		}
	}

	@Nested
	@DisplayName("Every new string exists in both languages")
	class Strings {
		@Test
		@DisplayName("Tooltip, list and HUD strings are translated")
		void bothLanguages() throws Exception {
			String ru = read(RU);
			String en = read(EN);
			for (String key : new String[]{
				"screen.chestmemory.clan.stock_here",
				"screen.chestmemory.clan.stock_elsewhere",
				"screen.chestmemory.clan.forget_confirm",
				"message.chestmemory.clan_forgot",
				"hud.chestmemory.lbl_bulk",
				"hud.chestmemory.overall",
				"screen.chestmemory.settings.row.hud_scale",
				"screen.chestmemory.settings.row.hud_compact",
			}) {
				assertTrue(ru.contains('"' + key + '"'), "ru_ru is missing " + key);
				assertTrue(en.contains('"' + key + '"'), "en_us is missing " + key);
			}
			assertFalse(
				ru.contains("\"screen.chestmemory.tooltip.gather_percent\""),
				"the percentage line was dropped — its string must go with it"
			);
		}
	}
}
