package com.chestmemory.client.data;

import net.minecraft.network.chat.Component;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Locale;

/**
 * Filter for remembered container types in the Ё panel.
 * Multiple specific types can be combined (chests + barrels + hoppers…).
 */
public enum ContainerFilter {
	ALL("all"),
	CHEST("chest"),
	BARREL("barrel"),
	SHULKER("shulker"),
	HOPPER("hopper"),
	DISPENSER("dispenser"),
	ENDER("ender"),
	INVENTORY_SHULKER("inventory_shulker");

	private final String id;

	ContainerFilter(String id) {
		this.id = id;
	}

	public String id() {
		return id;
	}

	public Component label() {
		return Component.translatable("screen.chestmemory.filter." + id);
	}

	public boolean matches(ContainerRecord record) {
		if (this == ALL) {
			return true;
		}
		String type = record.type() == null ? "" : record.type().toLowerCase(Locale.ROOT);
		return switch (this) {
			case ALL -> true;
			// Double chests count as chests (one container)
			case CHEST -> (type.contains("chest") || type.equals("double_chest"))
				&& !type.contains("ender") && !type.contains("shulker");
			case BARREL -> type.contains("barrel");
			case SHULKER -> type.contains("shulker") && !type.contains("inventory");
			case HOPPER -> type.contains("hopper");
			case DISPENSER -> type.contains("dispenser") || type.contains("dropper");
			case ENDER -> type.contains("ender");
			case INVENTORY_SHULKER -> type.contains("inventory_shulker") || type.equals("inv_shulker");
		};
	}

	/** True if record matches ANY of the selected types (OR). Empty / ALL → everything. */
	public static boolean matchesAny(ContainerRecord record, Collection<ContainerFilter> filters) {
		if (filters == null || filters.isEmpty() || filters.contains(ALL)) {
			return true;
		}
		for (ContainerFilter f : filters) {
			if (f != null && f != ALL && f.matches(record)) {
				return true;
			}
		}
		return false;
	}

	public static EnumSet<ContainerFilter> allTypes() {
		return EnumSet.of(ALL);
	}

	public static EnumSet<ContainerFilter> parse(String csv) {
		EnumSet<ContainerFilter> set = EnumSet.noneOf(ContainerFilter.class);
		if (csv == null || csv.isBlank() || "ALL".equalsIgnoreCase(csv.trim())) {
			set.add(ALL);
			return set;
		}
		for (String part : csv.split("[,;+|]+")) {
			String p = part.trim();
			if (p.isEmpty()) {
				continue;
			}
			try {
				ContainerFilter f = ContainerFilter.valueOf(p.toUpperCase(Locale.ROOT));
				set.add(f);
			} catch (Exception ignored) {
			}
		}
		if (set.isEmpty() || set.contains(ALL)) {
			return allTypes();
		}
		set.remove(ALL);
		return set.isEmpty() ? allTypes() : set;
	}

	public static String serialize(Collection<ContainerFilter> filters) {
		if (filters == null || filters.isEmpty() || filters.contains(ALL)) {
			return "ALL";
		}
		StringBuilder sb = new StringBuilder();
		for (ContainerFilter f : ContainerFilter.values()) {
			if (f == ALL || !filters.contains(f)) {
				continue;
			}
			if (!sb.isEmpty()) {
				sb.append(',');
			}
			sb.append(f.name());
		}
		return sb.isEmpty() ? "ALL" : sb.toString();
	}
}
