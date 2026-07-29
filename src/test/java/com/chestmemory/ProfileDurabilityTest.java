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
 * The profile file is the mod. Everything else can be rescanned; months of remembered
 * chests cannot.
 * <p>
 * The write path had two ways to lose all of it. The backup was made by MOVING the live
 * file aside, so between the two renames no profile existed at all — and a missing file
 * read as "fresh", which left loadFailed false and disarmed the guard whose whole job is
 * to protect a damaged profile. The next save then overwrote the intact .tmp, and the one
 * after rotated the near-empty new file on top of the last good .bak. Separately, the
 * dirty flag was cleared when a save was queued rather than when it landed, so a full
 * disk ended the session in silence: saveNow() on quit saw a clean profile and did
 * nothing at all.
 * <p>
 * Minecraft is deliberately absent from the test classpath, so these assertions read the
 * source the way the clan tests do. Structure is what is being pinned here: the ordering
 * of two renames, and which side of a write clears a flag, are exactly the kind of detail
 * a later refactor reverts without noticing.
 */
class ProfileDurabilityTest {
	private static String read(String path) throws Exception {
		return Files.readString(Path.of(path));
	}

	private static final String STORAGE =
		"src/client/java/com/chestmemory/client/data/ChestMemoryStorage.java";
	private static final String VERIFIER =
		"src/client/java/com/chestmemory/client/scan/ContainerVerifier.java";
	private static final String MIGRATION =
		"src/client/java/com/chestmemory/client/data/ProfileMigration.java";
	private static final String RECORD =
		"src/client/java/com/chestmemory/client/data/ContainerRecord.java";
	private static final String KEYS =
		"src/client/java/com/chestmemory/client/data/ItemStackKeys.java";

	private static String body(String src, String signature) {
		int at = src.indexOf(signature);
		assertTrue(at > 0, "not found: " + signature);
		return src.substring(at, src.indexOf("\n\t}", at));
	}

	@Nested
	@DisplayName("A crash must never be able to leave zero copies of a profile")
	class NeverZeroCopies {
		@Test
		@DisplayName("The backup is copied, not moved out of the way")
		void backupIsACopy() throws Exception {
			String src = body(read(STORAGE), "private void writeProfileFile(String worldId, JsonObject root)");
			assertTrue(
				src.contains("Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING)"),
				"moving the live file aside leaves a window with no profile on disk at all"
			);
			assertFalse(
				src.contains("Files.move(file, backup"),
				"the old rotation must be gone, not merely joined by a copy"
			);
			assertTrue(
				src.contains("ATOMIC_MOVE"),
				"the new file still has to arrive by atomic rename — that is what makes the swap safe"
			);
		}

		@Test
		@DisplayName("The payload is flushed before anything is renamed")
		void payloadIsFlushedFirst() throws Exception {
			String src = body(read(STORAGE), "private void writeProfileFile(String worldId, JsonObject root)");
			int force = src.indexOf("channel.force(true)");
			int swap = src.indexOf("Files.move(tmp, file");
			assertTrue(force > 0, "the temp file must be forced to disk");
			assertTrue(
				force < swap,
				"a rename that reaches disk before its payload leaves a file that exists and parses as nothing"
			);
		}

		@Test
		@DisplayName("A missing profile looks for the sidecars a broken write leaves behind")
		void missingProfileTriesRecovery() throws Exception {
			String src = body(read(STORAGE), "private WorldFile loadFromDisk(String worldId)");
			assertTrue(src.contains("recoverProfile(worldId, file)"), "recovery must be attempted");
			String recover = body(read(STORAGE), "private @Nullable WorldFile recoverProfile(String worldId, Path file)");
			assertTrue(
				recover.contains("\".tmp\"") && recover.contains("\".bak\""),
				"both sidecars are candidates: .tmp holds the newest state, .bak the last settled one"
			);
			assertTrue(
				recover.contains("modifiedAt(b), modifiedAt(a)"),
				"try them newest-first rather than trusting an assumed order"
			);
			assertTrue(
				recover.contains("Files.copy(candidate, file, StandardCopyOption.REPLACE_EXISTING)"),
				"a recovered profile must be restored in place, or the next rotation copies the "
					+ "damaged original on top of the good backup"
			);
		}

		@Test
		@DisplayName("Unreadable with nothing to fall back on refuses to write")
		void unreadableRefusesToWrite() throws Exception {
			String src = body(read(STORAGE), "private WorldFile loadFromDisk(String worldId)");
			int rescue = src.indexOf("recoverProfile(worldId, file)");
			int refuse = src.indexOf("failed.loadFailed = true");
			assertTrue(refuse > rescue, "only refuse once recovery has been tried");
			assertTrue(
				body(read(STORAGE), "private synchronized void scheduleSave(boolean blocking)")
					.contains("if (liveLoadFailed)"),
				"the refusal has to actually suppress saving — that is the guard being protected"
			);
		}
	}

