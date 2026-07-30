package com.chestmemory;

import com.chestmemory.client.util.LegacyColors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The five complaints from the second round of play-testing: the creator could not leave
 * their own gather, the activity feed leaked between gathers, the gather list reordered
 * itself under the cursor, sessions expired while people were still playing, and gradient
 * item names came out as one flat colour.
 * <p>
 * {@link LegacyColors} is exercised for real — it was split out of LegacyText precisely so
 * that the name-rewriting could be tested without the game on the classpath. The rest is
 * source inspection, the convention the sibling suites established.
 */
class GatherPolishTest {
	private static final String MANAGER =
		"src/client/java/com/chestmemory/client/clan/ClanSessionManager.java";
	private static final String SCREEN =
		"src/client/java/com/chestmemory/client/gui/ClanGatherScreen.java";
	private static final String FEED =
		"src/client/java/com/chestmemory/client/clan/ClanEventLog.java";
	private static final String ROSTER =
		"src/client/java/com/chestmemory/client/clan/ClanRoster.java";
	private static final String KEYS =
		"src/client/java/com/chestmemory/client/data/ItemStackKeys.java";
	private static final String LEGACY =
		"src/client/java/com/chestmemory/client/util/LegacyText.java";
	private static final String HUB = "hub/clan_hub.py";
	private static final String HUB_PHP = "hub/public/index.php";
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

	private static String pyBody(String src, String signature) {
		int at = src.indexOf(signature);
		assertTrue(at > 0, "not found: " + signature);
		int method = src.indexOf("\n    def ", at + 1);
		int top = src.indexOf("\ndef ", at + 1);
		int end = method < 0 ? top : (top < 0 ? method : Math.min(method, top));
		return src.substring(at, end < 0 ? src.length() : end);
	}

	@Nested
	@DisplayName("1. The creator can walk away without ending the gather")
	class HostExit {
		@Test
		@DisplayName("Leaving and closing are separate outcomes")
		void threeWaysOut() throws Exception {
			String manager = read(MANAGER);
			assertTrue(manager.contains("private enum Exit {"), "the three outcomes are named");
			for (String v : new String[]{"LEAVE,", "STEP_AWAY,", "CLOSE"}) {
				assertTrue(manager.contains(v), "missing outcome: " + v);
			}
			assertTrue(
				manager.contains("public static void stepAwayAsync("),
				"the creator needs a way out that is not a close"
			);
			assertTrue(
				body(manager, "public static void leaveAsync(")
					.contains("isHost(mc) ? Exit.CLOSE : Exit.LEAVE"),
				"the old button keeps its meaning: a host closes, a member leaves"
			);
		}

		@Test
		@DisplayName("Stepping away keeps the row and the host secret, so returning works")
		void stepAwayKeepsTheWayBack() throws Exception {
			String exit = body(read(MANAGER), "private static void exitAsync(");
			assertTrue(
				exit.contains("if (how != Exit.STEP_AWAY) {"),
				"the gather is still running — dropping it from the list would hide it"
			);
			int guard = exit.indexOf("if (how != Exit.STEP_AWAY) {");
			String guarded = exit.substring(guard, Math.min(exit.length(), guard + 200));
			assertTrue(
				guarded.contains("ClanRoster.forget(code)")
					&& guarded.contains("ClanHostSecrets.forget(code)"),
				"both the row and the secret must survive stepping away, or the creator "
					+ "comes back to a gather they can no longer host"
			);
		}

		@Test
		@DisplayName("The host's row offers the exit; closing stays behind its confirmation")
		void buttonIsOnTheHostRow() throws Exception {
			String screen = read(SCREEN);
			assertTrue(
				screen.contains("ClanSessionManager.stepAwayAsync("),
				"the host row must offer an exit — it used to show only «Назад»"
			);
			assertTrue(
				screen.contains("screen.chestmemory.clan.step_away"),
				"and it needs its own caption, not the member's"
			);
			assertTrue(
				body(screen, "private void initHostSettings(")
					.contains("ClanSessionManager.leaveAsync("),
				"ending it for everyone stays in host settings, behind the arm"
			);
		}
	}

	@Nested
	@DisplayName("2. The feed belongs to one gather")
	class FeedIsolation {
		@Test
		@DisplayName("The feed is rendered from the hub's history, not from watched diffs")
		void feedComesFromTheHub() throws Exception {
			String feed = read(FEED);
			assertTrue(feed.contains("private static String sessionCode"), "the feed tracks its gather");
			String from = body(feed, "public static synchronized void fromSession(");
			assertTrue(from.contains("entries.clear()"), "the hub's list IS the history");
			assertTrue(
				from.contains("events.size() - 1; i >= 0"),
				"the hub appends oldest first and the feed reads newest first"
			);
			assertFalse(
				feed.contains("public static synchronized void add("),
				"a second way in would let the client double-log what the hub already sent"
			);
			assertTrue(
				body(feed, "public static synchronized void clear()").contains("sessionCode = \"\""),
				"clearing must release the code, or the next snapshot looks like more of this one"
			);
		}

