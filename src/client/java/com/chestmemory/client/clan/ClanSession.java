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
	public final List<ClanMember> members = new ArrayList<>();
	/** itemId → progress */
	public final Map<String, ClanMaterial> materials = new LinkedHashMap<>();
	public final List<String> stagingKeys = new ArrayList<>();

	public static final class ClanMember {
		public String name = "";
		public String uuid = "";
		public long lastSeen;
	}

	public static final class ClanMaterial {
		public int need;
		public int delivered;
		public @Nullable String claimedBy;
		public @Nullable String claimedName;
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
