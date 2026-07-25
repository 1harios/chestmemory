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
	/** Optional virtual id for non-world containers (ender chest, inventory shulkers). */
	private String virtualId;
	/** item id -> total count */
	private Map<String, Integer> items = new LinkedHashMap<>();
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

	public String positionKey() {
		if (isVirtual()) {
			return "virtual|" + virtualId;
		}
		return dimension + "|" + x + "," + y + "," + z;
	}

	public static String makeKey(String dimension, int x, int y, int z) {
		return dimension + "|" + x + "," + y + "," + z;
	}

	public String shortLocation() {
		if (isVirtual()) {
			return displayName != null ? displayName : virtualId;
		}
		return x + ", " + y + ", " + z + (doubleChest ? " (double)" : "");
	}
}
