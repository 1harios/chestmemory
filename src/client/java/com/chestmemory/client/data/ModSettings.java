package com.chestmemory.client.data;

import com.chestmemory.ChestMemoryMod;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Persistent client settings — panel filters + highlight / display / gather options.
 */
public final class ModSettings {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static volatile ModSettings instance;

	private int nearbyRange = 64;
	private String scope = ListScope.NEARBY.name();
	/** Comma-separated ContainerFilter names, or ALL. E.g. CHEST,BARREL,HOPPER */
	private String typeFilter = "ALL";
	private String sortMode = SortMode.DISTANCE.name();
	/** CURRENT / ALL / or specific dimension id */
	private String dimensionChoice = "CURRENT";
	private String lastSearch = "";

	// --- Highlight ---
	private int highlightSeconds = 20;
	private boolean showDistanceLabels = true;
	private boolean distanceOnAllChests = true;
	private boolean highlightSlots = true;
	private int highlightRenderRange = 96;
	/** Item icon billboard above glowing chests. */
	private boolean showChestItemIcons = true;
	/**
	 * Glow strength: 0 = soft, 1 = normal, 2 = bright.
	 */
	private int glowIntensity = 1;

	// --- Colors (0xRRGGBB) ---
	private int glowColor = ColorPalette.DEFAULT_GLOW;
	private int nearestColor = ColorPalette.DEFAULT_NEAREST;
	private int slotColor = ColorPalette.DEFAULT_SLOT;
	private int routeFocusColor = ColorPalette.DEFAULT_ROUTE;
	private int hudAccentColor = ColorPalette.DEFAULT_HUD_ACCENT;
	private int hudTitleColor = ColorPalette.DEFAULT_HUD_TITLE;
	/** Outline for build-site warehouse (staging) chests. */
	private int warehouseColor = ColorPalette.DEFAULT_WAREHOUSE;
	/**
	 * Master switch: purple glow on marked warehouse chests.
	 * Works for solo gather and clan; turn off in settings if not needed.
	 */
	private boolean showWarehouseGlow = true;

	// --- Gather / scheme display ---
	private boolean showGatherHud = true;
	/** HUD corner: 0 = top-left, 1 = top-right, 2 = bottom-left, 3 = bottom-right. */
	private int gatherHudCorner = 0;
	/** Chat announcements when advancing gather queue. */
	private boolean gatherChatMessages = true;
	/** Auto-advance to next item when inventory has enough. */
	private boolean gatherAutoAdvance = true;

	// --- Panel ---
	/** Remember filter expand state. */
	private boolean filtersExpanded = false;

	// --- Clan hub ---
	/** Base URL of clan gather hub, e.g. https://clan.example.com */
	private String clanHubUrl = "";
	/** Optional shared clan token (header X-Clan-Token). */
	private String clanToken = "";

	// --- Deferred save (transient: never serialized by Gson) ---
	/** Pending unsaved changes. */
	private transient volatile boolean dirty;
	/** Ticks to wait before flushing (batches bursts of changes). */
	private transient volatile int saveDelayTicks;

	private ModSettings() {
	}

	public static ModSettings get() {
		ModSettings local = instance;
		if (local == null) {
			synchronized (ModSettings.class) {
				local = instance;
				if (local == null) {
					local = load();
					instance = local;
				}
			}
		}
		return local;
	}

	public int nearbyRange() {
		return nearbyRange;
	}

	public NearbyRange nearbyRangeEnum() {
		return NearbyRange.fromBlocks(nearbyRange);
	}

	public void setNearbyRange(NearbyRange range) {
		this.nearbyRange = range.blocks();
		save();
	}

	public void cycleNearbyRange() {
		NearbyRange cur = nearbyRangeEnum();
		NearbyRange[] all = NearbyRange.values();
		int i = 0;
		for (int k = 0; k < all.length; k++) {
			if (all[k] == cur) {
				i = k;
				break;
			}
		}
		setNearbyRange(all[(i + 1) % all.length]);
	}

	public ListScope listScope() {
		try {
			return ListScope.valueOf(scope);
		} catch (Exception e) {
			return ListScope.NEARBY;
		}
	}

	public void setListScope(ListScope s) {
		this.scope = s.name();
		save();
	}

	public void toggleListScope() {
		setListScope(listScope() == ListScope.NEARBY ? ListScope.WORLD_TOTAL : ListScope.NEARBY);
	}

