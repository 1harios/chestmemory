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
 * Four complaints from a live clan gather, each with a different root cause.
 * <p>
 * The one that mattered most: a member who joined by code could not open the gather at all.
 * Their materials come from the hub, but the button asked Litematica — which has nothing,
 * because the schematic was opened by the host on another machine.
 */
class ClanUsabilityTest {
	private static String read(String path) throws Exception {
		return Files.readString(Path.of(path));
	}

	private static final String ACCESS =
		"src/client/java/com/chestmemory/client/litematica/LitematicaAccess.java";
	private static final String SCREEN =
		"src/client/java/com/chestmemory/client/gui/ChestMemoryScreen.java";
	private static final String CLAN_SCREEN =
		"src/client/java/com/chestmemory/client/gui/ClanGatherScreen.java";
	private static final String CLAN =
		"src/client/java/com/chestmemory/client/clan/ClanSessionManager.java";
	private static final String STYLE =
		"src/client/java/com/chestmemory/client/gui/ChestGuiStyle.java";

	/** Contrast ratio per WCAG 2.1, used to check colours by measurement rather than by eye. */
	private static double contrast(int a, int b) {
		double la = luminance(a);
		double lb = luminance(b);
		return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
	}

	private static double luminance(int rgb) {
		double r = channel((rgb >> 16) & 0xFF);
		double g = channel((rgb >> 8) & 0xFF);
		double b = channel(rgb & 0xFF);
		return 0.2126 * r + 0.7152 * g + 0.0722 * b;
	}

	private static double channel(int v) {
		double c = v / 255.0;
		return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
	}

	/** Saturation (HSV), which is what separates dead grey from live wood. */
	private static double saturation(int rgb) {
		int r = (rgb >> 16) & 0xFF;
		int g = (rgb >> 8) & 0xFF;
		int b = rgb & 0xFF;
		int max = Math.max(r, Math.max(g, b));
		int min = Math.min(r, Math.min(g, b));
		return max == 0 ? 0 : (max - min) / (double) max;
	}

	private static int constant(String src, String name) {
		var m = java.util.regex.Pattern
			.compile("int\\s+" + name + "\\s*=\\s*0x[fF][fF]([0-9a-fA-F]{6})")
			.matcher(src);
		assertTrue(m.find(), "constant not found: " + name);
		return Integer.parseInt(m.group(1), 16);
	}

	@Nested
	@DisplayName("A member who joined by code can open the gather")
	class MemberCanGather {
		@Test
		@DisplayName("Clan materials count as a material list")
		void clanMaterialsAreAList() throws Exception {
			String src = read(ACCESS);
			int check = src.indexOf("hasClanMaterials()");
			int available = src.indexOf("if (!isAvailable())");
			assertTrue(check > 0, "hasActiveMaterialList must consult the clan session");
			assertTrue(
				check < available,
				"the clan check must come BEFORE the isAvailable() bail-out — a member has "
					+ "materials from the hub and may not even have Litematica installed"
			);
		}

		@Test
		@DisplayName("Entering a gather does not demand Litematica during a clan gather")
		void clanGatherSkipsLitematicaGate() throws Exception {
			String src = read(SCREEN);
			assertTrue(
				src.contains("boolean clanGather = com.chestmemory.client.clan.ClanSessionManager.isInSession()")
					&& src.contains("if (!clanGather && !LitematicaAccess.isAvailable())"),
				"enterGatherMode must let a clan gather through without Litematica"
			);
		}

		@Test
		@DisplayName("The list is what is LEFT, so delivered items are not gathered twice")
		void clanListUsesRemaining() throws Exception {
			String src = read(ACCESS);
			assertTrue(
				src.contains("session.remaining(e.getKey())"),
				"clan materials must use remaining(), not need(): teammates' deliveries "
					+ "must not be gathered again"
			);
		}

		@Test
		@DisplayName("A finished gather still opens instead of claiming there is no list")
		void finishedGatherStillOpens() throws Exception {
			String src = read(ACCESS);
			int decl = src.indexOf("private static boolean hasClanMaterials()");
			assertTrue(decl > 0, "hasClanMaterials is missing");
			String body = src.substring(decl, src.indexOf('}', decl));
			assertTrue(
				body.contains("!session.materials.isEmpty()") && !body.contains("remaining"),
				"presence of a list must not depend on anything still being missing"
			);
		}

		@Test
		@DisplayName("No infinite recursion between the list and its fallback")
		void noRecursion() throws Exception {
			String src = read(ACCESS);
			int decl = src.indexOf("private static @Nullable List<LitematicaCompat.MaterialNeed> clanMaterials()");
			assertTrue(decl > 0, "clanMaterials is missing");
			String body = src.substring(decl, src.indexOf("\n\t}", decl));
			assertFalse(
				body.contains("missingMaterials()") || body.contains("hasActiveMaterialList()"),
				"clanMaterials is called BY those methods; calling them back would recurse forever"
			);
		}
	}

