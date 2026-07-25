package com.chestmemory.client.data;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Dimension / custom multiworld filter for a multi-world server (farm, builds, nether hub, …).
 */
public final class DimensionChoice {
	public static final DimensionChoice ALL = new DimensionChoice(Kind.ALL, null);
	public static final DimensionChoice CURRENT = new DimensionChoice(Kind.CURRENT, null);

	public enum Kind {
		ALL,
		CURRENT,
		SPECIFIC
	}

	private final Kind kind;
	/** Full dimension id, e.g. minecraft:the_nether or custom:farm */
	private final @Nullable String dimensionId;

	private DimensionChoice(Kind kind, @Nullable String dimensionId) {
		this.kind = kind;
		this.dimensionId = dimensionId;
	}

	public static DimensionChoice of(String dimensionId) {
		if (dimensionId == null || dimensionId.isBlank()) {
			return ALL;
		}
		return new DimensionChoice(Kind.SPECIFIC, dimensionId);
	}

	public Kind kind() {
		return kind;
	}

	public @Nullable String dimensionId() {
		return dimensionId;
	}

	public Component label() {
		return switch (kind) {
			case ALL -> Component.translatable("screen.chestmemory.dimension.all");
			case CURRENT -> Component.translatable("screen.chestmemory.dimension.current");
			case SPECIFIC -> Component.literal(prettyName(dimensionId));
		};
	}

	/**
	 * Short nice name for custom worlds.
	 * <p>
	 * Matching is done on path/namespace <b>tokens</b> only — never naive
	 * {@code fullId.contains("mine")} which falsely matches {@code minecraft:...}.
	 */
	public static String prettyName(@Nullable String dimensionId) {
		if (dimensionId == null || dimensionId.isBlank()) {
			return "?";
		}

		// Vanilla exact keys first
		if (dimensionId.equals(Level.OVERWORLD.identifier().toString())) {
			return Component.translatable("screen.chestmemory.dimension.overworld").getString();
		}
		if (dimensionId.equals(Level.NETHER.identifier().toString())) {
			return Component.translatable("screen.chestmemory.dimension.nether").getString();
		}
		if (dimensionId.equals(Level.END.identifier().toString())) {
			return Component.translatable("screen.chestmemory.dimension.end").getString();
		}

		Identifier id = Identifier.tryParse(dimensionId);
		String path = id != null ? id.getPath() : dimensionId;
		String ns = id != null ? id.getNamespace() : "";
		String pathLower = path.toLowerCase(Locale.ROOT);
		String nsLower = ns.toLowerCase(Locale.ROOT);

		if (tokenMatch(pathLower, nsLower,
			"farm", "farms", "ферм", "ферма", "фермы", "agro", "mobfarm", "mob_farm", "afkfarm", "grinder")) {
			return Component.translatable("screen.chestmemory.dimension.farm").getString();
		}
		if (tokenMatch(pathLower, nsLower,
			"build", "builds", "building", "buildings", "постро", "постройки", "стройка",
			"creative", "creat", "plot", "plots", "freebuild", "строител")) {
			return Component.translatable("screen.chestmemory.dimension.build").getString();
		}
		if (tokenMatch(pathLower, nsLower,
			"hub", "lobby", "spawn", "хаб", "лобби", "spawnworld", "mainhub")) {
			return Component.translatable("screen.chestmemory.dimension.hub").getString();
		}
		if (tokenMatch(pathLower, nsLower,
			"resource", "resources", "resworld", "mining", "mines", "добыч", "шахт", "ресурс")) {
			return Component.translatable("screen.chestmemory.dimension.resource").getString();
		}

		// Unknown custom: title-case path
		String nice = path.replace('_', ' ').replace('-', ' ').replace('/', ' ');
		if (nice.isEmpty()) {
			return dimensionId;
		}
		String titled = Character.toUpperCase(nice.charAt(0)) + (nice.length() > 1 ? nice.substring(1) : "");
		if (!ns.isEmpty() && !"minecraft".equals(nsLower)) {
			return titled + " (" + ns + ")";
		}
		return titled;
	}

	/**
	 * Match keywords against path/namespace tokens only.
	 * Splits on {@code _ - / .} so {@code world_farm} matches {@code farm},
	 * but {@code minecraft} never matches {@code mine}.
	 */
	private static boolean tokenMatch(String pathLower, String nsLower, String... needles) {
		Set<String> tokens = tokenize(pathLower);
		// Do not tokenize "minecraft" namespace into "mine" — skip vanilla ns entirely
		if (!"minecraft".equals(nsLower) && !nsLower.isEmpty()) {
			tokens.addAll(tokenize(nsLower));
		}
		// Also allow matching the whole path as one token
		tokens.add(pathLower);

		for (String needle : needles) {
			String n = needle.toLowerCase(Locale.ROOT);
			for (String token : tokens) {
				if (token.equals(n)) {
					return true;
				}
				// Longer keywords may be a prefix/suffix of a token: farmworld, worldfarm
				if (n.length() >= 4 && (token.startsWith(n) || token.endsWith(n) || token.contains(n))) {
					return true;
				}
			}
		}
		return false;
	}