		@Test
		@DisplayName("Adopting a snapshot is what fills the feed, so no caller can forget")
		void wiredAtTheChokePoint() throws Exception {
			assertTrue(
				body(read(MANAGER), "private static @Nullable ClanSession adoptSession(")
					.contains("ClanEventLog.fromSession(next.code, next.events)"),
				"switching goes through join, which cleared nothing — that is why the house "
					+ "build opened showing the farm's claims"
			);
			assertFalse(
				read(MANAGER).contains("ClanEventLog.add("),
				"the diff-derived feed is gone: it only knew what happened while watching"
			);
		}

		@Test
		@DisplayName("Every kind the hub can send has a line, and both hubs send the same set")
		void everyKindIsSpoken() throws Exception {
			String feed = read(FEED);
			String hub = read(HUB);
			String php = read(HUB_PHP);
			for (String kind : new String[]{
				"claim", "release", "deliver", "join", "leave", "kick",
				"exclude", "include", "release_all", "timeout", "create",
			}) {
				assertTrue(
					feed.contains("case \"" + kind + "\""),
					"the client cannot say '" + kind + "' — the feed would silently skip it"
				);
				assertTrue(hub.contains("\"" + kind + "\""), "python hub never sends " + kind);
				assertTrue(php.contains("'" + kind + "'"), "php hub never sends " + kind);
			}
		}
	}

	@Nested
	@DisplayName("4. The gather list holds still")
	class StableList {
		@Test
		@DisplayName("Refreshing an entry does not move it")
		void rememberKeepsPosition() throws Exception {
			String remember = body(read(ROSTER), "public static void remember(\n");
			assertTrue(
				remember.contains("Entry prev = known.get(key);"),
				"remove-then-put is what moved the active gather to the bottom"
			);
			assertFalse(
				remember.contains("known.remove(key)"),
				"a poll refreshes progress through this method several times a minute"
			);
		}

		@Test
		@DisplayName("The order is documented as first-met, not most-recent")
		void orderIsDocumented() throws Exception {
			assertTrue(
				body(read(ROSTER), "public static List<Entry> all()").contains("ensureLoaded()"),
				"all() still loads before reading"
			);
			assertFalse(
				read(ROSTER).contains("most recently touched last"),
				"the old contract described the behaviour that was the bug"
			);
		}
	}

	@Nested
	@DisplayName("3. A gather people are still playing does not expire")
	class Retention {
		@Test
		@DisplayName("Heartbeats count for every gather, not only solo ones")
		void heartbeatsKeepItAlive() throws Exception {
			String purge = pyBody(read(HUB), "def _purge_old(");
			int loop = purge.indexOf("for m in members:");
			int shortLease = purge.indexOf("UNSTARTED_SESSION_TTL_SEC");
			assertTrue(loop > 0 && shortLease > 0, "both the lastSeen scan and the short lease exist");
			assertTrue(
				loop < shortLease,
				"lastSeen must be counted for every session before the short lease narrows the "
					+ "TTL — inside a narrowing branch it only ever saved some gathers"
			);
			// The rule that replaced the roster count. Member count was wrong both ways: a host
			// who steps away leaves an empty roster on purpose, so a gather with real progress
			// sat on the short lease, while a nameless test with the creator still listed got
			// the long one. "Has anything been handed in" is the honest signal.
			assertTrue(
				purge.contains("if not _has_progress(s):"),
				"the short lease keys off deliveries now, not off how many people are listed"
			);
			assertFalse(
				purge.contains("len(members) <= 1"),
				"the roster count is exactly the rule that got this wrong"
			);
			assertTrue(
				read(HUB_PHP).contains("foreach ($members as $m) {"),
				"PHP has to age sessions by the same rule — it writes the same store"
			);
		}
	}

	@Nested
	@DisplayName("5. Gradient names keep their gradient")
	class GradientNames {
		@Test
		@DisplayName("One of the sixteen encodes as the old code, so existing keys are untouched")
		void exactColoursStayLegacy() {
			assertEquals("§6", LegacyColors.code(0xFFAA00), "gold is gold, not a marker");
			assertEquals("§f", LegacyColors.code(0xFFFFFF));
			assertEquals("§0", LegacyColors.code(0x000000));
		}

		@Test
		@DisplayName("Anything else encodes as a full-precision marker")
		void rgbBecomesAMarker() {
			assertEquals("§#1AFF3C", LegacyColors.code(0x1AFF3C));
			assertEquals("§#0A0B0C", LegacyColors.code(0x0A0B0C), "leading zeroes are kept");
			assertEquals("§#123456", LegacyColors.code(0xFF123456), "alpha is dropped, not printed");
		}

