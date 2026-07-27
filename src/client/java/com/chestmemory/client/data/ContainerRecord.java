package com.chestmemory.client.data;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One remembered container (chest, barrel, shulker, ender chest, inventory shulker, etc.).
 * Double chests are stored once under a canonical block position.
 */
public final class ContainerRecord {
	private String type;
	private int x;
	private int y;
	private int z;
	private String dimension;
	/**
	 * Which world this was seen in, when the dimension id alone cannot say.
	 * <p>
	 * A multiworld server gives every world the same vanilla dimension keys, so a farm
	 * world's Nether and a build world's Nether both arrive as {@code minecraft:the_nether}.
	 * See {@link WorldFingerprint}. Null on records written before this field existed and on
	 * servers that offer nothing to fingerprint with, so callers must read null as "unknown",
	 * never as "different".
	 */
	private String worldTag;
	/** Optional virtual id for non-world containers (ender chest, inventory shulkers). */
	private String virtualId;
	/** item id -> total count */
	private Map<String, Integer> items = new LinkedHashMap<>();
	/**
	 * item id -> the part of {@link #items} that lies inside shulker boxes stored in this
	 * container. Nested shulker contents are merged into the flat totals at scan time; this
	 * keeps their origin, so the panel can answer "how much of it is in shulkers". Null on
	 * records written before the field existed and on containers with no shulkers.
	 */
	private Map<String, Integer> shulkerItems;
	private long lastSeenMillis;
	/** Display name for inventory shulkers, etc. */
	private String displayName;
	/** Optional highlight position for virtual containers (e.g. last opened ender chest). */
	private Integer highlightX;
	private Integer highlightY;
	private Integer highlightZ;
	/** True when this entry represents a double chest (still one container). */
	private boolean doubleChest;
	/** Other half of a double chest (for full highlight). */
	private Integer otherX;
	private Integer otherY;
	private Integer otherZ;

	public ContainerRecord() {
	}

	public ContainerRecord(String type, String dimension, int x, int y, int z) {
		this.type = type;
		this.dimension = dimension;
		this.x = x;
		this.y = y;
		this.z = z;
		this.lastSeenMillis = System.currentTimeMillis();
	}

	public static ContainerRecord virtual(String type, String virtualId, String dimension) {
		ContainerRecord record = new ContainerRecord(type, dimension, 0, 0, 0);
		record.virtualId = virtualId;
		return record;
	}

	/** Fingerprint of the world this was seen in, or null when unknown. */
	public String worldTag() {
		return worldTag;
	}

	public void setWorldTag(String worldTag) {
		this.worldTag = worldTag;
	}

	public String type() {
		return type;
	}

	public void setType(String type) {
		this.type = type;
	}

	public int x() {
		return x;
	}

	public int y() {
		return y;
	}

	public int z() {
		return z;
	}

	public void setPosition(int x, int y, int z) {
		this.x = x;
		this.y = y;
		this.z = z;
	}

	public String dimension() {
		return dimension;
	}

	public String virtualId() {
		return virtualId;
	}

	public void setVirtualId(String virtualId) {
		this.virtualId = virtualId;
	}

	public boolean isVirtual() {
		return virtualId != null && !virtualId.isEmpty();
	}

	public boolean isWorldBlock() {
		return !isVirtual();
	}

	public Map<String, Integer> items() {
		return items == null ? Collections.emptyMap() : items;
	}

	public void setItems(Map<String, Integer> items) {
		this.items = items != null ? new LinkedHashMap<>(items) : new LinkedHashMap<>();
	}

	/** Portion of the totals that lies inside shulker boxes stored in this container. */
	public Map<String, Integer> shulkerItems() {
		return shulkerItems == null ? Collections.emptyMap() : shulkerItems;
	}

	public void setShulkerItems(Map<String, Integer> shulkerItems) {
		this.shulkerItems = shulkerItems == null || shulkerItems.isEmpty()
			? null
			: new LinkedHashMap<>(shulkerItems);
	}

	/** How many of this item lie inside shulker boxes stored in this container. */
	public int shulkerCountOf(String itemId) {
		return shulkerItems == null ? 0 : shulkerItems.getOrDefault(itemId, 0);
	}

	public long lastSeenMillis() {
		return lastSeenMillis;
	}

	public void setLastSeenMillis(long lastSeenMillis) {
		this.lastSeenMillis = lastSeenMillis;
	}

	public String displayName() {
		return displayName;
	}

	public void setDisplayName(String displayName) {
		this.displayName = displayName;
	}

	public boolean doubleChest() {
		return doubleChest;
	}

	public void setDoubleChest(boolean doubleChest) {
		this.doubleChest = doubleChest;
	}

	public void setOtherHalf(int x, int y, int z) {
		this.otherX = x;
		this.otherY = y;
		this.otherZ = z;
	}

	public boolean hasOtherHalf() {
		return otherX != null && otherY != null && otherZ != null;
	}

	public int otherX() {
		return otherX == null ? x : otherX;
	}

	public int otherY() {
		return otherY == null ? y : otherY;
	}

	public int otherZ() {
		return otherZ == null ? z : otherZ;
	}

	public void setHighlightPos(int x, int y, int z) {
		this.highlightX = x;
		this.highlightY = y;
		this.highlightZ = z;
	}

	public boolean hasHighlightPos() {
		return highlightX != null && highlightY != null && highlightZ != null;
	}

	public int highlightX() {
		return highlightX == null ? x : highlightX;
	}

	public int highlightY() {
		return highlightY == null ? y : highlightY;
	}

	public int highlightZ() {
		return highlightZ == null ? z : highlightZ;
	}

	public int countOf(String itemId) {
		return items == null ? 0 : items.getOrDefault(itemId, 0);
	}

	public boolean hasItem(String itemId) {
		return countOf(itemId) > 0;
	}

	public int totalItemCount() {
		if (items == null) {
			return 0;
		}
		int total = 0;
		for (int c : items.values()) {
			total += c;
		}
		return total;
	}

	public int itemTypeCount() {
		return items == null ? 0 : items.size();
	}

	/**
	 * Storage key for this record. World blocks carry the world tag as a suffix when one is
	 * known ({@code dim|x,y,z@w1a2b3c4}), so two worlds that share a dimension id can hold a
	 * chest at the same coordinates without colliding. Records with no tag keep the legacy
	 * key ({@code dim|x,y,z}) — the two forms coexist in one profile during migration.
	 * Virtual records (ender chest, inventory shulkers) are personal, not per-world.
	 */
	public String positionKey() {
		if (isVirtual()) {
			return "virtual|" + virtualId;
		}
		return makeKey(dimension, x, y, z, worldTag);
	}

	public static String makeKey(String dimension, int x, int y, int z) {
		return dimension + "|" + x + "," + y + "," + z;
	}

	/** Key with a world-tag suffix; falls back to the legacy form when the tag is unknown. */
	public static String makeKey(String dimension, int x, int y, int z, String worldTag) {
		String base = dimension + "|" + x + "," + y + "," + z;
		if (worldTag == null || worldTag.isEmpty()) {
			return base;
		}
		return base + "@" + worldTag;
	}

	public String shortLocation() {
		if (isVirtual()) {
			return displayName != null ? displayName : virtualId;
		}
		return x + ", " + y + ", " + z + (doubleChest ? " (double)" : "");
	}
}
