package com.chestmemory.client.data;

import org.jspecify.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One-time normalization applied to every profile as it is loaded from disk.
 * <p>
 * Two things changed across format versions and both are handled here, losslessly:
 * <ul>
 *   <li><b>Legacy world tags.</b> Old versions stamped records with spawn-position tags
 *       ({@code s100_64_200}). Compared against the current seed-based tags they would read
 *       as "different world" for a chest that is right here, so they are cleared to
 *       "unknown" — which every read path treats as "show it".</li>
 *   <li><b>Key format.</b> The map key is rebuilt from the record itself
 *       ({@link ContainerRecord#positionKey()}), so files written by any version land on the
 *       exact key the rest of the code will look up, tagged or legacy alike.</li>
 * </ul>
 * Nothing is deleted: on the (theoretical) collision of two records normalizing to one key,
 * the most recently seen record wins — the same rule a fresh rescan would apply.
 * <p>
 * Free of Minecraft imports so the migration stays unit-testable.
 */
public final class ProfileMigration {
	private ProfileMigration() {
	}

	public static Map<String, ContainerRecord> normalize(@Nullable Map<String, ContainerRecord> loaded) {
		Map<String, ContainerRecord> out = new LinkedHashMap<>();
		if (loaded == null || loaded.isEmpty()) {
			return out;
		}
		for (Map.Entry<String, ContainerRecord> entry : loaded.entrySet()) {
			ContainerRecord record = entry.getValue();
			if (record == null) {
				continue;
			}
			// Gson accepts "items": {"minecraft:stone": null} and a hand-edited or partly
			// truncated file can carry one. The parse then succeeds, so loadFromDisk's guard
			// never fires, and the null only surfaces later as an unboxing NPE inside countOf
			// and listItems — on the render thread, every tick, for as long as the profile is
			// loaded. This walks every record already, so it is the right place to scrub.
			record.dropUnusableCounts();
			String sanitized = WorldTags.sanitize(record.worldTag());
			if (sanitized == null && record.worldTag() != null) {
				record.setWorldTag(null);
			}
			String key = record.positionKey();
			ContainerRecord existing = out.get(key);
			if (existing == null || record.lastSeenMillis() >= existing.lastSeenMillis()) {
				out.put(key, record);
			}
		}
		return out;
	}
}
