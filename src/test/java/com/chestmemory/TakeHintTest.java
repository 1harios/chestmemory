package com.chestmemory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The take-this-much hint over an open container, and the mod's own signature.
 * <p>
 * The hint's real bug was its lifetime: everything in that overlay hung off the highlight
 * timer, so half a minute into standing at a chest the slot tint and the count both vanished
 * while the gather was still running.
 */
class TakeHintTest {
	private static final String HINT =
		"src/client/java/com/chestmemory/client/highlight/SlotHighlighter.java";
	private static final String HIGHLIGHTER =
		"src/client/java/com/chestmemory/client/highlight/ChestHighlighter.java";
	private static final String SESSION =
		"src/client/java/com/chestmemory/client/litematica/BuildGatherSession.java";
	private static final String SETTINGS =
		"src/client/java/com/chestmemory/client/data/ModSettings.java";
	private static final String SETTINGS_SCREEN =
		"src/client/java/com/chestmemory/client/gui/ChestMemorySettingsScreen.java";
	private static final String ACCESSOR =
		"src/client/java/com/chestmemory/client/mixin/AbstractContainerScreenAccessor.java";
	private static final String MOD_JSON = "src/main/resources/fabric.mod.json";
	private static final String README = "README.md";
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
	@DisplayName("The hint lives as long as the gather, not as long as the glow")
	class Lifetime {
		@Test
		@DisplayName("A gather target marks slots even after the highlight has expired")
		void gatherOutlivesTheTimer() throws Exception {
			String hint = read(HINT);
			assertTrue(
				hint.contains("boolean fromHighlight = itemId != null;"),
				"the two sources have to be told apart — they have different lifetimes"
			);
			assertTrue(
				hint.contains("if (itemId == null && BuildGatherSession.isActive()) {")
					&& hint.contains("itemId = BuildGatherSession.currentItemId();"),
				"an expired highlight must not blank the overlay while a gather is running"
			);
		}

		@Test
		@DisplayName("Nothing re-arms the highlight on a timer, which is why this was needed")
		void highlightIsNotRefreshed() throws Exception {
			// highlightCurrent is called from focusItem — on start, on advance, on a click. No
			// tick refreshes it, so the glow really does lapse mid-gather. If a future change
			// starts renewing it, this test is the note explaining why that would be a
			// different design rather than a bug fix.
			String session = read(SESSION);
			int refresh = session.indexOf("ChestHighlighter.refreshDuration(");
			assertTrue(refresh > 0, "the duration is set where the target is focused");
			assertTrue(
				body(session, "private static void highlightCurrent(").contains("highlightItem("),
				"focusing a material is what arms the glow"
			);
			assertTrue(
				read(HIGHLIGHTER).contains("public static float remainingSeconds()"),
				"the glow is a countdown, and that is fine — it is the marking that must not be"
			);
		}

		@Test
		@DisplayName("A timed highlight still pulses and fades; a gather target is steady")
		void steadyForGather() throws Exception {
			String hint = read(HINT);
			assertTrue(
				hint.contains("fromHighlight ? 0.8F + 0.2F * (float) Math.sin(now / 280.0) : 0.7F"),
				"a fade tells the player the glow is about to stop; a gather has nothing to fade to"
			);
			assertTrue(
				hint.contains("fromHighlight && remain < 4.0F"),
				"the fade must not apply to a marking that is not counting down"
			);
		}
	}

	@Nested
	@DisplayName("The hint says what, how much, and whether it is here")
	class Content {
		@Test
		@DisplayName("Icon, name, remainder and stacks all appear")
		void richContent() throws Exception {
			String draw = body(read(HINT), "private static void drawTakeHint(");
			assertTrue(draw.contains("graphics.item(icon"), "the item's own icon");
			assertTrue(draw.contains("itemDisplayName(itemId)"), "and its name");
			assertTrue(draw.contains("BulkTooltip.stacksText(bulk)"), "the remainder in stacks");
			assertTrue(
				draw.contains("bulk.hasStack()"),
				"below one stack there is nothing to say — a tool must not read as stacks"
			);
		}