	/** @deprecated use {@link #typeFilters()} multi-select */
	@Deprecated
	public ContainerFilter typeFilter() {
		var set = typeFilters();
		if (set.contains(ContainerFilter.ALL) || set.size() != 1) {
			return ContainerFilter.ALL;
		}
		return set.iterator().next();
	}

	public java.util.EnumSet<ContainerFilter> typeFilters() {
		return ContainerFilter.parse(typeFilter);
	}

	public void setTypeFilter(ContainerFilter f) {
		if (f == null || f == ContainerFilter.ALL) {
			this.typeFilter = "ALL";
		} else {
			this.typeFilter = f.name();
		}
		save();
	}

	public void setTypeFilters(java.util.Collection<ContainerFilter> filters) {
		this.typeFilter = ContainerFilter.serialize(filters);
		save();
	}

	public SortMode sortMode() {
		return SortMode.fromId(sortMode);
	}

	public void setSortMode(SortMode m) {
		this.sortMode = m.name();
		save();
	}

	public void cycleSortMode() {
		SortMode[] all = SortMode.values();
		SortMode cur = sortMode();
		int i = 0;
		for (int k = 0; k < all.length; k++) {
			if (all[k] == cur) {
				i = k;
				break;
			}
		}
		setSortMode(all[(i + 1) % all.length]);
	}

	public String dimensionChoiceKey() {
		return dimensionChoice == null ? "CURRENT" : dimensionChoice;
	}

	public void setDimensionChoiceKey(String key) {
		this.dimensionChoice = key == null ? "CURRENT" : key;
		save();
	}

	public DimensionChoice resolveDimensionChoice() {
		String key = dimensionChoiceKey();
		if ("ALL".equalsIgnoreCase(key)) {
			return DimensionChoice.ALL;
		}
		if ("CURRENT".equalsIgnoreCase(key)) {
			return DimensionChoice.CURRENT;
		}
		return DimensionChoice.of(key);
	}

	public void setDimensionChoice(DimensionChoice choice) {
		if (choice.kind() == DimensionChoice.Kind.ALL) {
			setDimensionChoiceKey("ALL");
		} else if (choice.kind() == DimensionChoice.Kind.CURRENT) {
			setDimensionChoiceKey("CURRENT");
		} else {
			setDimensionChoiceKey(choice.dimensionId());
		}
	}

	public String lastSearch() {
		return lastSearch == null ? "" : lastSearch;
	}

	public void setLastSearch(String s) {
		this.lastSearch = s == null ? "" : s;
		save();
	}

	// --- Highlight ---

	public int highlightSeconds() {
		return clamp(highlightSeconds, 5, 120, 20);
	}

	public long highlightDurationMs() {
		return highlightSeconds() * 1000L;
	}

	public void setHighlightSeconds(int seconds) {
		this.highlightSeconds = clamp(seconds, 5, 120, 20);
		save();
	}

	public void cycleHighlightSeconds() {
		int s = highlightSeconds();
		if (s < 15) {
			setHighlightSeconds(20);
		} else if (s < 25) {
			setHighlightSeconds(30);
		} else if (s < 45) {
			setHighlightSeconds(60);
		} else if (s < 90) {
			setHighlightSeconds(90);
		} else {
			setHighlightSeconds(10);
		}
	}

	public boolean showDistanceLabels() {
		return showDistanceLabels;
	}

	public void setShowDistanceLabels(boolean v) {
		this.showDistanceLabels = v;
		save();
	}

	public void toggleShowDistanceLabels() {
		setShowDistanceLabels(!showDistanceLabels);
	}

	public boolean distanceOnAllChests() {
		return distanceOnAllChests;
	}

	public void setDistanceOnAllChests(boolean v) {
		this.distanceOnAllChests = v;
		save();
	}

	public void toggleDistanceOnAllChests() {
		setDistanceOnAllChests(!distanceOnAllChests);
	}

	public boolean highlightSlots() {
		return highlightSlots;
	}

	public void setHighlightSlots(boolean v) {
		this.highlightSlots = v;
		save();
	}

	public void toggleHighlightSlots() {
		setHighlightSlots(!highlightSlots);
	}

	public int highlightRenderRange() {
		return clamp(highlightRenderRange, 32, 256, 96);
	}

	public void setHighlightRenderRange(int blocks) {
		this.highlightRenderRange = clamp(blocks, 32, 256, 96);
		save();
	}