	private static final Pattern SPLIT = Pattern.compile("[^a-z0-9а-яё]+", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);

	private static Set<String> tokenize(String raw) {
		Set<String> out = new LinkedHashSet<>();
		if (raw == null || raw.isEmpty()) {
			return out;
		}
		for (String part : SPLIT.split(raw.toLowerCase(Locale.ROOT))) {
			if (!part.isEmpty()) {
				out.add(part);
			}
		}
		return out;
	}

	/** For header: pretty name, plus raw id when it's not vanilla. */
	public static String displayHere(@Nullable String dimensionId) {
		if (dimensionId == null || dimensionId.isBlank()) {
			return "?";
		}
		String pretty = prettyName(dimensionId);
		boolean vanilla = dimensionId.equals(Level.OVERWORLD.identifier().toString())
			|| dimensionId.equals(Level.NETHER.identifier().toString())
			|| dimensionId.equals(Level.END.identifier().toString());
		if (vanilla) {
			return pretty;
		}
		Identifier id = Identifier.tryParse(dimensionId);
		String shortId = id != null
			? (id.getNamespace().equals("minecraft") ? id.getPath() : id.toString())
			: dimensionId;
		// If pretty already reflects path, show pretty; else "Pretty · id"
		if (pretty.equalsIgnoreCase(shortId) || pretty.toLowerCase(Locale.ROOT).contains(
			id != null ? id.getPath().toLowerCase(Locale.ROOT) : shortId.toLowerCase(Locale.ROOT)
		)) {
			return pretty;
		}
		return pretty + " · " + shortId;
	}

	public boolean matches(ContainerRecord record, @Nullable String playerDimension) {
		boolean personal = record.isVirtual()
			&& record.virtualId() != null
			&& (record.virtualId().startsWith("inv_shulker") || "ender_chest".equals(record.virtualId()));

		return switch (kind) {
			case ALL -> true;
			case CURRENT -> {
				// Must match the dimension you stand in — not "all overworlds" / not all personal storage
				if (playerDimension == null || playerDimension.isBlank()) {
					yield false;
				}
				String recDim = record.dimension();
				if (recDim == null || recDim.isBlank()) {
					// Unknown dim: only personal inventory-shulkers (move with you)
					yield personal && record.virtualId() != null
						&& record.virtualId().startsWith("inv_shulker");
				}
				yield playerDimension.equals(recDim);
			}
			case SPECIFIC -> {
				if (dimensionId == null) {
					yield false;
				}
				// Personal storage: only if last scanned in that world (or inv shulker with matching dim)
				String recDim = record.dimension();
				if (recDim == null || recDim.isBlank()) {
					yield false;
				}
				yield dimensionId.equals(recDim);
			}
		};
	}

	public static List<DimensionChoice> buildChoices(
		Iterable<ContainerRecord> containers,
		@Nullable String playerDimension
	) {
		return buildChoices(containers, playerDimension, Set.of());
	}

	public static List<DimensionChoice> buildChoices(
		Iterable<ContainerRecord> containers,
		@Nullable String playerDimension,
		@Nullable Set<String> extraKnown
	) {
		Set<String> ids = new LinkedHashSet<>();
		if (playerDimension != null) {
			ids.add(playerDimension);
		}
		ids.add(Level.OVERWORLD.identifier().toString());
		ids.add(Level.NETHER.identifier().toString());
		ids.add(Level.END.identifier().toString());

		if (extraKnown != null) {
			ids.addAll(extraKnown);
		}
		for (ContainerRecord r : containers) {
			if (r.dimension() != null && !r.dimension().isBlank()) {
				ids.add(r.dimension());
			}
		}

		List<DimensionChoice> list = new ArrayList<>();
		list.add(ALL);
		list.add(CURRENT);

		List<String> sorted = new ArrayList<>(ids);
		sorted.sort(Comparator
			.comparingInt((String s) -> sortOrder(s))
			.thenComparing(s -> prettyName(s), String.CASE_INSENSITIVE_ORDER));

		for (String id : sorted) {
			list.add(of(id));
		}
		return list;
	}

	private static int sortOrder(String id) {
		if (id.equals(Level.OVERWORLD.identifier().toString())) {
			return 0;
		}
		if (MultiworldTracker.isFarmWorld(id)) {
			return 1;
		}
		if (MultiworldTracker.isBuildWorld(id)) {
			return 2;
		}
		if (id.equals(Level.NETHER.identifier().toString())) {
			return 3;
		}
		if (id.equals(Level.END.identifier().toString())) {
			return 4;
		}
		return 10;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof DimensionChoice other)) {
			return false;
		}
		if (kind != other.kind) {
			return false;
		}
		if (kind == Kind.SPECIFIC) {
			return dimensionId != null && dimensionId.equals(other.dimensionId);
		}
		return true;
	}

	@Override
	public int hashCode() {
		return kind.hashCode() * 31 + (dimensionId == null ? 0 : dimensionId.hashCode());
	}
}
