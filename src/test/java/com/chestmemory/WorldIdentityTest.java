package com.chestmemory;

import com.chestmemory.client.data.ContainerRecord;
import com.chestmemory.client.data.WorldFingerprint;
import com.chestmemory.client.data.WorldTags;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A multiworld server gives every world the same vanilla dimension keys, so the farm world's
 * chests and the build world's chests used to collide at identical coordinates. These tests
 * pin the identity rules that keep them apart — and, just as important, pin their deliberate
 * one-sidedness, because the destructive failure mode is treating "unknown" as "different"
 * and hiding or deleting chests that are perfectly fine.
 */
class WorldIdentityTest {

	@Nested
	@DisplayName("Tag composition")
	class TagComposition {

		@Test
		@DisplayName("A hashed seed yields a stable seed tag")
		void seedTagStable() {
			String a = WorldTags.seedTag(123456789L);
			String b = WorldTags.seedTag(123456789L);
			assertNotNull(a);
			assertEquals(a, b);
			assertTrue(WorldTags.isCurrentFormat(a));
			assertEquals('w', a.charAt(0));
		}

		@Test
		@DisplayName("Different seeds yield different tags")
		void seedTagsDiffer() {
			assertNotEquals(WorldTags.seedTag(1L), WorldTags.seedTag(2L));
		}

		@Test
		@DisplayName("A zeroed seed (seed-hiding server) is unknown, not world zero")
		void zeroSeedIsUnknown() {
			assertNull(WorldTags.seedTag(0L));
		}

		@Test
		@DisplayName("Spawn tags are stable, distinct per position, and current-format")
		void spawnTags() {
			String a = WorldTags.spawnTag(0, 64, 0);
			assertEquals(a, WorldTags.spawnTag(0, 64, 0));
			assertNotEquals(a, WorldTags.spawnTag(100, 70, -200));
			assertTrue(WorldTags.isCurrentFormat(a));
			assertEquals('p', a.charAt(0));
		}

		@Test
		@DisplayName("Legacy spawn-position tags are not current format")
		void legacyFormatRejected() {
			assertFalse(WorldTags.isCurrentFormat("s0_64_0"));
			assertFalse(WorldTags.isCurrentFormat(""));
			assertFalse(WorldTags.isCurrentFormat(null));
			assertNull(WorldTags.sanitize("s0_64_0"));
			assertEquals("w1a2b3c4", WorldTags.sanitize("w1a2b3c4"));
		}
	}

	@Nested
	@DisplayName("provablyDifferent")
	class ProvablyDifferent {

		@Test
		@DisplayName("Two known, unequal worlds are different")
		void differentWorlds() {
			assertTrue(WorldFingerprint.provablyDifferent(
				WorldTags.seedTag(1L), WorldTags.seedTag(2L)));
			assertTrue(WorldFingerprint.provablyDifferent(
				WorldTags.spawnTag(0, 64, 0), WorldTags.spawnTag(100, 70, -200)));
		}

		@Test
		@DisplayName("The same world is not different")
		void sameWorld() {
			assertFalse(WorldFingerprint.provablyDifferent(
				WorldTags.seedTag(42L), WorldTags.seedTag(42L)));
		}

		@Test
		@DisplayName("A record written before tags existed must not be treated as foreign")
		void legacyRecordUnknown() {
			// The whole point: without this, updating the mod would hide every chest already
			// remembered, and the verifier would start deleting them.
			assertFalse(WorldFingerprint.provablyDifferent(WorldTags.seedTag(1L), null));
		}

		@Test
		@DisplayName("A server that offers no fingerprint is never judged")
		void unknownCurrentWorld() {
			assertFalse(WorldFingerprint.provablyDifferent(null, WorldTags.seedTag(1L)));
		}

		@Test
		@DisplayName("Blank counts as unknown, not as a distinct world")
		void blankIsUnknown() {
			assertFalse(WorldFingerprint.provablyDifferent("", WorldTags.seedTag(1L)));
			assertFalse(WorldFingerprint.provablyDifferent(WorldTags.seedTag(1L), "   "));
		}

		@Test
		@DisplayName("Legacy-format tags from old profiles are never judged")
		void legacyFormatNeverJudged() {
			// An old "s…" tag against a new seed tag is a format change, not a world change.
			assertFalse(WorldFingerprint.provablyDifferent("s0_64_0", WorldTags.seedTag(1L)));
			assertFalse(WorldFingerprint.provablyDifferent("s0_64_0", "s100_70_-200"));
		}

		@Test
		@DisplayName("A seed tag and a spawn tag are different signals, not different worlds")
		void mixedSignalsNeverJudged() {
			assertFalse(WorldFingerprint.provablyDifferent(
				WorldTags.seedTag(1L), WorldTags.spawnTag(0, 64, 0)));
		}
	}

	@Nested
	@DisplayName("Records carry the world tag")
	class RecordTag {

		@Test
		@DisplayName("A fresh record has no tag until one is stamped")
		void defaultsToNull() {
			ContainerRecord r = new ContainerRecord("chest", "minecraft:the_nether", 1, 2, 3);
			assertEquals(null, r.worldTag());
		}

		@Test
		@DisplayName("The tag round-trips")
		void roundTrip() {
			ContainerRecord r = new ContainerRecord("chest", "minecraft:the_nether", 1, 2, 3);
			r.setWorldTag("w1a2b3c4");
			assertEquals("w1a2b3c4", r.worldTag());
		}
	}

	@Nested
	@DisplayName("The container key carries the world")
	class KeyIdentity {

		@Test
		@DisplayName("Two worlds at identical coordinates no longer share one key")
		void identicalCoordinatesNoLongerCollide() {
			// The collision this fixes: a farm-world chest and a build-world chest at the
			// same coordinates read and overwrote one record.
			ContainerRecord farm = new ContainerRecord("chest", "minecraft:the_nether", 100, 64, 200);
			farm.setWorldTag(WorldTags.seedTag(1L));
			ContainerRecord build = new ContainerRecord("chest", "minecraft:the_nether", 100, 64, 200);
			build.setWorldTag(WorldTags.seedTag(2L));
			assertNotEquals(farm.positionKey(), build.positionKey());
		}

		@Test
		@DisplayName("An untagged record keeps the legacy key, so old profiles stay readable")
		void untaggedKeepsLegacyKey() {
			ContainerRecord legacy = new ContainerRecord("chest", "minecraft:the_nether", 100, 64, 200);
			assertEquals(
				ContainerRecord.makeKey("minecraft:the_nether", 100, 64, 200),
				legacy.positionKey()
			);
		}

		@Test
		@DisplayName("Tagged and untagged key forms are related but distinct")
		void taggedKeyExtendsLegacy() {
			String tag = WorldTags.seedTag(7L);
			String legacy = ContainerRecord.makeKey("minecraft:overworld", 1, 2, 3);
			String tagged = ContainerRecord.makeKey("minecraft:overworld", 1, 2, 3, tag);
			assertTrue(tagged.startsWith(legacy));
			assertNotEquals(legacy, tagged);
			// A null tag degrades to the legacy form.
			assertEquals(legacy, ContainerRecord.makeKey("minecraft:overworld", 1, 2, 3, null));
		}

		@Test
		@DisplayName("Different coordinates never collide")
		void differentCoordinates() {
			assertFalse(ContainerRecord.makeKey("minecraft:the_nether", 1, 50, 4)
				.equals(ContainerRecord.makeKey("minecraft:the_nether", -15, 49, -19)));
		}
	}
}