	@Nested
	@DisplayName("The Gathers tab no longer prints text over its own buttons")
	class NoOverlap {
		@Test
		@DisplayName("The list bottom comes from the buttons, not from a hard-coded offset")
		void listBottomIsDerived() throws Exception {
			String src = read(CLAN_SCREEN);
			assertTrue(
				src.contains("this.listBottom = rowTop - 6"),
				"listBottom must be set where the buttons are placed, so the two cannot drift"
			);
			assertTrue(
				src.contains("int bottom = this.listBottom > 0"),
				"the drawing code must use the recorded bottom"
			);
		}

		@Test
		@DisplayName("Outside a session the tab lays its buttons out at the bottom")
		void outOfSessionUsesBottomRow() throws Exception {
			String src = read(CLAN_SCREEN);
			int guard = src.indexOf("if (this.tab != TAB_GATHER && this.tab != TAB_LIST)");
			int branch = src.indexOf("if (this.tab == TAB_LIST)", guard);
			assertTrue(branch > guard, "the out-of-session Gathers branch is missing");
			String body = src.substring(branch, branch + 2600);
			assertTrue(
				body.contains("int rowTop = this.panelTop + this.panelH - 48"),
				"controls must sit at the bottom; laying them from the top is what printed "
					+ "the empty-list caption across the create button"
			);
		}

		@Test
		@DisplayName("Measured: the list ends above the first button row")
		void geometryDoesNotOverlap() {
			int panelH = 300;
			int panelTop = 0;
			int tabsY = panelTop + 36 + 8 + 18 + 4 + 2;
			int listTop = tabsY + 22;
			int rowTop = panelTop + panelH - 48;
			int listBottom = rowTop - 6;
			assertTrue(listBottom < rowTop, "the list runs under the buttons");
			assertTrue(listTop < listBottom, "the list has no height left");
			assertEquals(6, rowTop - listBottom, "gap between list and buttons");
		}

		@Test
		@DisplayName("The empty-state caption stays inside the list area")
		void emptyStateFitsInsideTheList() {
			int panelH = 300;
			int tabsY = 36 + 8 + 18 + 4 + 2;
			int listTop = tabsY + 22;
			int listBottom = panelH - 48 - 6;
			int mid = listTop + (listBottom - listTop) / 2;
			int caption = mid - 8;
			int hint = mid + 4;
			assertTrue(caption > listTop, "caption is above the list area");
			assertTrue(hint + 8 < listBottom, "hint runs into the buttons");
		}
	}

	@Nested
	@DisplayName("A button that cannot be pressed looks different")
	class DisabledIsVisible {
		@Test
		@DisplayName("Measured: disabled is desaturated, not just another brown")
		void disabledIsDesaturated() throws Exception {
			String src = read(STYLE);
			int row = constant(src, "ROW_WOOD");
			int disabled = constant(src, "ROW_WOOD_DISABLED");
			// The old value was 0x2A2018 — 1.03:1 against ROW_WOOD, effectively invisible.
			// Wood is deeply saturated, so draining the colour is what reads as "dead"; the
			// palette is too dark for brightness alone to carry the difference.
			assertTrue(
				saturation(row) - saturation(disabled) > 0.4,
				"disabled must be clearly less saturated than wood: "
					+ saturation(row) + " vs " + saturation(disabled)
			);
			assertTrue(
				contrast(row, disabled) > 1.15,
				"disabled must also differ in brightness: " + contrast(row, disabled) + ":1"
			);
		}

		@Test
		@DisplayName("Measured: the disabled caption is still readable")
		void disabledTextStaysReadable() throws Exception {
			String src = read(STYLE);
			int disabled = constant(src, "ROW_WOOD_DISABLED");
			int text = constant(src, "TEXT_DISABLED");
			double ratio = contrast(text, disabled);
			assertTrue(ratio >= 4.5, "WCAG AA requires 4.5:1, got " + ratio + ":1");
		}

		@Test
		@DisplayName("A disabled row is flat: the bevel is what makes it look pressable")
		void disabledRowHasNoBevel() throws Exception {
			String src = read(STYLE);
			int decl = src.indexOf("public static void drawSettingRow");
			String body = src.substring(decl, src.indexOf("\n\t}", decl));
			assertTrue(
				body.contains("if (enabled) {"),
				"the highlight and shadow must be drawn only when the row is enabled"
			);
		}

		@Test
		@DisplayName("The create button is greyed out when there is no schematic to create from")
		void createIsDisabledWithoutASchematic() throws Exception {
			String src = read(CLAN_SCREEN);
			assertTrue(
				src.contains("createBtn.active = canCreate"),
				"create must be disabled rather than failing after the click"
			);
			assertTrue(
				src.contains("screen.chestmemory.clan.create_need_list"),
				"a disabled button has to say why"
			);
		}
	}

