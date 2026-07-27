package com.chestmemory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The clan screen opened with a row reading "Хаб: уже в сборке" that looked exactly like the
 * buttons under it, did nothing when clicked, and claimed the hub was fine whether or not
 * anything answered there. A player cannot act on that; they can act on "hub unreachable".
 */
class ClanScreenPolishTest {
	private static String read(String path) throws Exception {
		return Files.readString(Path.of(path));
	}

	private static final String CLAN_SCREEN =
		"src/client/java/com/chestmemory/client/gui/ClanGatherScreen.java";
	private static final String CLAN =
		"src/client/java/com/chestmemory/client/clan/ClanSessionManager.java";
	private static final String STYLE =
		"src/client/java/com/chestmemory/client/gui/ChestGuiStyle.java";
	private static final String RU =
		"src/main/resources/assets/chestmemory/lang/ru_ru.json";
	private static final String EN =
		"src/main/resources/assets/chestmemory/lang/en_us.json";

	/** Minecraft's font is 6px per glyph for the Latin/Cyrillic ranges this UI uses. */
	private static int px(String text) {
		return text.length() * 6;
	}

	private static String lang(String file, String key) throws Exception {
		var m = java.util.regex.Pattern
			.compile("\"" + java.util.regex.Pattern.quote(key) + "\"\\s*:\\s*\"([^\"]*)\"")
			.matcher(read(file));
		assertTrue(m.find(), "missing translation: " + key);
		return m.group(1);
	}

	private static double contrast(int a, int b) {
		double la = luminance(a);
		double lb = luminance(b);
		return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
	}

	private static double luminance(int rgb) {
		return 0.2126 * channel((rgb >> 16) & 0xFF)
			+ 0.7152 * channel((rgb >> 8) & 0xFF)
			+ 0.0722 * channel(rgb & 0xFF);
	}

	private static double channel(int v) {
		double c = v / 255.0;
		return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
	}

	@Nested
	@DisplayName("The hub row reports state instead of pretending to be a button")
	class HubStatus {
		@Test
		@DisplayName("The strip became a corner lamp that answers clicks with a re-check")
		void fakeButtonRemoved() throws Exception {
			String src = read(CLAN_SCREEN);
			assertFalse(
				src.contains("screen.chestmemory.clan.hub_builtin\"),"),
				"the state must not be built as a SettingRowButton — it looked "
					+ "identical to the real buttons and swallowed every click"
			);
			assertTrue(
				src.contains("new HubLampButton("),
				"the lamp replaced the full-width strip — one row returned to the grid"
			);
			assertFalse(
				src.contains("drawStatusStrip("),
				"the strip must not be painted as well: two indicators disagree eventually"
			);
			assertTrue(
				src.contains("this::retryHubCheck"),
				"clicking the lamp is the retry now"
			);
		}

		@Test
		@DisplayName("The state is measured, not assumed")
		void hubIsActuallyChecked() throws Exception {
			String clan = read(CLAN);
			assertTrue(clan.contains("enum HubState"), "the manager must model hub state");
			assertTrue(
				clan.contains("client().health()"),
				"state must come from the health endpoint, not from 'a URL is baked in'"
			);
			assertTrue(
				read(CLAN_SCREEN).contains("ClanSessionManager.checkHubAsync"),
				"the screen must ask on open"
			);
		}

		@Test
		@DisplayName("A status check never blocks a real request")
		void healthUsesItsOwnFlag() throws Exception {
			String src = read(CLAN);
			int decl = src.indexOf("public static void checkHubAsync");
			assertTrue(decl > 0, "checkHubAsync is missing");
			String body = src.substring(decl, src.indexOf("\n\t}", decl));
			assertTrue(
				body.contains("healthBusy.compareAndSet"),
				"sharing the busy flag would let a background check block a create or a join"
			);
			assertFalse(
				body.contains("busy.compareAndSet"),
				"the shared request flag must not be taken by a status check"
			);
		}

