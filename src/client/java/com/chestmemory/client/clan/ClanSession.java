package com.chestmemory.client.clan;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared clan build-gather session (synced via hub).
 */
public final class ClanSession {
	public String code = "";
	public String name = "";
	public String schemaName = "";
	public String hostName = "";
	public String hostUuid = "";
	public long createdAt;
	public long updatedAt;
	public int revision;
	/** The hub's clock when this snapshot was produced — see {@link #isMemberAway}. */
	public long now;
	/** Local clock when this snapshot arrived (set by the client, not the hub). */
	public transient long receivedAt;
	public final List<ClanMember> members = new ArrayList<>();
	/** itemId → progress */
	public final Map<String, ClanMaterial> materials = new LinkedHashMap<>();
	public final List<String> stagingKeys = new ArrayList<>();

	public static final class ClanMember {
		public String name = "";
		public String uuid = "";
		public long lastSeen;

		/**
		 * @deprecated compares the hub's clock against the local one, so any clock skew
		 * between the player's machine and the server marked everyone away (or nobody,
		 * ever). Use {@link ClanSession#isMemberAway} — it measures in hub time.
		 */
		@Deprecated
		public boolean isAway() {
			return lastSeen > 0 && System.currentTimeMillis() - lastSeen > 180_000L;
		}
	}

	public static final class ClanMaterial {
		public int need;
		public int delivered;
		public @Nullable String claimedBy;
		public @Nullable String claimedName;
		/**
		 * When the claim was taken, in the hub's clock. Zero on materials nobody holds,
		 * and on sessions created before the hub started recording it.
		 * <p>
		 * This exists so "who is carrying what" has one answer everywhere. A member who
		 * claims glass and then stone is working the glass — they clicked it first — but
		 * click order is local knowledge. Without a hub-side timestamp the members panel
		 * fell back to whatever order the materials happen to sit in the map, and could
		 * name the stone while the collector's own HUD named the glass.
		 */
		public long claimedAt;
		/** Who last raised the delivered count — the hub records it on every increase. */
		public @Nullable String lastDeliveredBy;
		/**
		 * Struck off the gather by the host: nobody collects it, no claim may be taken on
		 * it, and it counts toward neither need nor delivered.
		 * <p>
		 * The material is marked rather than deleted — its delivered count is real history,
		 * and un-excluding has to be able to restore it.
		 */
		public boolean excluded;
	}

	/**
	 * True when the hub has not heard from this member for a while.
	 * <p>
	 * Measured entirely in the hub's clock: its {@code now} at snapshot time plus how long
	 * ago the snapshot arrived locally. Comparing the hub's {@code lastSeen} against the
	 * player's wall clock broke on any clock skew between the two machines.
	 */
	public boolean isMemberAway(ClanMember m) {
		if (m == null || m.lastSeen <= 0) {
			return false;
		}
		long hubNow = now > 0
			? now + Math.max(0, System.currentTimeMillis() - receivedAt)
			: System.currentTimeMillis();
		return hubNow - m.lastSeen > 180_000L;
	}

	/**
	 * Total still wanted, excluded materials left out.
	 * <p>
	 * Counting a struck-off material would leave the bar short of 100% forever, which is
	 * the opposite of what excluding it was for.
	 */
	public int totalNeed() {
		int t = 0;
		for (ClanMaterial m : materials.values()) {
			if (m.excluded) {
				continue;
			}
			t += Math.max(0, m.need);
		}
		return t;
	}

	/** Total handed in, excluded materials left out — the mirror of {@link #totalNeed}. */
	public int totalDelivered() {
		int t = 0;
		for (ClanMaterial m : materials.values()) {
			if (m.excluded) {
				continue;
			}
			t += Math.max(0, Math.min(m.need, m.delivered));
		}
		return t;
	}

	public int remaining(String itemId) {
		ClanMaterial m = materials.get(itemId);
		if (m == null || m.excluded) {
			return 0;
		}
		return Math.max(0, m.need - m.delivered);
	}

	public @Nullable ClanMaterial material(String itemId) {
		return itemId == null ? null : materials.get(itemId);
	}

	/** True when the host struck this material off the gather. */
	public boolean isExcluded(@Nullable String itemId) {
		ClanMaterial m = material(itemId);
		return m != null && m.excluded;
	}

	/**
	 * The claim this member took first, or null when they hold none.
	 * <p>
	 * Ordered by {@code claimedAt} so every client — the holder's HUD and everyone else's
	 * members panel — names the same material. Materials from before the hub recorded
	 * timestamps have {@code claimedAt == 0} and sort ahead of timed ones, which keeps
	 * old sessions on their previous behaviour instead of reshuffling them.
	 */
	public @Nullable String firstClaimOf(@Nullable String uuid) {
		if (uuid == null || uuid.isBlank()) {
			return null;
		}
		String best = null;
		long bestAt = Long.MAX_VALUE;
		for (Map.Entry<String, ClanMaterial> e : materials.entrySet()) {
			ClanMaterial m = e.getValue();
			if (m.excluded || m.claimedBy == null || !uuid.equalsIgnoreCase(m.claimedBy)) {
				continue;
			}
			long at = m.claimedAt > 0 ? m.claimedAt : 0L;
			if (best == null || at < bestAt) {
				best = e.getKey();
				bestAt = at;
			}
		}
		return best;
	}
}
