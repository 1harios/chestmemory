package com.chestmemory.client.data;

import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Stable keys for item stacks that differ by enchantments
 * (enchanted books, enchanted tools/armor, etc.).
 * <p>
 * Format: {@code namespace:path} or
 * {@code namespace:path#e:minecraft:sharpness=5+s:minecraft:mending=1}
 * where {@code e:} = applied enchantments, {@code s:} = stored (books).
 */
public final class ItemStackKeys {
	private ItemStackKeys() {
	}

	/** Full identity key including enchantments. */
	public static String keyOf(ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return "minecraft:air";
		}
		Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
		if (id == null) {
			return "minecraft:air";
		}
		String base = id.toString();
		List<String> parts = new ArrayList<>();
		appendEnchantParts(parts, "s", stack.getOrDefault(DataComponents.STORED_ENCHANTMENTS, ItemEnchantments.EMPTY));
		appendEnchantParts(parts, "e", stack.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY));
		if (parts.isEmpty()) {
			return base;
		}
		Collections.sort(parts);
		return base + "#" + String.join("+", parts);
	}

	/** Registry id only (before {@code #}). */
	public static String baseId(String key) {
		if (key == null) {
			return "minecraft:air";
		}
		int hash = key.indexOf('#');
		return hash < 0 ? key : key.substring(0, hash);
	}

	public static boolean hasEnchantData(String key) {
		return key != null && key.indexOf('#') >= 0;
	}

	/** True if this stack matches the stored key (exact identity). */
	public static boolean matches(ItemStack stack, String key) {
		if (stack == null || stack.isEmpty() || key == null) {
			return false;
		}
		return keyOf(stack).equals(key);
	}

	/** Build an ItemStack for icons / display from a key. */
	public static ItemStack toStack(String key) {
		if (key == null || key.isBlank()) {
			return new ItemStack(Items.CHEST);
		}
		Identifier id = Identifier.tryParse(baseId(key));
		if (id == null) {
			return new ItemStack(Items.CHEST);
		}
		Item item = BuiltInRegistries.ITEM.getValue(id);
		if (item == null || item == Items.AIR) {
			return new ItemStack(Items.CHEST);
		}
		ItemStack stack = new ItemStack(item);
		if (!hasEnchantData(key)) {
			return stack;
		}

		HolderLookup.RegistryLookup<Enchantment> lookup = enchantmentLookup();
		if (lookup == null) {
			return stack;
		}

		ItemEnchantments.Mutable stored = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
		ItemEnchantments.Mutable applied = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
		boolean anyStored = false;
		boolean anyApplied = false;

		String enc = key.substring(key.indexOf('#') + 1);
		for (String part : enc.split("\\+")) {
			if (part.length() < 4) {
				continue;
			}
			boolean isStored = part.startsWith("s:");
			boolean isApplied = part.startsWith("e:");
			if (!isStored && !isApplied) {
				continue;
			}
			String body = part.substring(2);
			int eq = body.lastIndexOf('=');
			if (eq <= 0) {
				continue;
			}
			Identifier eid = Identifier.tryParse(body.substring(0, eq));
			if (eid == null) {
				continue;
			}
			int level;
			try {
				level = Integer.parseInt(body.substring(eq + 1));
			} catch (NumberFormatException e) {
				continue;
			}
			Optional<Holder.Reference<Enchantment>> holder =
				lookup.get(ResourceKey.create(Registries.ENCHANTMENT, eid));
			if (holder.isEmpty()) {
				continue;
			}
			if (isStored) {
				stored.set(holder.get(), level);
				anyStored = true;
			} else {
				applied.set(holder.get(), level);
				anyApplied = true;
			}
		}
		if (anyStored) {
			stack.set(DataComponents.STORED_ENCHANTMENTS, stored.toImmutable());
		}
		if (anyApplied) {
			stack.set(DataComponents.ENCHANTMENTS, applied.toImmutable());
		}
		return stack;
	}

	/** Human-readable name including enchantments. */
	public static String displayName(String key) {
		if (key == null || key.isBlank()) {
			return "?";
		}
		ItemStack stack = toStack(key);
		String base = stack.getHoverName().getString();
		if (!hasEnchantData(key)) {
			return base;
		}
		List<String> names = enchantDisplayNames(key);
		if (names.isEmpty()) {
			return base;
		}
		return base + " (" + String.join(", ", names) + ")";
	}

	/** For search matching. */
	public static String searchBlob(String key) {
		return (key + " " + displayName(key)).toLowerCase(Locale.ROOT);
	}

	private static List<String> enchantDisplayNames(String key) {
		List<String> names = new ArrayList<>();
		if (!hasEnchantData(key)) {
			return names;
		}
		HolderLookup.RegistryLookup<Enchantment> lookup = enchantmentLookup();
		String enc = key.substring(key.indexOf('#') + 1);
		for (String part : enc.split("\\+")) {
			if (part.length() < 4 || (part.charAt(0) != 's' && part.charAt(0) != 'e') || part.charAt(1) != ':') {
				continue;
			}
			String body = part.substring(2);
			int eq = body.lastIndexOf('=');
			if (eq <= 0) {
				continue;
			}
			Identifier eid = Identifier.tryParse(body.substring(0, eq));
			int level;
			try {
				level = Integer.parseInt(body.substring(eq + 1));
			} catch (NumberFormatException e) {
				continue;
			}
			if (eid == null) {
				continue;
			}
			if (lookup != null) {
				Optional<Holder.Reference<Enchantment>> holder =
					lookup.get(ResourceKey.create(Registries.ENCHANTMENT, eid));
				if (holder.isPresent()) {
					names.add(Enchantment.getFullname(holder.get(), level).getString());
					continue;
				}
			}
			// Fallback: translation key
			String path = eid.getPath().replace('_', ' ');
			if (level > 1) {
				names.add(path + " " + level);
			} else {
				names.add(path);
			}
		}
		Collections.sort(names);
		return names;
	}

	private static void appendEnchantParts(List<String> parts, String prefix, ItemEnchantments enchants) {
		if (enchants == null || enchants.isEmpty()) {
			return;
		}
		for (Holder<Enchantment> holder : enchants.keySet()) {
			int level = enchants.getLevel(holder);
			if (level <= 0) {
				continue;
			}
			String id = enchantId(holder);
			if (id == null) {
				continue;
			}
			parts.add(prefix + ":" + id + "=" + level);
		}
	}

	private static @Nullable String enchantId(Holder<Enchantment> holder) {
		return holder.unwrapKey()
			.map(ResourceKey::identifier)
			.map(Identifier::toString)
			.orElse(null);
	}

	private static HolderLookup.@Nullable RegistryLookup<Enchantment> enchantmentLookup() {
		Minecraft mc = Minecraft.getInstance();
		if (mc == null || mc.level == null) {
			return null;
		}
		try {
			return mc.level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
		} catch (Exception e) {
			return null;
		}
	}
}