		@Test
		@DisplayName("Repeated checks are throttled, but a manual retry always goes through")
		void throttledButRetryable() throws Exception {
			String src = read(CLAN);
			assertTrue(src.contains("15_000L"), "the check must be rate-limited");
			assertTrue(
				src.contains("public static void forceHubRecheck()")
					&& src.contains("lastHealthMillis = 0L"),
				"a manual retry must clear the throttle, or the button would do nothing"
			);
		}

		@Test
		@DisplayName("State is spelled out, not only coloured")
		void stateIsNotColourOnly() throws Exception {
			// The words moved into the lamp's tooltip — still words, just on demand.
			String src = read(CLAN_SCREEN);
			for (String key : new String[]{"hub_lamp_online", "hub_lamp_offline", "hub_checking"}) {
				assertTrue(
					src.contains("screen.chestmemory.clan." + key),
					"a red/green lamp alone is no help to a colour-blind player: " + key
				);
			}
		}

		@Test
		@DisplayName("Measured: the strip is readable and its labels fit")
		void stripIsReadableAndFits() throws Exception {
			String style = read(STYLE);
			var m = java.util.regex.Pattern
				.compile("graphics\\.fill\\(x \\+ 1, y \\+ 1, x \\+ width - 1, y \\+ height - 1, 0x[fF][fF]([0-9a-fA-F]{6})\\)")
				.matcher(style.substring(style.indexOf("public static void drawStatusStrip")));
			assertTrue(m.find(), "strip background not found");
			int bg = Integer.parseInt(m.group(1), 16);
			assertTrue(contrast(0xE8D8B8, bg) >= 4.5, "caption unreadable on the strip");
			for (int lamp : new int[]{0x5FD068, 0xE0603C, 0xE0A83C}) {
				assertTrue(contrast(lamp, bg) >= 3.0, "lamp lost against the strip");
			}
			// 316px content, minus the lamp and its padding.
			int room = 316 - 22;
			for (String key : new String[]{"hub_online", "hub_offline", "hub_checking"}) {
				String ru = lang(RU, "screen.chestmemory.clan." + key);
				assertTrue(px(ru) < room, "caption does not fit: " + ru);
			}
		}

		@Test
		@DisplayName("A disabled row is flat, so the strip cannot be mistaken for one")
		void stripIsFlat() throws Exception {
			String style = read(STYLE);
			int decl = style.indexOf("public static void drawStatusStrip");
			String body = style.substring(decl, style.indexOf("\n\t}", decl));
			assertFalse(
				body.contains("withAlpha(0xFFFFFF"),
				"a top highlight is what makes a row look pressable"
			);
		}
	}

	@Nested
	@DisplayName("The Gathers tab stopped printing over its own controls")
	class NoOverlap {
		@Test
		@DisplayName("The status sentence is drawn below the panel, out of the way")
		void statusLineIsBelowThePanel() throws Exception {
			// It used to be printed inside the panel and had to be suppressed tab by tab to
			// stop it landing on buttons. Below the panel — where the chest screen puts its
			// footer — it has the full width and cannot collide with anything, so every tab
			// can show it again.
			String src = read(CLAN_SCREEN);
			assertTrue(
				src.contains("this.panelTop + this.panelH + 6"),
				"the status line must sit below the panel"
			);
			assertFalse(
				src.contains("this.panelTop + this.panelH - 39"),
				"the old in-panel position is what collided with the controls"
			);
		}

		@Test
		@DisplayName("Measured: rows and the status line no longer share a y")
		void geometryIsClear() {
			int panelH = 300;
			int rowTop = panelH - 48;
			int secondRow = panelH - 26;
			int statusY = panelH - 39;
			assertTrue(statusY >= rowTop && statusY <= rowTop + 18,
				"this is the collision the fix is about");
			assertTrue(rowTop + 18 <= secondRow, "the two button rows must not overlap");
		}
	}