	public void cycleHighlightRenderRange() {
		int r = highlightRenderRange();
		if (r < 56) {
			setHighlightRenderRange(64);
		} else if (r < 80) {
			setHighlightRenderRange(96);
		} else if (r < 112) {
			setHighlightRenderRange(128);
		} else if (r < 144) {
			setHighlightRenderRange(160);
		} else if (r < 200) {
			setHighlightRenderRange(256);
		} else {
			setHighlightRenderRange(48);
		}
	}

	public boolean showChestItemIcons() {
		return showChestItemIcons;
	}

	public void setShowChestItemIcons(boolean v) {
		this.showChestItemIcons = v;
		save();
	}

	public void toggleShowChestItemIcons() {
		setShowChestItemIcons(!showChestItemIcons);
	}

	/** 0 soft · 1 normal · 2 bright */
	public int glowIntensity() {
		return clamp(glowIntensity, 0, 2, 1);
	}

	public void setGlowIntensity(int v) {
		this.glowIntensity = clamp(v, 0, 2, 1);
		save();
	}

	public void cycleGlowIntensity() {
		setGlowIntensity((glowIntensity() + 1) % 3);
	}

	// --- Colors ---

	public int glowColor() {
		return ColorPalette.normalizeRgb(glowColor);
	}

	public void setGlowColor(int rgb) {
		this.glowColor = ColorPalette.normalizeRgb(rgb);
		save();
	}

	public void cycleGlowColor() {
		setGlowColor(ColorPalette.cycle(glowColor()));
	}

	public int nearestColor() {
		return ColorPalette.normalizeRgb(nearestColor);
	}

	public void setNearestColor(int rgb) {
		this.nearestColor = ColorPalette.normalizeRgb(rgb);
		save();
	}

	public void cycleNearestColor() {
		setNearestColor(ColorPalette.cycle(nearestColor()));
	}

	public int slotColor() {
		return ColorPalette.normalizeRgb(slotColor);
	}

	public void setSlotColor(int rgb) {
		this.slotColor = ColorPalette.normalizeRgb(rgb);
		save();
	}

	public void cycleSlotColor() {
		setSlotColor(ColorPalette.cycle(slotColor()));
	}

	public int routeFocusColor() {
		return ColorPalette.normalizeRgb(routeFocusColor);
	}

	public void setRouteFocusColor(int rgb) {
		this.routeFocusColor = ColorPalette.normalizeRgb(rgb);
		save();
	}

	public void cycleRouteFocusColor() {
		setRouteFocusColor(ColorPalette.cycle(routeFocusColor()));
	}

	public int hudAccentColor() {
		return ColorPalette.normalizeRgb(hudAccentColor);
	}

	public void setHudAccentColor(int rgb) {
		this.hudAccentColor = ColorPalette.normalizeRgb(rgb);
		save();
	}

	public void cycleHudAccentColor() {
		setHudAccentColor(ColorPalette.cycle(hudAccentColor()));
	}

	public int hudTitleColor() {
		return ColorPalette.normalizeRgb(hudTitleColor);
	}

	public void setHudTitleColor(int rgb) {
		this.hudTitleColor = ColorPalette.normalizeRgb(rgb);
		save();
	}

	public void cycleHudTitleColor() {
		setHudTitleColor(ColorPalette.cycle(hudTitleColor()));
	}

	public int warehouseColor() {
		return ColorPalette.normalizeRgb(warehouseColor);
	}

	public void setWarehouseColor(int rgb) {
		this.warehouseColor = ColorPalette.normalizeRgb(rgb);
		save();
	}

	public void cycleWarehouseColor() {
		setWarehouseColor(ColorPalette.cycle(warehouseColor()));
	}

	public boolean showWarehouseGlow() {
		return showWarehouseGlow;
	}

	public void setShowWarehouseGlow(boolean v) {
		this.showWarehouseGlow = v;
		save();
	}

	public void toggleShowWarehouseGlow() {
		setShowWarehouseGlow(!showWarehouseGlow);
	}

	/** Soft fill derived from outline glow color. */
	public int glowFillColor() {
		return ColorPalette.softFillRgb(glowColor());
	}

	public void resetColors() {
		this.glowColor = ColorPalette.DEFAULT_GLOW;
		this.nearestColor = ColorPalette.DEFAULT_NEAREST;
		this.slotColor = ColorPalette.DEFAULT_SLOT;
		this.routeFocusColor = ColorPalette.DEFAULT_ROUTE;
		this.hudAccentColor = ColorPalette.DEFAULT_HUD_ACCENT;
		this.hudTitleColor = ColorPalette.DEFAULT_HUD_TITLE;
		this.warehouseColor = ColorPalette.DEFAULT_WAREHOUSE;
		this.glowIntensity = 1;
		save();
	}