	@Nested
	@DisplayName("A failed write must not be mistaken for a saved one")
	class FailedWriteKeepsData {
		@Test
		@DisplayName("The dirty flag comes back when the write throws")
		void dirtyRestoredOnFailure() throws Exception {
			String write = body(read(STORAGE), "private void writeProfileFile(String worldId, JsonObject root)");
			assertTrue(
				write.contains("onWriteFailed(worldId)"),
				"a full disk left the flag clear, so saveNow() on quit did nothing and the "
					+ "session's scans were dropped despite a clean exit"
			);
			String cb = body(read(STORAGE), "private synchronized void onWriteFailed(String worldId)");
			assertTrue(cb.contains("liveDirty = true"), "the profile must go back to unsaved");
			assertTrue(
				cb.contains("worldId.equals(liveWorldId)"),
				"but only while we still hold that world — re-dirtying a profile we no longer "
					+ "have loaded would write one world's records into another's file"
			);
		}
	}

	@Nested
	@DisplayName("One file per world, and never a shared bucket")
	class ProfileIdentity {
		@Test
		@DisplayName("A server address carries a hash and a length cap")
		void addressIsHashedAndCapped() throws Exception {
			String src = body(read(STORAGE), "private static String sanitizeAddress(String address)");
			assertTrue(
				src.contains("shortHash(a)"),
				"an IDN hostname sanitizes to the empty string, so every such server shared one "
					+ "mp_.json — and on a seed-hiding server the verifier then deleted one "
					+ "server's chests while the player walked around another"
			);
			assertTrue(
				src.contains("MAX_SLUG_CHARS"),
				"a 250-character hostname produced a file name past the filesystem limit, and "
					+ "then every save threw and nothing persisted at all"
			);
		}

		@Test
		@DisplayName("Existing multiplayer profiles are migrated, not orphaned")
		void multiplayerProfilesMigrate() throws Exception {
			String src = body(read(STORAGE), "private static @Nullable String legacyProfileId(Minecraft client, String worldId)");
			assertTrue(src.contains("\"mp_\" + slug"), "the pre-hash multiplayer id must be derivable");
			assertTrue(
				src.contains("slug.isEmpty() ? null : \"mp_\" + slug"),
				"an empty legacy slug is the shared bucket several servers wrote to; handing it "
					+ "to whichever one connects first would be arbitrary"
			);
		}

		@Test
		@DisplayName("A profile from a newer format is left alone")
		void newerFormatIsRefused() throws Exception {
			String src = body(read(STORAGE), "private WorldFile readProfile(Path file)");
			assertTrue(
				src.contains("onDisk > FORMAT_VERSION"),
				"formatVersion was written since v3 and never once read, so a future shape change "
					+ "had no way to announce itself"
			);
			assertTrue(
				read(STORAGE).contains("refused.loadFailed = true"),
				"refusing to parse it must also refuse to overwrite it"
			);
		}
	}

	@Nested
	@DisplayName("Records describe things that exist")
	class RecordsStayHonest {
		@Test
		@DisplayName("A broken chest half is only spared while its partner still points back")
		void brokenHalfIsNotImmortal() throws Exception {
			String sweep = read(VERIFIER);
			assertTrue(
				sweep.contains("stillPairedWith(level, other, pos)"),
				"sparing the record whenever the neighbour was any container at all had no exit: "
					+ "opening the survivor runs as a SINGLE chest and only clears keys at the "
					+ "position it scanned, so the stale double record was re-blessed every sweep"
			);
			String paired = body(sweep, "private static boolean stillPairedWith(Level level, BlockPos other, BlockPos expected)");
			assertTrue(paired.contains("ChestType.SINGLE"), "a lone chest next door is a different container");
			assertTrue(
				paired.contains("getConnectedBlockPos(other, state).equals(expected)"),
				"the partner has to be paired with THIS position, not merely paired"
			);
		}

		@Test
		@DisplayName("Counts that cannot be added up are dropped on load")
		void unusableCountsAreScrubbed() throws Exception {
			assertTrue(
				body(read(MIGRATION), "public static Map<String, ContainerRecord> normalize")
					.contains("record.dropUnusableCounts()"),
				"normalize already walks every record, so it is where a null count should die"
			);
			String drop = body(read(RECORD), "void dropUnusableCounts()");
			assertTrue(
				drop.contains("count == null"),
				"Gson accepts a null count and the parse succeeds, so the guard never fires — it "
					+ "surfaces later as an unboxing NPE on the render thread, every tick"
			);
			assertTrue(drop.contains("count <= 0"), "no scan produces a non-positive count");
		}
	}

