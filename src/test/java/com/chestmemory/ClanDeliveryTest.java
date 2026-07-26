package com.chestmemory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Picking an item up counted as having gathered it.
 * <p>
 * remainingNeed() added the backpack to the warehouse, so the moment a stack was in hand the
 * gather called the item finished and moved on — while the hub was still told delivered = 0,
 * because the report reads the warehouse. Locally done, clan-wise nothing had happened.
 * <p>
 * For a shared gather only the warehouse can count: a stack in someone's backpack is not
 * available to the clan, and it is not delivered until it is dropped off.
 */
class ClanDeliveryTest {
	private static String read(String path) throws Exception {
		return Files.readString(Path.of(path));
	}

	private static final String GATHER =
		"src/client/java/com/chestmemory/client/litematica/BuildGatherSession.java";
	private static final String CLAN =
		"src/client/java/com/chestmemory/client/clan/ClanSessionManager.java";
	private static final String SCANNER =
		"src/client/java/com/chestmemory/client/scan/ContainerScanner.java";
	private static final String CLAN_SCREEN =
		"src/client/java/com/chestmemory/client/gui/ClanGatherScreen.java";
	private static final String CHEST_SCREEN =
		"src/client/java/com/chestmemory/client/gui/ChestMemoryScreen.java";
	private static final String RU =
		"src/main/resources/assets/chestmemory/lang/ru_ru.json";

	private static String body(String src, String signature) {
		int at = src.indexOf(signature);
		assertTrue(at > 0, "not found: " + signature);
		return src.substring(at, src.indexOf("\n\t}", at));
	}

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
	@DisplayName("Only what reached the warehouse counts for the clan")
	class WarehouseIsTheTruth {
		@Test
		@DisplayName("A clan gather ignores the backpack")
		void backpackDoesNotCount() throws Exception {
			String src = body(read(GATHER), "public static int remainingNeed(String itemId, @Nullable LocalPlayer player)");
			assertTrue(
				src.contains("boolean clanGather = com.chestmemory.client.clan.ClanSessionManager.isInActiveGather(itemId)")
					&& src.contains("int inPlayer = clanGather ? 0 : countInPlayer(player, itemId)"),
				"carrying a stack must not finish a clan item: the report reads the warehouse, "
					+ "so the gather advanced while the hub was told delivered = 0"
			);
		}

		@Test
		@DisplayName("Solo gathering still counts the backpack")
		void soloIsUnchanged() throws Exception {
			String src = body(read(GATHER), "public static int remainingNeed(String itemId, @Nullable LocalPlayer player)");
			assertTrue(
				src.contains("clanGather ? 0 : countInPlayer"),
				"solo has no warehouse to require — carrying the material IS having it"
			);
			assertTrue(
				src.contains("int covered = inPlayer + warehouse"),
				"the solo path must still add the two together"
			);
		}

		@Test
		@DisplayName("The arithmetic the fix depends on")
		void coveredMaths() {
			int need = 64;
			// Clan: 64 in the backpack, nothing dropped off yet.
			int coveredClan = 0 + 0;
			assertEquals(64, need - coveredClan, "nothing is delivered until it is dropped off");
			// Clan: the stack reaches the warehouse.
			int coveredDelivered = 0 + 64;
			assertEquals(0, need - coveredDelivered, "now it counts");
			// Solo: the backpack is enough.
			int coveredSolo = 64 + 0;
			assertEquals(0, need - coveredSolo, "solo must not regress");
		}

		@Test
		@DisplayName("The report still reads the warehouse, which is now the only source")
		void reportReadsTheWarehouse() throws Exception {
			String src = body(read(CLAN), "public static void reportStagedNow(Minecraft mc, @Nullable String itemId)");
			assertTrue(
				src.contains("countInStaging(itemId)"),
				"delivery is warehouse contents; anything else would count items twice"
			);
		}
	}