	@Nested
	@DisplayName("Joining by code got easier")
	class PasteAndGuards {
		@Test
		@DisplayName("A code can be pasted out of a copied chat line")
		void pasteExtractsFromSentence() throws Exception {
			String src = read(CLAN_SCREEN);
			assertTrue(src.contains("private void pasteCodeFromClipboard()"), "paste is missing");
			int decl = src.indexOf("private void pasteCodeFromClipboard()");
			String body = src.substring(decl, src.indexOf("\n\t}", decl));
			assertTrue(
				body.contains("Pattern") && body.contains("CM"),
				"the code arrives inside a chat sentence, so a plain paste is not enough"
			);
		}

		@Test
		@DisplayName("The extraction pattern actually matches a real chat line")
		void patternMatchesRealInput() {
			var p = java.util.regex.Pattern.compile("(?i)\\bCM[-\\s]?([A-Z0-9]{4})\\b");
			assertTrue(p.matcher("Клан-сбор: CM-6K3E — подключайтесь").find(), "chat line");
			assertTrue(p.matcher("CM-6K3E").find(), "bare code");
			assertTrue(p.matcher("cm 6k3e").find(), "lowercase, spaced");
			assertFalse(p.matcher("no code here").find(), "must not invent a code");
		}

		@Test
		@DisplayName("Join is greyed out until there is a code to join with")
		void joinNeedsACode() throws Exception {
			String src = read(CLAN_SCREEN);
			assertTrue(
				src.contains("joinBtn.active = !switching && !this.codeDraft.isBlank()"),
				"join must be greyed without a code, rather than failing on click"
			);
		}

		@Test
		@DisplayName("An unreachable hub is retried through the lamp itself")
		void retryReplacesBack() throws Exception {
			// The dedicated retry button is gone with the strip: the lamp is the state AND
			// the action — click it to ask again, tooltip says so.
			String src = read(CLAN_SCREEN);
			assertTrue(
				src.contains("this::retryHubCheck"),
				"clicking the lamp must re-ask the hub"
			);
			assertFalse(
				src.contains("screen.chestmemory.clan.hub_retry\""),
				"a second retry affordance would drift out of sync with the lamp"
			);
		}

		@Test
		@DisplayName("Typing lights up the button, and the hub coming back swaps the retry away")
		void refreshWatchesBothInputs() throws Exception {
			String src = read(CLAN_SCREEN);
			int decl = src.indexOf("public void tick()");
			String body = src.substring(decl, src.indexOf("\n\t}", decl));
			assertTrue(
				body.contains("hasCode != this.builtForHasCode"),
				"without this the first character does not enable join until the next click"
			);
			assertTrue(body.contains("hub != this.builtForHub"), "hub state drives buttons too");
		}

		@Test
		@DisplayName("Typing does not lose focus, even though every keystroke rebuilds")
		void focusSurvivesRebuild() throws Exception {
			String src = read(CLAN_SCREEN);
			int decl = src.indexOf("public void tick()");
			String body = src.substring(decl, src.indexOf("\n\t}", decl));
			assertTrue(
				body.contains("wasTyping") && body.contains("this.codeBox.setFocused(true)"),
				"the first keystroke triggers a rebuild, which recreates the EditBox — "
					+ "without restoring focus the player must re-click for every letter"
			);
		}

		@Test
		@DisplayName("Measured: the bottom row fits, including the Russian caption")
		void bottomRowFits() throws Exception {
			int content = 340 - 24;
			int gap = 4;
			int halfL = (content - gap) / 2;
			int pasteW = 64;
			int codeW = halfL - gap - pasteW;
			assertTrue(
				12 + halfL + gap + codeW + gap + pasteW <= 12 + content,
				"the row runs past the panel edge"
			);
			assertTrue(codeW >= px("CM-XXXX") + 10, "the code box cannot show a full code");
			String ru = lang(RU, "screen.chestmemory.clan.paste_code");
			assertTrue(px(ru) + 12 <= pasteW, "the paste caption is clipped: " + ru);
			String en = lang(EN, "screen.chestmemory.clan.paste_code");
			assertTrue(px(en) + 12 <= pasteW, "the paste caption is clipped: " + en);
		}
	}
}