	@Nested
	@DisplayName("Switching gathers is visible, safe and not abrupt")
	class SwitchingIsSmooth {
		@Test
		@DisplayName("Nothing is torn down before the new gather arrives")
		void teardownWaitsForSuccess() throws Exception {
			String src = read(CLAN);
			int decl = src.indexOf("public static void switchToAsync");
			String body = src.substring(decl, src.indexOf("\n\tpublic ", decl + 10));
			assertFalse(
				body.contains("BuildGatherSession.clear()") || body.contains("clearStaging()"),
				"switchToAsync must not destroy the current gather up front: a slow or failed "
					+ "switch left the player with no gather at all"
			);
			assertTrue(
				src.contains("if (differentGather) {"),
				"the queue must be cleared once the new gather has actually arrived"
			);
		}

		@Test
		@DisplayName("The switch is shown while it runs")
		void switchingIsObservable() throws Exception {
			String clan = read(CLAN);
			String screen = read(CLAN_SCREEN);
			assertTrue(clan.contains("switchingTo"), "the manager must expose the pending code");
			assertTrue(
				screen.contains("screen.chestmemory.clan.switching"),
				"the row being switched to must be labelled"
			);
		}

		@Test
		@DisplayName("A second click during a switch is ignored")
		void doubleSwitchIsRejected() throws Exception {
			String screen = read(CLAN_SCREEN);
			int decl = screen.indexOf("public boolean mouseClicked");
			String body = screen.substring(decl, decl + 900);
			assertTrue(
				body.contains("ClanSessionManager.switchingTo() != null"),
				"clicking another row mid-switch must not start a second one"
			);
			String clan = read(CLAN);
			int sw = clan.indexOf("public static void switchToAsync");
			assertTrue(
				clan.substring(sw, clan.indexOf("\n\tpublic ", sw + 10)).contains("if (busy.get())"),
				"switchToAsync must also guard itself, not rely on the screen"
			);
		}

		@Test
		@DisplayName("No request drops its callback and leaves the screen on 'working…'")
		void callbacksAlwaysRun() throws Exception {
			String src = read(CLAN);
			// Scanned line by line rather than by regex: one guard reads
			// "!client().isConfigured() || !busy.compareAndSet(...)", and a pattern that
			// stopped at the first bracket silently skipped it and under-counted.
			String[] lines = src.split("\n");
			int checked = 0;
			for (int i = 0; i < lines.length; i++) {
				if (!lines[i].contains("busy.compareAndSet(false, true)")) {
					continue;
				}
				checked++;
				StringBuilder block = new StringBuilder();
				for (int j = i + 1; j < Math.min(lines.length, i + 8); j++) {
					if (lines[j].trim().equals("}")) {
						break;
					}
					block.append(lines[j]);
				}
				if (!block.toString().contains("onDone")) {
					// The poll and the resume retry have no caller waiting on them; every
					// user-facing request does, and returning without running its callback
					// left the screen showing "working…" forever.
					String before = src.substring(0, src.indexOf(lines[i]));
					int at = before.lastIndexOf("static void ");
					String owner = src.substring(at, src.indexOf('(', at));
					assertTrue(
						owner.contains("poll") || owner.contains("resume"),
						"drops its callback and freezes the screen on 'working…': " + owner
					);
				}
			}
			assertEquals(5, checked, "expected to inspect every busy guard");
		}

		@Test
		@DisplayName("The screen refreshes when the switch finishes, without polling 20x/s")
		void screenRefreshesOnStateChange() throws Exception {
			String src = read(CLAN_SCREEN);
			assertTrue(src.contains("public void tick()"), "the screen needs a tick to notice");
			int decl = src.indexOf("public void tick()");
			String body = src.substring(decl, src.indexOf("\n\t}", decl));
			assertTrue(
				body.contains("builtForSwitching") && body.contains("rebuildWidgets()"),
				"the rebuild must be driven by a state change"
			);
			// The rebuild must sit behind a condition. Checking the text shape was brittle;
			// what matters is that the call is guarded by the comparison against the state
			// the widgets were last built for.
			int rebuild = body.indexOf("this.rebuildWidgets()");
			int guard = body.indexOf("if (!java.util.Objects.equals(switching, this.builtForSwitching)");
			assertTrue(guard > 0, "the rebuild must be guarded by a state comparison");
			assertTrue(
				guard < rebuild,
				"an unconditional rebuild every tick fights with typing"
			);
		}

		@Test
		@DisplayName("A rebuild does not erase the code being typed")
		void typedCodeSurvivesRebuild() throws Exception {
			String src = read(CLAN_SCREEN);
			assertTrue(src.contains("private String codeDraft"), "the draft must be kept");
			int boxes = src.split("new EditBox\\(", -1).length - 1;
			int restores = src.split("setValue\\(this\\.codeDraft\\)", -1).length - 1;
			int responders = src.split("this\\.codeDraft = v", -1).length - 1;
			// One EditBox is the hub URL field, which has its own value.
			assertEquals(boxes - 1, restores, "every code box must restore the draft");
			assertEquals(boxes - 1, responders, "every code box must record what is typed");
		}
	}
}
