package com.chestmemory.client.litematica;

import com.chestmemory.client.data.ChestMemoryStorage;
import com.chestmemory.client.data.ContainerRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Greedy nearest-neighbour route through chests that hold an item,
 * stopping once accumulated stock covers {@code need}.
 */
public final class ChestRoute {
	private ChestRoute() {
	}

	public record Stop(ContainerRecord record, BlockPos pos, int order, int takeFromHere, double legDist) {
	}

	/**
	 * @param candidates chests with the item (same dimension)
	 * @param start      player position
	 * @param dimension  player dimension
	 * @param itemId     item being gathered
	 * @param need       how many more are needed
	 */
	public static List<Stop> build(
		List<ContainerRecord> candidates,
		Vec3 start,
		@Nullable String dimension,
		String itemId,
		int need
	) {
		if (candidates == null || candidates.isEmpty() || need <= 0 || start == null) {
			return List.of();
		}

		List<ContainerRecord> remaining = new ArrayList<>(candidates);
		Set<String> used = new HashSet<>();
		List<Stop> route = new ArrayList<>();
		Vec3 cursor = start;
		int stillNeed = need;
		int order = 1;

		while (!remaining.isEmpty() && stillNeed > 0 && order <= 32) {
			ContainerRecord best = null;
			double bestDist = Double.MAX_VALUE;
			BlockPos bestPos = null;

			for (ContainerRecord r : remaining) {
				String key = r.positionKey();
				if (used.contains(key)) {
					continue;
				}
				BlockPos p = posOf(r);
				if (p == null) {
					continue;
				}
				double d = ChestMemoryStorage.distanceTo(r, cursor, dimension);
				if (d < 0) {
					// fallback euclidean
					d = cursor.distanceTo(Vec3.atCenterOf(p));
				}
				if (d < bestDist) {
					bestDist = d;
					best = r;
					bestPos = p;
				}
			}

			if (best == null || bestPos == null) {
				break;
			}

			int available = best.countOf(itemId);
			int take = Math.min(stillNeed, available);
			if (take <= 0) {
				used.add(best.positionKey());
				remaining.remove(best);
				continue;
			}

			route.add(new Stop(best, bestPos, order, take, bestDist));
			stillNeed -= take;
			used.add(best.positionKey());
			remaining.remove(best);
			cursor = Vec3.atCenterOf(bestPos);
			order++;
		}

		return route;
	}

	/** Total walking distance of the route (sum of legs). */
	public static double totalLength(List<Stop> route) {
		double t = 0;
		for (Stop s : route) {
			t += Math.max(0, s.legDist());
		}
		return t;
	}

	private static @Nullable BlockPos posOf(ContainerRecord r) {
		if (r.isVirtual()) {
			if (!r.hasHighlightPos()) {
				return null;
			}
			return new BlockPos(r.highlightX(), r.highlightY(), r.highlightZ());
		}
		return new BlockPos(r.x(), r.y(), r.z());
	}
}