		@Test
		@DisplayName("What this container holds is stated and coloured by whether it covers the need")
		void hereLine() throws Exception {
			String draw = body(read(HINT), "private static void drawTakeHint(");
			assertTrue(
				draw.contains("inThisContainer >= stillNeed ? 0xFF7FE08A"),
				"green when this chest finishes the job"
			);
			assertTrue(
				draw.contains("inThisContainer > 0 ? 0xFFFFE066"),
				"yellow for part of it"
			);
			assertTrue(draw.contains("hint.chestmemory.here_none"), "and a word for none at all");
			assertTrue(
				read(HINT).contains("int inThisContainer = 0;"),
				"the total has to be summed from the container's own slots"
			);
			assertFalse(
				read(HINT).contains("String hint = \"↓ \" + stillNeed;"),
				"the bare number it replaced said nothing the HUD did not already say"
			);
		}

		@Test
		@DisplayName("The box stays on screen at every position")
		void staysOnScreen() throws Exception {
			String draw = body(read(HINT), "private static void drawTakeHint(");
			assertTrue(draw.contains("if (y < 1)"), "above the window must not fall off the top");
			assertTrue(
				draw.contains("y + h > screen.height - 1"),
				"below the window must not fall off the bottom"
			);
			assertTrue(
				draw.contains("x + w > screen.width - 1"),
				"a long item name on a narrow window pushed the box past the right edge"
			);
			assertTrue(
				read(ACCESSOR).contains("chestmemory$getImageHeight()"),
				"placing it under the window needs the window's height"
			);
		}
	}

	@Nested
	@DisplayName("The hint has its own switch")
	class Settings {
		@Test
		@DisplayName("Tint and hint are separate, and each is honoured on its own")
		void separateSwitches() throws Exception {
			String hint = read(HINT);
			assertTrue(
				hint.contains("boolean wantTint = ModSettings.get().highlightSlots();")
					&& hint.contains("boolean wantHint = ModSettings.get().gatherSlotHint();"),
				"the tint marks WHICH slots, the hint says WHAT and HOW MUCH — different questions"
			);
			assertTrue(
				hint.contains("if (!wantTint && !wantHint) {"),
				"both off is the only case where there is nothing to draw"
			);
			assertTrue(
				hint.contains("if (!wantTint) {\n\t\t\t\tbreak;\n\t\t\t}"),
				"the slot loop has to stop when only the hint is wanted"
			);
			assertTrue(
				hint.contains("if (wantHint && stillNeed > 0)"),
				"and the hint must not depend on the tint's switch"
			);
		}

		@Test
		@DisplayName("Position is a setting, clamped, and reachable on the gather tab")
		void positionSetting() throws Exception {
			assertTrue(
				body(read(SETTINGS), "public int gatherSlotHintPos()").contains("Math.min(2"),
				"clamped on read: the settings file is hand-editable"
			);
			String screen = read(SETTINGS_SCREEN);
			assertTrue(screen.contains("settings.row.slot_hint\""), "the switch is on the tab");
			assertTrue(screen.contains("settings.row.slot_hint_pos"), "and the position cycle");
			assertTrue(
				screen.contains("() -> ModSettings.get().gatherSlotHint());"),
				"choosing a position for a hidden hint is pointless — the row greys out"
			);
		}
	}

	/**
	 * The footer of the settings sheet, checked by arithmetic rather than by eye.
	 * <p>
	 * The signature shipped overlapping the Done button by exactly one pixel row and touching
	 * the frame below it, because three hand-written offsets had to agree and did not.
	 * Re-deriving them from the source and doing the sums here is the only way this stays
	 * fixed: a future tweak to one padding cannot quietly re-create the collision.
	 */
	@Nested
	@DisplayName("The settings footer fits")
	class Footer {
		/** Reads `private static final int NAME = 12;` back out of the screen. */
		private int constant(String src, String name) {
			var m = java.util.regex.Pattern
				.compile("private static final int " + name + " = (\\d+);")
				.matcher(src);
			assertTrue(m.find(), "no such constant: " + name);
			return Integer.parseInt(m.group(1));
		}