	// --- Gather ---

	/** 0 = top-left, 1 = top-right, 2 = bottom-left, 3 = bottom-right. */
	public int gatherHudCorner() {
		return Math.max(0, Math.min(3, gatherHudCorner));
	}

	public void cycleGatherHudCorner() {
		this.gatherHudCorner = (gatherHudCorner() + 1) % 4;
		save();
	}

	public boolean showGatherHud() {
		return showGatherHud;
	}

	public void setShowGatherHud(boolean v) {
		this.showGatherHud = v;
		save();
	}

	public void toggleShowGatherHud() {
		setShowGatherHud(!showGatherHud);
	}

	public boolean gatherChatMessages() {
		return gatherChatMessages;
	}

	public void setGatherChatMessages(boolean v) {
		this.gatherChatMessages = v;
		save();
	}

	public void toggleGatherChatMessages() {
		setGatherChatMessages(!gatherChatMessages);
	}

	public boolean gatherAutoAdvance() {
		return gatherAutoAdvance;
	}

	public void setGatherAutoAdvance(boolean v) {
		this.gatherAutoAdvance = v;
		save();
	}

	public void toggleGatherAutoAdvance() {
		setGatherAutoAdvance(!gatherAutoAdvance);
	}

	public boolean filtersExpanded() {
		return filtersExpanded;
	}

	public void setFiltersExpanded(boolean v) {
		this.filtersExpanded = v;
		save();
	}

	// --- Clan ---

	public String clanHubUrl() {
		return clanHubUrl == null ? "" : clanHubUrl.trim();
	}

	public void setClanHubUrl(String url) {
		this.clanHubUrl = url == null ? "" : url.trim();
		save();
	}

	public String clanToken() {
		return clanToken == null ? "" : clanToken.trim();
	}

	public void setClanToken(String token) {
		this.clanToken = token == null ? "" : token.trim();
		save();
	}

	private static int clamp(int v, int min, int max, int def) {
		if (v < min || v > max) {
			return def;
		}
		return v;
	}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve(ChestMemoryMod.MOD_ID).resolve("settings.json");
	}

	private static ModSettings load() {
		Path path = file();
		if (!Files.isRegularFile(path)) {
			return new ModSettings();
		}
		try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
			ModSettings loaded = GSON.fromJson(reader, ModSettings.class);
			return loaded != null ? loaded : new ModSettings();
		} catch (Exception e) {
			ChestMemoryMod.LOGGER.warn("Failed to load settings, using defaults", e);
			return new ModSettings();
		}
	}

	/**
	 * Mark settings changed. The actual disk write happens one client tick later
	 * (see {@link #tick()}), so a burst of changes produces a single write.
	 */
	public void save() {
		this.saveDelayTicks = 1;
		this.dirty = true;
	}

	/** Call once per client tick — flushes pending changes after the 1-tick delay. */
	public static void tick() {
		ModSettings s = instance;
		if (s == null || !s.dirty) {
			return;
		}
		if (s.saveDelayTicks > 0) {
			s.saveDelayTicks--;
			return;
		}
		s.flushNow();
	}

	/** Flush pending changes if settings were ever loaded (shutdown hook). */
	public static void flushPending() {
		ModSettings s = instance;
		if (s != null) {
			s.flushNow();
		}
	}

	/** Write to disk immediately if there are unsaved changes (also used on shutdown). */
	public synchronized void flushNow() {
		if (!dirty) {
			return;
		}
		Path path = file();
		Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
		try {
			Files.createDirectories(path.getParent());
			// Write to a temp file first: a crash mid-write must not truncate settings.json,
			// because load() silently falls back to defaults on a parse error.
			try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
				GSON.toJson(this, writer);
			}
			try {
				Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException e) {
				Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
			}
			// Only clear the flag once the bytes are actually on disk, otherwise a failed
			// write silently discards the user's changes with no retry.
			dirty = false;
		} catch (Exception e) {
			ChestMemoryMod.LOGGER.error("Failed to save settings", e);
			try {
				Files.deleteIfExists(tmp);
			} catch (IOException ignored) {
				// best effort
			}
		}
	}
}