		@Test
		@DisplayName("A marker reads back byte-exact")
		void markerRoundTrips() {
			for (int rgb : new int[]{0x000001, 0x1AFF3C, 0xFEDCBA, 0xFFFFFE}) {
				String code = LegacyColors.code(rgb);
				assertEquals(rgb, LegacyColors.markerAt(code, 0), "round trip failed for " + code);
			}
			assertEquals(0x1AFF3C, LegacyColors.markerAt("abc§#1AFF3Cdef", 3), "offset parsing");
		}

		@Test
		@DisplayName("A half-written marker is text, not an exception")
		void malformedMarkerIsHarmless() {
			assertEquals(-1, LegacyColors.markerAt("§#12FF", 0), "truncated at the end");
			assertEquals(-1, LegacyColors.markerAt("§#12FF3", 0), "one hex digit short");
			assertEquals(-1, LegacyColors.markerAt("§#GGGGGG", 0), "not hex at all");
			assertEquals(-1, LegacyColors.markerAt("§6red", 0), "a plain code is not a marker");
			assertEquals(-1, LegacyColors.markerAt("", 0));
			assertEquals(-1, LegacyColors.markerAt("§#123456", 99), "past the end");
		}

		@Test
		@DisplayName("Downgrading keeps every character of the name")
		void downgradeKeepsText() {
			// A three-stop gradient over "Меч": each character its own colour.
			//
			// The codes are what plain RGB distance actually picks, which is not always what
			// the eye would guess: #FF0000 lands on dark red §4 rather than bright §c, because
			// §c is #FF5555 and differs in two channels. #FFFF00 ties between gold #FFAA00 and
			// yellow #FFFF55 at the same distance, and the first match wins. That is fine — the
			// approximation only feeds chat and CSV; tooltips now render the real colour.
			String gradient = "§#FF0000М§#FF7F00е§#FFFF00ч";
			String flat = LegacyColors.downgrade(gradient);
			assertEquals("§4М§6е§6ч", flat, "each marker becomes its nearest plain code");
			assertEquals("Меч", LegacyColors.strip(gradient), "and stripping leaves the text");
			assertEquals("Меч", LegacyColors.strip(flat));
		}

		@Test
		@DisplayName("A literal '§#' in an anvil name is not eaten")
		void literalMarkerPrefixSurvives() {
			assertEquals("§#tag", LegacyColors.downgrade("§#tag"), "not a marker: copy it through");
			assertEquals("§#tag", LegacyColors.strip("§#tag"));
			assertEquals("a§#b", LegacyColors.downgrade("a§#b"));
		}

		@Test
		@DisplayName("Names with no styling come back identical")
		void plainNamesUntouched() {
			assertEquals("Меч босса", LegacyColors.downgrade("Меч босса"));
			assertEquals("Меч босса", LegacyColors.strip("Меч босса"));
			assertEquals("", LegacyColors.downgrade(null));
			assertEquals("", LegacyColors.strip(null));
		}

		@Test
		@DisplayName("Stripping removes modifiers too, unlike vanilla's stripper on a marker")
		void stripHandlesBothForms() {
			assertEquals("bold red", LegacyColors.strip("§l§cbold §cred").replace("  ", " "));
			assertEquals("x", LegacyColors.strip("§k§l§m§n§o§rx"));
			assertFalse(
				LegacyColors.strip("§#1AFF3Cx").contains("#"),
				"vanilla's stripper would leave '#1AFF3C' behind in a searchable name"
			);
		}

		@Test
		@DisplayName("The two decoders are used where each belongs")
		void decodersWiredCorrectly() throws Exception {
			String keys = read(KEYS);
			assertTrue(
				keys.contains("LegacyText.toComponent(customName)"),
				"only a component can carry real RGB to the renderer"
			);
			assertFalse(
				keys.contains("Component.literal(customName)"),
				"a literal only carries §-codes, which the font renderer flattens"
			);
			assertTrue(
				keys.contains("LegacyText.downgrade(custom)"),
				"the string path feeds chat, CSV and search, none of which render a marker"
			);
			assertTrue(
				read(LEGACY).contains("LegacyColors.markerAt(legacy, i)"),
				"the parser must share the marker scanner that is under test here"
			);
		}
	}

	@Nested
	@DisplayName("New strings exist in both languages")
	class Strings {
		@Test
		@DisplayName("The exit caption and its chat line are translated")
		void bothLanguages() throws Exception {
			String ru = read(RU);
			String en = read(EN);
			for (String key : new String[]{
				"screen.chestmemory.clan.step_away",
				"screen.chestmemory.clan.step_away_hint",
				"message.chestmemory.clan_stepped_away",
			}) {
				assertTrue(ru.contains('"' + key + '"'), "ru_ru is missing " + key);
				assertTrue(en.contains('"' + key + '"'), "en_us is missing " + key);
			}
		}
	}
}