	@Nested
	@DisplayName("A drop-off registers immediately")
	class ImmediateDelivery {
		@Test
		@DisplayName("Closing the warehouse chest reports it, without waiting for the tick")
		void chestPushIsImmediate() throws Exception {
			String src = read(SCANNER);
			assertTrue(
				src.contains("ClanSessionManager.isInSession()")
					&& src.contains("isStagingKey(")
					&& src.contains("pushStagingProgress(client)"),
				"the periodic push is every ~10s, so dropping a stack off and opening the "
					+ "panel showed nothing counted — which reads as 'it did not register'"
			);
		}

		@Test
		@DisplayName("Only the warehouse triggers it, not every chest")
		void onlyWarehouseChests() throws Exception {
			String src = read(SCANNER);
			int at = src.indexOf("pushStagingProgress(client)");
			assertTrue(at > 0, "the push is missing");
			String around = src.substring(Math.max(0, at - 400), at);
			assertTrue(
				around.contains("isStagingKey("),
				"pushing on every chest open would hammer the hub while a player loots"
			);
		}
	}

	@Nested
	@DisplayName("The feed says who delivered what")
	class FeedLogsDeliveries {
		@Test
		@DisplayName("A delivery is logged")
		void deliveryIsLogged() throws Exception {
			String src = body(read(CLAN), "public static void reportDeliveredAsync(Minecraft mc, String itemId, int amount)");
			assertTrue(
				src.contains("ClanEventLog.Kind.DELIVER"),
				"the feed logged claims and arrivals but never a delivery, so a gather where "
					+ "everyone was working looked idle"
			);
		}

		@Test
		@DisplayName("Only real progress is logged, so the periodic push cannot spam it")
		void noSpamFromRepeatedPushes() throws Exception {
			String src = body(read(CLAN), "public static void reportDeliveredAsync(Minecraft mc, String itemId, int amount)");
			assertTrue(
				src.contains("int before = clanDelivered(itemId)") && src.contains("if (now > before)"),
				"the push re-reports the same warehouse totals every ~10s; logging those "
					+ "would bury the feed"
			);
		}

		@Test
		@DisplayName("Finishing an item is announced")
		void completionIsAnnounced() throws Exception {
			String src = body(read(CLAN), "public static void reportDeliveredAsync(Minecraft mc, String itemId, int amount)");
			assertTrue(
				src.contains("m.delivered >= m.need") && src.contains("clan_item_complete"),
				"the moment an item is done for the whole clan is worth saying out loud"
			);
		}
	}

	@Nested
	@DisplayName("Materials are slots, and several can be taken in one visit")
	class GridAndMultiClaim {
		@Test
		@DisplayName("The tab draws a grid, not a stack of planks")
		void drawnAsAGrid() throws Exception {
			String src = read(CLAN_SCREEN);
			assertTrue(src.contains("private static final int CELL = 24"), "no cell size");
			String draw = body(src, "private void drawMaterials(");
			assertTrue(
				draw.contains("ChestGuiStyle.drawSlot(") && draw.contains("perRow"),
				"a 30-material gather as one row each is a wall of text needing constant "
					+ "scrolling; slots are how this mod shows items everywhere else"
			);
		}

		@Test
		@DisplayName("Taking an item leaves the screen open")
		void screenStaysOpen() throws Exception {
			String clan = body(read(CLAN_SCREEN), "private void claimFromList(String itemId)");
			assertFalse(clan.contains("onClose()"), "the clan screen must not close on a claim");
			// Scoped to the block right after startQueue. Searching the whole file matched
			// those two lines somewhere else entirely, so the check passed even with the
			// branch deleted — the break test caught that.
			String chest = read(CHEST_SCREEN);
			int at = chest.indexOf("BuildGatherSession.startQueue(client, summary.itemId(), ordered);");
			assertTrue(at > 0, "the clan claim path moved — update this test");
			String after = chest.substring(at, at + 500);
			assertTrue(
				after.contains("ClanSessionManager.isInSession()") && after.contains("rebuildWidgets()"),
				"the chest panel closed on every pick, so reserving three items meant "
					+ "opening it three times"
			);
			assertTrue(
				after.contains("} else {") && after.contains("this.onClose();"),
				"solo must still close: there the pick IS the whole interaction"
			);
		}