	@Nested
	@DisplayName("Reading a profile costs what it should")
	class CheapReads {
		@Test
		@DisplayName("Labelling a tab does not build a single record")
		void tabsReadOnlyTheHeader() throws Exception {
			String src = body(read(STORAGE), "public synchronized List<WorldTab> listWorldTabs()");
			assertTrue(src.contains("readProfileHeader(path)"), "the tab list must read headers");
			assertFalse(
				src.contains("loadFromDisk(id)"),
				"a full parse of every profile ever written, on the render thread, holding the "
					+ "storage lock, to obtain a name and a size"
			);
			String header = body(read(STORAGE), "private static @Nullable ProfileHeader readProfileHeader(Path file)");
			assertTrue(header.contains("reader.skipValue()"), "stream past what is not needed");
			assertTrue(
				header.contains("topLevelKeys"),
				"a legacy file is a bare map of containers, so its top-level keys are the count"
			);
		}

		@Test
		@DisplayName("The memo maps have a ceiling")
		void nameCachesAreBounded() throws Exception {
			String src = read(KEYS);
			assertTrue(
				src.contains("NAME_CACHE_MAX"),
				"the cache key embeds anvil names, which a shop server makes unbounded — the only "
					+ "eviction was the player changing language"
			);
			assertTrue(
				src.contains("removeEldestEntry"),
				"bounded means evicting, not merely having a number"
			);
		}

		@Test
		@DisplayName("Matching a slot settles on the registry id first")
		void matchesShortCircuits() throws Exception {
			String src = body(read(KEYS), "public static boolean matches(ItemStack stack, String key)");
			int idCheck = src.indexOf("id.toString().equals(baseId(key))");
			int fullKey = src.indexOf("keyOf(stack).equals(key)");
			assertTrue(idCheck > 0, "compare the cheap thing first");
			assertTrue(
				idCheck < fullKey,
				"keyOf allocates a list, walks both enchantment components and escapes the anvil "
					+ "name — and this runs per non-empty slot per frame during a gather"
			);
		}
	}

	@Nested
	@DisplayName("Text the mod did not write")
	class UntrustedText {
		@Test
		@DisplayName("A CSV cell cannot start a formula")
		void csvNeutralisesFormulas() throws Exception {
			String src = body(read(STORAGE), "private static String csvField(@Nullable Object value)");
			assertTrue(
				src.contains("\"=+-@\".indexOf(s.charAt(0))"),
				"item names come from anvils, including other players', and the mod hands the "
					+ "file to the player to open in a spreadsheet"
			);
			assertTrue(src.contains("\"'\" + s"), "the neutralising prefix has to actually be added");
		}

		@Test
		@DisplayName("An escaped name survives the round trip")
		void escapingRoundTrips() throws Exception {
			String src = body(read(KEYS), "private static String unescapeNamePart(String raw)");
			assertTrue(
				src.contains("StringBuilder"),
				"sequential replaces cannot round-trip: a literal backslash-p escapes to two "
					+ "backslashes then p, and the backslash-p rule fired on the second backslash "
					+ "before the doubled backslash collapsed"
			);
			assertFalse(src.contains(".replace("), "no replace chain may remain in the unescape path");

			// The arithmetic the fix restores, spelled out on the escape rules themselves:
			// escape doubles a backslash, so unescape must consume it before reading the letter.
			String escaped = "a\\\\p";
			assertEquals("a\\p", singlePassUnescape(escaped), "backslash then p must stay backslash then p");
			assertEquals("=", singlePassUnescape("\\e"), "an escaped equals must come back");
			assertEquals("+", singlePassUnescape("\\p"), "an escaped plus must come back");
			assertEquals("#", singlePassUnescape("\\h"), "an escaped hash must come back");
		}

		/** Mirror of the production rules, so the expectation above is stated independently. */
		private static String singlePassUnescape(String raw) {
			StringBuilder out = new StringBuilder(raw.length());
			for (int i = 0; i < raw.length(); i++) {
				char c = raw.charAt(i);
				if (c != '\\' || i + 1 >= raw.length()) {
					out.append(c);
					continue;
				}
				char next = raw.charAt(++i);
				switch (next) {
					case 'h' -> out.append('#');
					case 'e' -> out.append('=');
					case 'p' -> out.append('+');
					case '\\' -> out.append('\\');
					default -> out.append(c).append(next);
				}
			}
			return out.toString();
		}
	}
}
