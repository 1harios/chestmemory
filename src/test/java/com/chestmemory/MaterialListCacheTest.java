package com.chestmemory;

import com.chestmemory.client.litematica.LitematicaCompat.MaterialNeed;
import com.chestmemory.client.litematica.MaterialListCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The material list belongs to Litematica, which clears it on every world load — so walking
 * through a Nether portal emptied a running gather. These tests pin the cache that keeps the
 * list alive, including the mistake that made the first attempt at this fix dead code.
 */
class MaterialListCacheTest {
	private static final List<MaterialNeed> DIRT_AND_PEONY = List.of(
		new MaterialNeed("minecraft:dirt", 4, 4, 0),
		new MaterialNeed("minecraft:peony", 1, 1, 0)
	);
	private static final List<MaterialNeed> STONE = List.of(
		new MaterialNeed("minecraft:stone", 64, 64, 0)
	);

	@BeforeEach
	void reset() {
		MaterialListCache.setArmed(false);
	}

	@Test
	@DisplayName("Not armed: an empty list stays empty (user closed their list on purpose)")
	void notArmedPassesThrough() {
		MaterialListCache.resolve(DIRT_AND_PEONY, "Build");
		assertEquals(List.of(), MaterialListCache.resolve(List.of(), null));
	}

	@Test
	@DisplayName("Armed: the list survives Litematica dropping it during a world load")
	void servesCacheWhileArmed() {
		MaterialListCache.setArmed(true);
		MaterialListCache.resolve(DIRT_AND_PEONY, "Build");

		// The portal: Litematica reports nothing and has no list name.
		List<MaterialNeed> served = MaterialListCache.resolve(List.of(), null);

		assertEquals(2, served.size(), "materials must survive the portal");
		assertEquals("minecraft:dirt", served.getFirst().itemId());
		assertTrue(MaterialListCache.isServingCache(List.of()));
	}

	@Test
	@DisplayName("The schematic name survives too — losing it would wipe the gather queue")
	void keepsListNameThroughPortal() {
		MaterialListCache.setArmed(true);
		MaterialListCache.resolve(DIRT_AND_PEONY, "Build");
		MaterialListCache.resolve(List.of(), null);

		// snapshotTotals() clears the queue whenever the active list name changes. If the
		// name went null mid-portal it would wipe the very queue the cache is protecting.
		assertEquals("Build", MaterialListCache.cachedListName());
	}

	@Test
	@DisplayName("Litematica takes over again once it has a list")
	void livePreferredOverCache() {
		MaterialListCache.setArmed(true);
		MaterialListCache.resolve(DIRT_AND_PEONY, "Build");
		MaterialListCache.resolve(List.of(), null);

		List<MaterialNeed> back = MaterialListCache.resolve(DIRT_AND_PEONY, "Build");
		assertEquals(DIRT_AND_PEONY, back);
		assertFalse(MaterialListCache.isServingCache(DIRT_AND_PEONY));
	}

	@Test
	@DisplayName("Switching schematic replaces the cache instead of mixing two builds")
	void switchingSchematicDropsOldCache() {
		MaterialListCache.setArmed(true);
		MaterialListCache.resolve(DIRT_AND_PEONY, "Build");

		MaterialListCache.resolve(STONE, "Other");
		assertEquals("Other", MaterialListCache.cachedListName());

		List<MaterialNeed> served = MaterialListCache.resolve(List.of(), null);
		assertEquals(1, served.size(), "must serve the new schematic only");
		assertEquals("minecraft:stone", served.getFirst().itemId());
	}

	@Test
	@DisplayName("Stopping the gather clears the cache, so an empty list reads as empty")
	void disarmClears() {
		MaterialListCache.setArmed(true);
		MaterialListCache.resolve(DIRT_AND_PEONY, "Build");

		MaterialListCache.setArmed(false);

		assertEquals(List.of(), MaterialListCache.resolve(List.of(), null));
		assertFalse(MaterialListCache.isServingCache(List.of()));
	}

	@Test
	@DisplayName("Regression: the real gather entry point must arm the cache")
	void startQueuePathArmsCache() throws Exception {
		// The first version of this fix armed the cache in setActive(), but a gather actually
		// starts through startQueue(), which sets `active = true` directly. armed stayed
		// false, so the portal still emptied the list — the fix compiled and did nothing.
		// Guard the wiring itself: startQueue must arm the cache.
		String src = java.nio.file.Files.readString(java.nio.file.Path.of(
			"src/client/java/com/chestmemory/client/litematica/BuildGatherSession.java"));
		int start = src.indexOf("public static void startQueue(");
		assertTrue(start > 0, "startQueue not found — rename? update this test");
		int end = src.indexOf("\n\tpublic static", start + 10);
		String body = end > start ? src.substring(start, end) : src.substring(start);
		assertTrue(
			body.contains("MaterialListCache.setArmed(true)"),
			"startQueue must arm MaterialListCache, otherwise a portal empties the gather list"
		);
	}
}
