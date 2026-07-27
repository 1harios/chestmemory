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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
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
	/**
	 * Resolving a key to a display name builds an ItemStack and hits the item and
	 * enchantment registries. That happened on every keystroke in the search box and on
	 * every comparison during sorting, so it is memoised here.
	 * <p>
	 * Keyed by item key; invalidated when the language changes, since the cached strings
	 * are localized. Access is confined to the client thread.
	 */
	private static final Map<String, String> DISPLAY_NAME_CACHE = new HashMap<>();
	private static final Map<String, String> SEARCH_BLOB_CACHE = new HashMap<>();
	private static @Nullable String cachedLanguage;

	private ItemStackKeys() {
	}

	/** Drops memoised names when the active language changed. */
	private static void ensureLanguageFresh() {
		Minecraft mc = Minecraft.getInstance();
		String lang = mc != null && mc.getLanguageManager() != null
			? mc.getLanguageManager().getSelected()
			: null;
		if (!Objects.equals(lang, cachedLanguage)) {
			cachedLanguage = lang;
			DISPLAY_NAME_CACHE.clear();
			SEARCH_BLOB_CACHE.clear();
		}
	}

	/** Clears memoised display names (language change, resource reload). */
	public static void clearNameCache() {
		DISPLAY_NAME_CACHE.clear();
		SEARCH_BLOB_CACHE.clear();
		cachedLanguage = null;
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
		// An anvil-renamed item is a distinct thing to the player — "Sorted Glass" should
		// not be pooled with plain glass in the list. Encoded as n:<escaped name>.
		String custom = customName(stack);
		if (custom != null) {
			parts.add("n:" + escapeNamePart(custom));
		}
		if (parts.isEmpty()) {
			return base;
		}
		Collections.sort(parts);
		return base + "#" + String.join("+", parts);
	}

	/** Anvil / data-pack custom name, or null when the item uses its default name. */
	private static @Nullable String customName(ItemStack stack) {
		Component name = stack.get(DataComponents.CUSTOM_NAME);
		if (name == null) {
			return null;
		}
		String text = name.getString().trim();
		if (text.isEmpty() || text.length() > 64) {
			return text.isEmpty() ? null : text.substring(0, 64);
		}
		return text;
	}

	/** Keep the key parseable: '+' separates parts and '=' splits enchant level. */
	private static String escapeNamePart(String raw) {
		return raw.replace("\\", "\\\\").replace("+", "\\p").replace("=", "\\e").replace("#", "\\h");
	}

	private static String unescapeNamePart(String raw) {
		return raw.replace("\\h", "#").replace("\\e", "=").replace("\\p", "+").replace("\\\\", "\\");
	}

	/** Custom name encoded in the key, or null. */
	public static @Nullable String customNameOf(String key) {
		if (!hasEnchantData(key)) {
			return null;
		}
		for (String part : key.substring(key.indexOf('#') + 1).split("\\+")) {
			if (part.startsWith("n:")) {
				return unescapeNamePart(part.substring(2));
			}
		}
		return null;
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
		String customName = customNameOf(key);
		if (customName != null) {
			stack.set(DataComponents.CUSTOM_NAME, Component.literal(customName));
		}

		HolderLookup.RegistryLookup<Enchantment> lookup = enchantmentLookup();
		if (lookup == null) {
			// Custom name is already applied above; only enchantments are unavailable.
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

	/** Human-readable name including enchantments. Memoised — see DISPLAY_NAME_CACHE. */
	public static String displayName(String key) {
		if (key == null || key.isBlank()) {
			return "?";
		}
		ensureLanguageFresh();
		String cached = DISPLAY_NAME_CACHE.get(key);
		if (cached != null) {
			return cached;
		}
		String name = computeDisplayName(key);
		DISPLAY_NAME_CACHE.put(key, name);
		return name;
	}

	private static String computeDisplayName(String key) {
		// Unknown / removed-mod items: show the raw key rather than the chest icon fallback
		// toStack() uses for rendering — a list row saying "Chest" for a modded ingot lies.
		Identifier baseIdent = Identifier.tryParse(baseId(key));
		if (baseIdent == null || BuiltInRegistries.ITEM.getValue(baseIdent) == Items.AIR) {
			return key;
		}
		ItemStack stack = toStack(key);
		String custom = customNameOf(key);
		// Renamed items are listed under their own name, which is how the player thinks
		// of them; the base item name follows in parentheses so the type stays obvious.
		String base = custom != null
			? custom + " (" + new ItemStack(stack.getItem()).getHoverName().getString() + ")"
			: stack.getHoverName().getString();
		if (!hasEnchantData(key)) {
			return base;
		}
		List<String> names = enchantDisplayNames(key);
		if (names.isEmpty()) {
			return base;
		}
		return base + " (" + String.join(", ", names) + ")";
	}

	/** For search matching. Memoised — rebuilt on every keystroke otherwise. */
	public static String searchBlob(String key) {
		if (key == null || key.isBlank()) {
			return "";
		}
		ensureLanguageFresh();
		String cached = SEARCH_BLOB_CACHE.get(key);
		if (cached != null) {
			return cached;
		}
		String blob = (key + " " + displayName(key)).toLowerCase(Locale.ROOT);
		SEARCH_BLOB_CACHE.put(key, blob);
		return blob;
	}

	/** Localized enchantment names encoded in a key — for rich tooltips. */
	public static List<String> enchantNames(String key) {
		return enchantDisplayNames(key);
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
