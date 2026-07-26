package com.chestmemory;

import com.chestmemory.client.data.ContainerRecord;
import com.chestmemory.client.data.WorldFingerprint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A multiworld server gives every world the same vanilla dimension keys, so the farm world's
 * Nether and the build world's Nether both arrive as {@code minecraft:the_nether}. These
 * tests pin the comparison that keeps them apart — and, just as important, pin its
 * deliberate one-sidedness, because the destructive failure mode is treating "unknown" as
 * "different" and hiding or deleting chests that are perfectly fine.
 */
class WorldIdentityTest {

	@Nested
	@DisplayName("provablyDifferent")
	class ProvablyDifferent {

		@Test
		@DisplayName("Two known, unequal worlds are different")
		void differentWorlds() {
			assertTrue(WorldFingerprint.provablyDifferent("s0_64_0", "s100_70_-200"));
		}

		@Test
		@DisplayName("The same world is not different")
		void sameWorld() {
			assertFalse(WorldFingerprint.provablyDifferent("s0_64_0", "s0_64_0"));
		}

		@Test
		@DisplayName("A record written before tags existed must not be treated as foreign")
		void legacyRecordUnknown() {
			// The whole point: without this, updating the mod would hide every chest already
			// remembered, and the verifier would start deleting them.
			assertFalse(WorldFingerprint.provablyDifferent("s0_64_0", null));
		}

		@Test
		@DisplayName("A server that offers no fingerprint is never judged")
		void unknownCurrentWorld() {
			assertFalse(WorldFingerprint.provablyDifferent(null, "s0_64_0"));
		}

		@Test
		@DisplayName("Blank counts as unknown, not as a distinct world")
		void blankIsUnknown() {
			assertFalse(WorldFingerprint.provablyDifferent("", "s0_64_0"));
			assertFalse(WorldFingerprint.provablyDifferent("s0_64_0", "   "));
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
			r.setWorldTag("s0_64_0");
			assertEquals("s0_64_0", r.worldTag());
		}
	}

	@Nested
	@DisplayName("Known limitation: the container key still ignores the world")
	class KeyCollision {

		@Test
		@DisplayName("Two worlds at identical coordinates share one key")
		void identicalCoordinatesCollide() {
			// Documented, not desired. The user chose to defer this: fixing it means changing
			// the key format across every call site plus a migration of existing profiles.
			// The test exists so the day someone changes the format, the intent is on record.
			String farm = ContainerRecord.makeKey("minecraft:the_nether", 100, 64, 200);
			String build = ContainerRecord.makeKey("minecraft:the_nether", 100, 64, 200);
			assertEquals(farm, build, "if this ever differs, the collision has been fixed");
		}

		@Test
		@DisplayName("Different coordinates never collide")
		void differentCoordinates() {
			assertFalse(ContainerRecord.makeKey("minecraft:the_nether", 1, 50, 4)
				.equals(ContainerRecord.makeKey("minecraft:the_nether", -15, 49, -19)));
		}
	}
}