		@Test
		@DisplayName("Items can also be taken from the summary strip")
		void summaryStripIsClickable() throws Exception {
			String src = read(CLAN_SCREEN);
			assertTrue(src.contains("private int quickAt("), "the strip is not clickable");
			assertTrue(
				src.contains("claimFromList(this.quickIds.get(q))"),
				"the strip already answered 'what should I get?'; acting on it should not "
					+ "need a tab switch"
			);
		}

		@Test
		@DisplayName("A stale hit-box cannot claim clicks for slots that are gone")
		void hitBoxIsClearedEachFrame() throws Exception {
			String src = body(read(CLAN_SCREEN), "private void drawGatherSummary(");
			assertTrue(
				src.contains("this.quickTop = -1"),
				"the strip is conditional, so its geometry must be reset every frame"
			);
		}

		@Test
		@DisplayName("Measured: the grid and its counts fit")
		void gridFits() {
			int content = 340 - 24;
			int cell = 24;
			int perRow = content / cell;
			assertEquals(13, perRow, "columns at 24px");
			assertTrue(perRow * cell <= content, "the grid runs past the panel");
			// The count sits bottom-right with 3px of padding on each side.
			int room = cell - 6;
			assertTrue(px("173") <= room, "a three-digit count must fit");
			assertTrue(px("999+") > room, "which is why 999+ was replaced");
			assertTrue(px("99k") <= room, "the compact form must fit");
		}

		@Test
		@DisplayName("Measured: the hover caption clears the back button")
		void captionClearsTheButton() {
			int panelH = 300;
			int gridBottom = panelH - 50;
			int captionTop = panelH - 48;
			int captionEnd = panelH - 38 + 8;
			int backTop = panelH - 26;
			assertTrue(gridBottom < captionTop, "the grid runs into its own caption");
			assertTrue(captionEnd <= backTop, "the caption runs into the back button");
		}
	}

	@Nested
	@DisplayName("The summary tab stopped printing over the session buttons")
	class SummaryOverlap {
		@Test
		@DisplayName("Session controls are laid out from the bottom")
		void controlsAtTheBottom() throws Exception {
			String src = read(CLAN_SCREEN);
			assertTrue(
				src.contains("int ctlTop = this.panelTop + this.panelH - 70"),
				"laid out from the top they sat exactly where the summary paints — "
					+ "'Нажми ещё' across the schematic name, 'Копировать код' across progress"
			);
		}

		@Test
		@DisplayName("Measured: the summary column ends above them")
		void summaryClearsControls() {
			int panelH = 300;
			int tabsY = 36 + 8 + 18 + 4 + 2;
			int y = tabsY + 24;
			y += 14;   // schematic name
			y += 26;   // bar + percentage
			y += 32;   // tiles
			y += 11;   // "free items" caption
			int contentEnd = y + 24 + 11;  // cells + hover caption
			assertTrue(contentEnd <= panelH - 70, "the summary still overlaps the controls");
		}
	}

	@Nested
	@DisplayName("The tab is named for what it is for")
	class Naming {
		@Test
		@DisplayName("It is not called 'Что нужно' any more")
		void renamed() throws Exception {
			String label = lang("screen.chestmemory.clan.tab_materials");
			assertFalse(
				label.equalsIgnoreCase("Что нужно"),
				"the tab is where work is picked up, not a shopping list"
			);
			assertTrue(px(label) <= (340 - 24) / 5 - 6, "the caption is clipped: " + label);
		}

		@Test
		@DisplayName("Hovering a slot says what clicking it will do")
		void hoverExplainsTheAction() throws Exception {
			String src = read(CLAN_SCREEN);
			assertTrue(
				src.contains("screen.chestmemory.clan.mat_take_hint")
					&& src.contains("screen.chestmemory.clan.mat_yours_hint"),
				"a slot carries no label, so the hover line has to carry the affordance"
			);
		}
	}
}
