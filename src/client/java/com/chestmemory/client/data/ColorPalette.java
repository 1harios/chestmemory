package com.chestmemory.client.data;

import net.minecraft.network.chat.Component;

/**
 * Named RGB presets for highlight / HUD / slot colors (0xRRGGBB, no alpha).
 */
public final class ColorPalette {
	public record Swatch(int rgb, String nameKey) {
		public Component label() {
			return Component.translatable(nameKey);
		}
	}

	/** Shared palette — click cycles through these. */
	public static final Swatch[] ALL = {
		new Swatch(0x40E8FF, "color.chestmemory.cyan"),
		new Swatch(0x7CFF7A, "color.chestmemory.green"),
		new Swatch(0x55A6FF, "color.chestmemory.blue"),
		new Swatch(0xFFD56A, "color.chestmemory.gold"),
		new Swatch(0xFF9F43, "color.chestmemory.orange"),
		new Swatch(0xFF6B6B, "color.chestmemory.red"),
		new Swatch(0xC090FF, "color.chestmemory.purple"),
		new Swatch(0xFF69B4, "color.chestmemory.pink"),
		new Swatch(0x00FFC8, "color.chestmemory.mint"),
		new Swatch(0xE8E8E8, "color.chestmemory.white"),
		new Swatch(0xA0A0A0, "color.chestmemory.gray"),
		// Defaults that were not in the palette before, so the settings row fell back to
		// showing a raw hex code (#28DC50) next to rows that showed a proper name.
		new Swatch(0x28DC50, "color.chestmemory.emerald"),
		new Swatch(0x80FFA0, "color.chestmemory.lime"),
		new Swatch(0xC8A040, "color.chestmemory.bronze"),
		new Swatch(0xE8C060, "color.chestmemory.amber"),
	};

	// Defaults matching previous hardcoded look
	public static final int DEFAULT_GLOW = 0x40E8FF;
	public static final int DEFAULT_NEAREST = 0x7CFF7A;
	public static final int DEFAULT_SLOT = 0x28DC50;
	public static final int DEFAULT_ROUTE = 0x80FFA0;
	public static final int DEFAULT_HUD_ACCENT = 0xC8A040;
	public static final int DEFAULT_HUD_TITLE = 0xE8C060;
	/** Build-site warehouse chest outline (purple). */
	public static final int DEFAULT_WAREHOUSE = 0xC090FF;

	private ColorPalette() {
	}

	public static int normalizeRgb(int rgb) {
		return rgb & 0xFFFFFF;
	}

	public static int r(int rgb) {
		return (normalizeRgb(rgb) >> 16) & 0xFF;
	}

	public static int g(int rgb) {
		return (normalizeRgb(rgb) >> 8) & 0xFF;
	}

	public static int b(int rgb) {
		return normalizeRgb(rgb) & 0xFF;
	}

	/** ARGB from 0xRRGGBB + alpha 0–255. */
	public static int argb(int alpha, int rgb) {
		return ((alpha & 0xFF) << 24) | normalizeRgb(rgb);
	}

	/** Soft fill derived from outline color. */
	public static int softFillRgb(int outlineRgb) {
		int rr = Math.max(0, (int) (r(outlineRgb) * 0.55));
		int gg = Math.max(0, (int) (g(outlineRgb) * 0.55));
		int bb = Math.max(0, (int) (b(outlineRgb) * 0.55));
		return (rr << 16) | (gg << 8) | bb;
	}

	/** Brighter border for slot edges. */
	public static int brighten(int rgb, float factor) {
		int rr = Math.min(255, (int) (r(rgb) * factor));
		int gg = Math.min(255, (int) (g(rgb) * factor));
		int bb = Math.min(255, (int) (b(rgb) * factor));
		return (rr << 16) | (gg << 8) | bb;
	}

	public static int indexOf(int rgb) {
		int n = normalizeRgb(rgb);
		for (int i = 0; i < ALL.length; i++) {
			if (normalizeRgb(ALL[i].rgb) == n) {
				return i;
			}
		}
		return -1;
	}

	public static int cycle(int currentRgb) {
		int i = indexOf(currentRgb);
		if (i < 0) {
			return ALL[0].rgb;
		}
		return ALL[(i + 1) % ALL.length].rgb;
	}

	public static Component nameOf(int rgb) {
		int i = indexOf(rgb);
		if (i >= 0) {
			return ALL[i].label();
		}
		// Custom / legacy value — show hex
		return Component.literal(String.format("#%06X", normalizeRgb(rgb)));
	}
}
