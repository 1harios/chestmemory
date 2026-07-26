package com.chestmemory;

import com.chestmemory.client.litematica.LitematicaCompat.MaterialNeed;
import com.chestmemory.client.litematica.MaterialListCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
		// setArmed(false) no longer wipes the copy, so tests have to clear it explicitly.
		MaterialListCache.setArmed(false);
		MaterialListCache.clear();
	}

	@Test
	@DisplayName("Nothing cached yet: an empty list stays empty")
	void nothingCachedPassesThrough() {
		// With no copy taken there is nothing to serve, so an empty list is the honest answer.
		MaterialListCache.clear();
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
	@DisplayName("Stopping the gather keeps the copy, so the gather can be started again")
	void disarmKeepsCache() {
		// Changed deliberately. Litematica only recreates a material list when the player opens
		// it by hand, so throwing the copy away when a gather finished left no list from either
		// side — and after a portal the «Сбор» button could no longer be pressed. The copy now
		// survives until the schematic changes.
		MaterialListCache.setArmed(true);
		MaterialListCache.resolve(DIRT_AND_PEONY, "Build");

		MaterialListCache.setArmed(false);

		assertEquals(2, MaterialListCache.resolve(List.of(), null).size(),
			"the build must still be known after the gather ends");
	}

	@Test
	@DisplayName("clear() still drops everything, for a real reset")
	void explicitClearDrops() {
		MaterialListCache.setArmed(true);
		MaterialListCache.resolve(DIRT_AND_PEONY, "Build");

		MaterialListCache.clear();

		assertEquals(List.of(), MaterialListCache.resolve(List.of(), null));
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
