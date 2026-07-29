package com.chestmemory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Jade overlay, after four complaints from play: the list stopped at eighteen icons with
 * no way to see the rest, an empty chest claimed it had never been scanned, the wording was
 * longer than the line it sat on, and tools were counted in stacks.
 */
class JadeOverlayTest {
	private static final String JADE =
		"src/client/java/com/chestmemory/client/jade/MemoryContainerComponentProvider.java";
	private static final String SCANNER =
		"src/client/java/com/chestmemory/client/scan/ContainerScanner.java";
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

	/** The value of a lang key, so length can be measured rather than eyeballed. */
	private static String lang(String file, String key) throws Exception {
		String src = read(file);
		int at = src.indexOf('"' + key + '"');
		assertTrue(at > 0, file + " is missing " + key);
		int colon = src.indexOf(':', at);
		int open = src.indexOf('"', colon);
		int close = src.indexOf('"', open + 1);
		return src.substring(open + 1, close);
	}

	@Nested
	@DisplayName("Shift opens the full list")
	class ShiftExpands {
		@Test
		@DisplayName("Two caps: a glance while walking past, and everything on demand")
		void twoCaps() throws Exception {
			String src = read(JADE);
			assertTrue(src.contains("MAX_ITEMS_SHOWN = 18"), "the short cap is the default");
			assertTrue(src.contains("MAX_ITEMS_SHIFT = 90"), "and Shift lifts it");
			assertTrue(
				src.contains("int limit = expanded ? MAX_ITEMS_SHIFT : MAX_ITEMS_SHOWN;")
					&& src.contains("if (shown >= limit)"),
				"the loop has to honour the chosen cap, not the constant it used to read"
			);
		}

		@Test
		@DisplayName("Shift is read from the window, because no screen is open")
		void shiftReadFromWindow() throws Exception {
			String shift = body(read(JADE), "private static boolean isShiftDown(");
			assertTrue(
				shift.contains("InputConstants.isKeyDown("),
				"a Jade overlay draws with no screen open, so there is no key event to consult"
			);
			assertTrue(
				shift.contains("KEY_LSHIFT") && shift.contains("KEY_RSHIFT"),
				"both keys — reaching for the right one must not look like a broken feature"
			);
			assertTrue(shift.contains("window == null"), "no window, no modifier");
		}

		@Test
		@DisplayName("The hint appears only while the list is actually cut short")
		void hintOnlyWhenTruncated() throws Exception {
			String src = read(JADE);
			assertTrue(
				src.contains("expanded ? \"jade.chestmemory.more\" : \"jade.chestmemory.more_shift\""),
				"telling a player to hold Shift while they are holding it is noise"
			);
			assertTrue(
				lang(RU, "jade.chestmemory.more_shift").contains("Shift"),
				"the hint has to name the key, or nobody discovers the feature"
			);
		}
	}

	@Nested
	@DisplayName("An empty chest says it is empty")
	class EmptyIsNotUnknown {
		@Test
		@DisplayName("Never scanned and scanned-but-empty are different answers")
		void emptyHasItsOwnLine() throws Exception {
			String src = read(JADE);
			int unknown = src.indexOf("jade.chestmemory.unknown");
			int empty = src.indexOf("jade.chestmemory.empty");
			assertTrue(unknown > 0 && empty > 0, "both cases need a line of their own");
			assertTrue(
				src.contains("if (record == null) {"),
				"absence of a record is the only thing that means 'not scanned'"
			);
			assertTrue(
				src.contains("if (record.items().isEmpty()) {"),
				"an emptied chest still has a record — reporting it as unscanned reads as data loss"
			);
			assertFalse(
				src.contains("record == null || record.items().isEmpty()"),
				"the two cases were collapsed into one message"
			);
		}

		@Test
		@DisplayName("The scanner really does record an empty container, so the split is honest")
		void scannerRecordsEmpty() throws Exception {
			assertTrue(
				read(SCANNER).contains("A genuinely empty chest still gets recorded"),
				"the empty line would be unreachable if nothing were stored for an empty chest"
			);
		}
	}

	@Nested
	@DisplayName("The wording fits the line it sits on")
	class Wording {
		@Test
		@DisplayName("Every Jade line is short enough to read at a glance")
		void linesAreShort() throws Exception {
			for (String file : new String[]{RU, EN}) {
				// A Jade overlay floats over the world at the top of the screen: these lines
				// compete with the game, not with a settings page. "Память: ещё не сканировали"
				// was 26 characters of which four carried meaning.
				for (String key : new String[]{
					"jade.chestmemory.unknown",
					"jade.chestmemory.empty",
					"jade.chestmemory.staging",
				}) {
					String value = lang(file, key).replace("§d", "").replace("§r", "");
					assertTrue(
						value.length() <= 16,
						key + " is too long for an overlay line: '" + value + "'"
					);
				}
			}
		}

		@Test
		@DisplayName("Dead strings are gone rather than left to rot")
		void noDeadKeys() throws Exception {
			String jade = read(JADE);
			for (String file : new String[]{RU, EN}) {
				String src = read(file);
				for (String key : new String[]{
					"jade.chestmemory.header", "jade.chestmemory.double_chest",
				}) {
					// Exact match: header_short is live and shares the prefix.
					assertFalse(
						src.contains('"' + key + '"'),
						key + " has no reader in the provider and must not linger in lang"
					);
				}
			}
			assertTrue(
				jade.contains("jade.chestmemory.header_short"),
				"the live header must still be the one being sent"
			);
		}
	}
}
