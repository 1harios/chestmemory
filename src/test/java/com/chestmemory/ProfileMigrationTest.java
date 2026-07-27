package com.chestmemory;

import com.chestmemory.client.data.ContainerRecord;
import com.chestmemory.client.data.ProfileMigration;
import com.chestmemory.client.data.WorldTags;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Profiles written by any older version must load into the new per-world key scheme without
 * losing a single record. The dangerous direction is a legacy spawn tag surviving migration:
 * compared against the new seed tags it would read as "different world" and hide the chest.
 */
class ProfileMigrationTest {

	private static ContainerRecord record(String dim, int x, int y, int z, String tag, long seen) {
		ContainerRecord r = new ContainerRecord("chest", dim, x, y, z);
		r.setWorldTag(tag);
		r.setLastSeenMillis(seen);
		return r;
	}

	@Test
	@DisplayName("Legacy spawn tags are cleared to unknown, record kept under its legacy key")
	void legacyTagCleared() {
		Map<String, ContainerRecord> in = new LinkedHashMap<>();
		ContainerRecord r = record("minecraft:overworld", 10, 64, -3, "s0_64_0", 1000L);
		in.put("minecraft:overworld|10,64,-3", r);

		Map<String, ContainerRecord> out = ProfileMigration.normalize(in);

		assertEquals(1, out.size());
		ContainerRecord migrated = out.get("minecraft:overworld|10,64,-3");
		assertEquals(r, migrated, "record must survive under the legacy key");
		assertNull(migrated.worldTag(), "legacy tag must become unknown, never 'different'");
	}

	@Test
	@DisplayName("Current-format tags survive and the map key is rebuilt to match them")
	void currentTagKeyRebuilt() {
		String tag = WorldTags.seedTag(99L);
		Map<String, ContainerRecord> in = new LinkedHashMap<>();
		ContainerRecord r = record("minecraft:overworld", 1, 2, 3, tag, 1000L);
		// Simulate a file whose key predates the record's tag (or was edited by hand).
		in.put("minecraft:overworld|1,2,3", r);

		Map<String, ContainerRecord> out = ProfileMigration.normalize(in);

		assertEquals(1, out.size());
		assertEquals(r, out.get(ContainerRecord.makeKey("minecraft:overworld", 1, 2, 3, tag)));
		assertEquals(tag, r.worldTag());
	}

	@Test
	@DisplayName("Same-coordinate records from two worlds coexist after migration")
	void twoWorldsCoexist() {
		String farm = WorldTags.seedTag(1L);
		String build = WorldTags.seedTag(2L);
		Map<String, ContainerRecord> in = new LinkedHashMap<>();
		ContainerRecord farmChest = record("minecraft:overworld", 100, 64, 200, farm, 1000L);
		ContainerRecord buildChest = record("minecraft:overworld", 100, 64, 200, build, 2000L);
		in.put(farmChest.positionKey(), farmChest);
		in.put(buildChest.positionKey(), buildChest);

		Map<String, ContainerRecord> out = ProfileMigration.normalize(in);

		assertEquals(2, out.size(), "one chest per world, no collision");
		assertEquals(farmChest, out.get(ContainerRecord.makeKey("minecraft:overworld", 100, 64, 200, farm)));
		assertEquals(buildChest, out.get(ContainerRecord.makeKey("minecraft:overworld", 100, 64, 200, build)));
	}

	@Test
	@DisplayName("On a genuine key collision the most recently seen record wins")
	void newestWinsOnCollision() {
		Map<String, ContainerRecord> in = new LinkedHashMap<>();
		ContainerRecord older = record("minecraft:overworld", 5, 5, 5, "s0_64_0", 1000L);
		ContainerRecord newer = record("minecraft:overworld", 5, 5, 5, "s9_9_9", 2000L);
		// Distinct legacy tags used to make distinct... nothing — keys ignored tags. After
		// both tags are cleared, the two entries normalize to one legacy key.
		in.put("a", older);
		in.put("b", newer);

		Map<String, ContainerRecord> out = ProfileMigration.normalize(in);

		assertEquals(1, out.size());
		assertEquals(newer, out.get("minecraft:overworld|5,5,5"));
	}

	@Test
	@DisplayName("Virtual records (ender chest, inventory shulkers) pass through untouched")
	void virtualRecordsPassThrough() {
		Map<String, ContainerRecord> in = new LinkedHashMap<>();
		ContainerRecord ender = ContainerRecord.virtual("ender_chest", "ender_chest", "minecraft:overworld");
		in.put(ender.positionKey(), ender);

		Map<String, ContainerRecord> out = ProfileMigration.normalize(in);

		assertEquals(1, out.size());
		assertEquals(ender, out.get("virtual|ender_chest"));
	}

	@Test
	@DisplayName("Null input and null records are tolerated")
	void nullSafety() {
		assertTrue(ProfileMigration.normalize(null).isEmpty());
		Map<String, ContainerRecord> in = new LinkedHashMap<>();
		in.put("broken", null);
		assertTrue(ProfileMigration.normalize(in).isEmpty());
	}
}
