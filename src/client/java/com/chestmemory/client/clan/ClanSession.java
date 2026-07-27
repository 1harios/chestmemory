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
		/** Who last raised the delivered count — the hub records it on every increase. */
		public @Nullable String lastDeliveredBy;
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

	public int totalNeed() {
		int t = 0;
		for (ClanMaterial m : materials.values()) {
			t += Math.max(0, m.need);
		}
		return t;
	}

	public int totalDelivered() {
		int t = 0;
		for (ClanMaterial m : materials.values()) {
			t += Math.max(0, Math.min(m.need, m.delivered));
		}
		return t;
	}

	public int remaining(String itemId) {
		ClanMaterial m = materials.get(itemId);
		if (m == null) {
			return 0;
		}
		return Math.max(0, m.need - m.delivered);
	}

	public @Nullable ClanMaterial material(String itemId) {
		return itemId == null ? null : materials.get(itemId);
	}
}
