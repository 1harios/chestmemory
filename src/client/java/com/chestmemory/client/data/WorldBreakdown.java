package com.chestmemory.client.data;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Where an item actually lies, grouped by world — the answer to "I clicked diamonds and
 * nothing glows": they are in the farm world, not here.
 * <p>
 * Groups world-block records by dimension id, and splits the player's own dimension into
 * "here" and "another world with the same dimension id" using the world tags introduced by
 * the multiworld fix. Virtual records (ender chest, inventory shulkers) are personal — they
 * travel with the player — and are reported separately via {@link #personalCount}.
 * <p>
 * Free of Minecraft imports so the grouping stays unit-testable.
 */
public final class WorldBreakdown {
	/**
	 * @param dimensionId raw dimension id of the group
	 * @param here        records provably (well, not disprovably) in the player's world
	 * @param otherWorld  same dimension id as the player, but provably a different world
	 *                    (multiworld farm vs build); {@code dimensionId} alone cannot name it
	 * @param count       total items in this group
	 * @param containers  containers in this group
	 */
	public record Entry(String dimensionId, boolean here, boolean otherWorld, int count, int containers) {
	}

	private WorldBreakdown() {
	}

	/**
	 * Group world-block holdings of an item by world. Entries are ordered: "here" first,
	 * then by descending count.
	 */
	public static List<Entry> of(
		Iterable<ContainerRecord> records,
		String itemId,
		@Nullable String playerDimension,
		@Nullable String currentWorldTag
	) {
		// key -> [count, containers]; key encodes the group identity
		Map<String, int[]> totals = new LinkedHashMap<>();
		Map<String, Entry> shapes = new LinkedHashMap<>();
		for (ContainerRecord r : records) {
			if (r == null || r.isVirtual()) {
				continue;
			}
			int n = r.countOf(itemId);
			if (n <= 0) {
				continue;
			}
			String dim = r.dimension() == null ? "?" : r.dimension();
			boolean sameDim = playerDimension != null && playerDimension.equals(dim);
			boolean foreign = sameDim && WorldTags.provablyDifferent(currentWorldTag, r.worldTag());
			boolean here = sameDim && !foreign;
			String key = dim + (foreign ? "@other" : here ? "@here" : "");
			totals.computeIfAbsent(key, k -> new int[2]);
			int[] t = totals.get(key);
			t[0] += n;
			t[1] += 1;
			shapes.putIfAbsent(key, new Entry(dim, here, foreign, 0, 0));
		}
		List<Entry> out = new ArrayList<>(totals.size());
		for (Map.Entry<String, int[]> e : totals.entrySet()) {
			Entry shape = shapes.get(e.getKey());
			out.add(new Entry(shape.dimensionId(), shape.here(), shape.otherWorld(), e.getValue()[0], e.getValue()[1]));
		}
		out.sort(Comparator
			.comparing((Entry e) -> !e.here())
			.thenComparing(Entry::count, Comparator.reverseOrder()));
		return out;
	}

	/** Items in personal storage — ender chest and inventory shulkers travel with the player. */
	public static int personalCount(Iterable<ContainerRecord> records, String itemId) {
		int total = 0;
		for (ContainerRecord r : records) {
			if (r != null && r.isVirtual()) {
				total += r.countOf(itemId);
			}
		}
		return total;
	}

	/** Sum of items in groups that are not "here". */
	public static int elsewhereCount(List<Entry> entries) {
		int total = 0;
		for (Entry e : entries) {
			if (!e.here()) {
				total += e.count();
			}
		}
		return total;
	}

	/** Sum of items in the "here" group (usually zero or one entry). */
	public static int hereCount(List<Entry> entries) {
		int total = 0;
		for (Entry e : entries) {
			if (e.here()) {
				total += e.count();
			}
		}
		return total;
	}
}
