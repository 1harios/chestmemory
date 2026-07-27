package com.chestmemory;

import com.chestmemory.client.data.ContainerRecord;
import com.chestmemory.client.data.WorldBreakdown;
import com.chestmemory.client.data.WorldTags;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The "where does this item actually lie" grouping behind the panel tooltip and the
 * other-world chat notice. The case that matters: the player stands in the build world,
 * the diamonds are in the farm world at the same dimension id — the breakdown must say
 * "another world", not stay silent and not claim "here".
 */
class WorldBreakdownTest {

	private static ContainerRecord chest(String dim, String tag, int x, Map<String, Integer> items) {
		ContainerRecord r = new ContainerRecord("chest", dim, x, 64, 0);
		r.setWorldTag(tag);
		r.setItems(items);
		return r;
	}

	@Test
	@DisplayName("Same dimension id, different world tag → grouped as another world, not here")
	void farmVsBuild() {
		String farm = WorldTags.seedTag(1L);
		String build = WorldTags.seedTag(2L);
		List<ContainerRecord> records = List.of(
			chest("minecraft:overworld", farm, 0, Map.of("minecraft:diamond", 250)),
			chest("minecraft:overworld", farm, 5, Map.of("minecraft:diamond", 50)),
			chest("minecraft:overworld", build, 10, Map.of("minecraft:diamond", 7))
		);

		// Player stands in the build world
		List<WorldBreakdown.Entry> groups =
			WorldBreakdown.of(records, "minecraft:diamond", "minecraft:overworld", build);

		assertEquals(2, groups.size());
		WorldBreakdown.Entry here = groups.getFirst();
		assertTrue(here.here(), "the here-group sorts first");
		assertEquals(7, here.count());
		assertEquals(1, here.containers());

		WorldBreakdown.Entry other = groups.get(1);
		assertFalse(other.here());
		assertTrue(other.otherWorld(), "same dimension id but a provably different world");
		assertEquals(300, other.count());
		assertEquals(2, other.containers());

		assertEquals(7, WorldBreakdown.hereCount(groups));
		assertEquals(300, WorldBreakdown.elsewhereCount(groups));
	}

	@Test
	@DisplayName("Everything in another world → hereCount is zero (triggers the chat notice)")
	void onlyElsewhere() {
		String farm = WorldTags.seedTag(1L);
		String build = WorldTags.seedTag(2L);
		List<ContainerRecord> records = List.of(
			chest("minecraft:overworld", farm, 0, Map.of("minecraft:diamond", 100))
		);

		List<WorldBreakdown.Entry> groups =
			WorldBreakdown.of(records, "minecraft:diamond", "minecraft:overworld", build);

		assertEquals(1, groups.size());
		assertEquals(0, WorldBreakdown.hereCount(groups));
		assertEquals(100, WorldBreakdown.elsewhereCount(groups));
		assertTrue(groups.getFirst().otherWorld());
	}

	@Test
	@DisplayName("An untagged legacy record in the player's dimension counts as here")
	void legacyRecordIsHere() {
		List<ContainerRecord> records = List.of(
			chest("minecraft:overworld", null, 0, Map.of("minecraft:iron_ingot", 64))
		);

		List<WorldBreakdown.Entry> groups =
			WorldBreakdown.of(records, "minecraft:iron_ingot", "minecraft:overworld", WorldTags.seedTag(2L));

		assertEquals(1, groups.size());
		assertTrue(groups.getFirst().here(), "unknown must fail open — never hide a chest");
		assertEquals(64, WorldBreakdown.hereCount(groups));
	}

	@Test
	@DisplayName("Other dimensions group by dimension id")
	void otherDimension() {
		List<ContainerRecord> records = List.of(
			chest("minecraft:the_nether", null, 0, Map.of("minecraft:diamond", 12)),
			chest("minecraft:overworld", null, 0, Map.of("minecraft:diamond", 3))
		);

		List<WorldBreakdown.Entry> groups =
			WorldBreakdown.of(records, "minecraft:diamond", "minecraft:overworld", null);

		assertEquals(2, groups.size());
		assertTrue(groups.getFirst().here());
		assertEquals("minecraft:the_nether", groups.get(1).dimensionId());
		assertFalse(groups.get(1).otherWorld(), "a different dimension is not the same-id-collision case");
	}

	@Test
	@DisplayName("Ender chest and inventory shulkers are personal, not world groups")
	void personalStorage() {
		ContainerRecord ender = ContainerRecord.virtual("ender_chest", "ender_chest", "minecraft:overworld");
		ender.setItems(Map.of("minecraft:diamond", 9));
		List<ContainerRecord> records = List.of(ender);

		List<WorldBreakdown.Entry> groups =
			WorldBreakdown.of(records, "minecraft:diamond", "minecraft:overworld", null);

		assertTrue(groups.isEmpty(), "virtual records never form world groups");
		assertEquals(9, WorldBreakdown.personalCount(records, "minecraft:diamond"));
	}
}
