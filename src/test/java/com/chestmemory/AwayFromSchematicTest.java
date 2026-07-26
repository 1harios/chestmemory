package com.chestmemory;

import com.chestmemory.client.litematica.LitematicaCompat.MaterialNeed;
import com.chestmemory.client.litematica.MaterialListCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The HUD warning "schematic elsewhere" used to stick forever after coming home.
 * <p>
 * It was keyed on "Litematica has no material list", which sounded equivalent to "we are
 * away" but is not: Litematica clears the list on a world load and never recreates it — only
 * the player does, from its own menu. So the condition stayed true in the schematic's own
 * world too. These tests pin the dimension-based check that replaced it.
 */
class AwayFromSchematicTest {
	private static final String OVERWORLD = "minecraft:overworld";
	private static final String NETHER = "minecraft:the_nether";
	private static final List<MaterialNeed> DIRT = List.of(new MaterialNeed("minecraft:dirt", 4, 4, 0));

	@BeforeEach
	void reset() {
		MaterialListCache.setArmed(false);
	}

	@Test
	@DisplayName("In the schematic's own world: no warning")
	void homeWorldIsNotAway() {
		MaterialListCache.setArmed(true);
		MaterialListCache.resolve(DIRT, "Build", OVERWORLD);

		assertFalse(MaterialListCache.isAwayFromSchematic(OVERWORLD));
	}

	@Test
	@DisplayName("After a portal into the Nether: warning shows")
	void otherDimensionIsAway() {
		MaterialListCache.setArmed(true);
		MaterialListCache.resolve(DIRT, "Build", OVERWORLD);

		assertTrue(MaterialListCache.isAwayFromSchematic(NETHER));
	}

	@Test
	@DisplayName("Regression: coming home clears the warning even though Litematica has no list")
	void warningClearsOnReturn() {
		MaterialListCache.setArmed(true);
		MaterialListCache.resolve(DIRT, "Build", OVERWORLD);

		// Portal out, then back. Litematica reports nothing in both cases — that is the whole
		// point: it does not restore the list by itself.
		MaterialListCache.resolve(List.of(), null, NETHER);
		assertTrue(MaterialListCache.isAwayFromSchematic(NETHER), "away while in the Nether");

		MaterialListCache.resolve(List.of(), null, OVERWORLD);
		assertFalse(
			MaterialListCache.isAwayFromSchematic(OVERWORLD),
			"back home: the warning must clear, this is the bug the user reported"
		);
	}

	@Test
	@DisplayName("Not armed: never warns")
	void notArmedNeverWarns() {
		MaterialListCache.resolve(DIRT, "Build", OVERWORLD);
		assertFalse(MaterialListCache.isAwayFromSchematic(NETHER));
	}

	@Test
	@DisplayName("Unknown dimension is never treated as away")
	void unknownDimensionIsNotAway() {
		MaterialListCache.setArmed(true);
		MaterialListCache.resolve(DIRT, "Build", OVERWORLD);

		assertFalse(MaterialListCache.isAwayFromSchematic(null));
	}

	@Test
	@DisplayName("The captured dimension follows the list, not the player")
	void dimensionCapturedWithList() {
		MaterialListCache.setArmed(true);
		MaterialListCache.resolve(DIRT, "Build", OVERWORLD);
		// A live list seen in the Nether means the schematic is there now.
		MaterialListCache.resolve(DIRT, "Build", NETHER);

		assertFalse(MaterialListCache.isAwayFromSchematic(NETHER));
		assertTrue(MaterialListCache.isAwayFromSchematic(OVERWORLD));
	}

	@Test
	@DisplayName("The HUD text has to fit the 148px box")
	void warningTextFits() throws Exception {
		// The first version read "Схема в другом мире — список сохранён", which is ~222px in
		// the default font and was silently cut off. The HUD box is 160px wide with 6px
		// padding each side, so ~24 characters at 6px per glyph.
		String ru = java.nio.file.Files.readString(java.nio.file.Path.of(
			"src/main/resources/assets/chestmemory/lang/ru_ru.json"));
		java.util.regex.Matcher m = java.util.regex.Pattern
			.compile("\"hud\\.chestmemory\\.list_cached\":\\s*\"([^\"]*)\"")
			.matcher(ru);
		assertTrue(m.find(), "lang key missing");
		String text = m.group(1);
		assertTrue(
			text.length() * 6 <= 148,
			"HUD notice too wide: '" + text + "' ≈ " + (text.length() * 6) + "px of 148"
		);
	}
}