		@Test
		@DisplayName("The button and the signature cannot share a pixel row")
		void noOverlap() throws Exception {
			String src = read(SETTINGS_SCREEN);
			int pad = constant(src, "FOOTER_PAD");
			int creditsH = constant(src, "CREDITS_H");
			int doneH = constant(src, "DONE_H");
			int gap = constant(src, "FOOTER_GAP");
			int contentGap = constant(src, "CONTENT_GAP");

			int creditsUp = pad + creditsH;
			int doneUp = creditsUp + gap + doneH;
			int viewBottomUp = doneUp + contentGap;

			// Measured up from the panel's bottom edge. drawSettingRow fills rows [y, y+h),
			// so the button's last occupied row is doneUp - doneH + 1 above the foot.
			int buttonLastRow = doneUp - doneH + 1;
			assertTrue(
				buttonLastRow > creditsUp,
				"the button's last row (" + buttonLastRow + " above the foot) must sit above the "
					+ "signature's first (" + creditsUp + ") — at -26/-9 they were the same row"
			);
			assertTrue(
				creditsH + pad == creditsUp && pad >= 3,
				"the signature needs real clearance under it, not the 1px the frame left"
			);
			assertTrue(
				viewBottomUp >= doneUp + 1,
				"the scrolling viewport has to end above the button, or a row rides over it"
			);
		}

		@Test
		@DisplayName("All three positions come from the constants, not from fresh literals")
		void derivedNotGuessed() throws Exception {
			String src = read(SETTINGS_SCREEN);
			assertTrue(src.contains("panelH - VIEW_BOTTOM_UP"), "viewport is derived");
			assertTrue(src.contains("panelH - DONE_UP"), "button is derived");
			assertTrue(src.contains("panelH - CREDITS_UP"), "signature is derived");
			for (String old : new String[]{
				"panelH - 32", "panelH - 26", "panelH - 9", "panelH - 40",
			}) {
				assertFalse(
					src.contains(old),
					"the hand-tuned offset " + old + " is what broke the layout"
				);
			}
		}

		@Test
		@DisplayName("The rebind prompt shares the signature's line instead of covering a row")
		void promptSharesTheLine() throws Exception {
			String draw = body(read(SETTINGS_SCREEN), "private void drawFooter(");
			assertTrue(
				draw.contains("boolean rebinding = this.listeningKey != null;"),
				"one line, two possible tenants"
			);
			assertTrue(
				draw.contains("screen.chestmemory.settings.key_wait")
					&& draw.contains("screen.chestmemory.credits"),
				"both strings are chosen in the same place, so neither can be mispositioned"
			);
			assertTrue(
				draw.contains("ChestGuiStyle.ellipsize(this.font, line, this.contentW)"),
				"a longer translation must not run over the frame"
			);
			assertTrue(draw.contains("ChestGuiStyle.HEADER_LINE"), "the footer gets its own groove");
		}
	}

	@Nested
	@DisplayName("The mod is signed")
	class Branding {
		@Test
		@DisplayName("Clan and author appear in the metadata")
		void metadata() throws Exception {
			String json = read(MOD_JSON);
			assertTrue(json.contains("Sunshine's Dels"), "the clan names the mod");
			assertTrue(json.contains("\"Karandash\""), "the author is credited");
			assertTrue(
				json.contains("chestmemory:clan"),
				"a machine-readable clan field, so other tooling can read it too"
			);
		}

		@Test
		@DisplayName("And in the panel, the settings sheet and the README")
		void inGameAndDocs() throws Exception {
			for (String file : new String[]{RU, EN}) {
				String src = read(file);
				assertTrue(
					src.contains("Sunshine's Dels"),
					file + " must carry the clan name in the panel title"
				);
				assertTrue(src.contains("screen.chestmemory.credits"), "and the credits line");
			}
			assertTrue(
				read(SETTINGS_SCREEN).contains("screen.chestmemory.credits"),
				"the settings sheet draws the signature"
			);
			assertTrue(
				read(README).startsWith("# Chest Memory — Sunshine's Dels"),
				"the README leads with it"
			);
		}

		@Test
		@DisplayName("New strings exist in both languages")
		void bothLanguages() throws Exception {
			String ru = read(RU);
			String en = read(EN);
			for (String key : new String[]{
				"hint.chestmemory.here",
				"hint.chestmemory.here_none",
				"screen.chestmemory.settings.row.slot_hint",
				"screen.chestmemory.settings.row.slot_hint_pos",
				"screen.chestmemory.settings.hint_pos.above",
				"screen.chestmemory.settings.hint_pos.inside",
				"screen.chestmemory.settings.hint_pos.below",
				"screen.chestmemory.credits",
			}) {
				assertTrue(ru.contains('"' + key + '"'), "ru_ru is missing " + key);
				assertTrue(en.contains('"' + key + '"'), "en_us is missing " + key);
			}
		}
	}
}
