package com.chestmemory.client.util;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import org.jspecify.annotations.Nullable;

import java.util.Optional;

/**
 * Flattens a styled {@link Component} into a legacy §-coded string.
 * <p>
 * Renamed items on servers often carry colours — via anvil plugins, shop systems or
 * {@code /give} with JSON names. The mod stores item identities as compact strings, so
 * without this the colours were stripped at scan time and a "§6Меч босса" listed as plain
 * "Меч босса". Legacy codes survive inside strings and the font renderer still honours
 * them everywhere (tooltips, chat, lists), which makes them the right interchange format.
 * <p>
 * RGB colours are mapped to the nearest of the 16 legacy colours — close enough for
 * display, and the raw name still comes from the real item when it is looked at directly.
 */
public final class LegacyText {
	private static final char PREFIX = '§';

	/** The classic 16 colours, index = legacy code value (0–15). */
	private static final int[] LEGACY_RGB = {
		0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA,
		0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF
	};
	private static final char[] LEGACY_CODE = {
		'0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'
	};

	private LegacyText() {
	}

	/**
	 * Component text with styles encoded as legacy codes. Returns the plain string when
	 * the component carries no styling, so unstyled names keep their exact old form (and
	 * the storage keys built from them stay stable).
	 */
	public static String toLegacy(@Nullable Component component) {
		if (component == null) {
			return "";
		}
		String plain = component.getString();
		StringBuilder sb = new StringBuilder(plain.length() + 8);
		String[] last = {""};
		component.visit((style, text) -> {
			String codes = codesFor(style);
			if (!codes.equals(last[0])) {
				if (codes.isEmpty()) {
					// Styled run ended — reset, or the previous colour bleeds on.
					sb.append(PREFIX).append('r');
				} else {
					sb.append(codes);
				}
				last[0] = codes;
			}
			sb.append(text);
			return Optional.empty();
		}, Style.EMPTY);
		String out = sb.toString();
		return out.equals(plain) ? plain : out;
	}

	/** Legacy code sequence for a style: colour first (it resets modifiers), then modifiers. */
	private static String codesFor(Style style) {
		if (style == null || style.isEmpty()) {
			return "";
		}
		StringBuilder b = new StringBuilder(6);
		char colour = nearestColourCode(style.getColor());
		if (colour != 0) {
			b.append(PREFIX).append(colour);
		}
		if (style.isObfuscated()) {
			b.append(PREFIX).append('k');
		}
		if (style.isBold()) {
			b.append(PREFIX).append('l');
		}
		if (style.isStrikethrough()) {
			b.append(PREFIX).append('m');
		}
		if (style.isUnderlined()) {
			b.append(PREFIX).append('n');
		}
		if (style.isItalic()) {
			b.append(PREFIX).append('o');
		}
		return b.toString();
	}

	/** Nearest legacy colour code for an RGB text colour, or 0 when there is none. */
	private static char nearestColourCode(@Nullable TextColor color) {
		if (color == null) {
			return 0;
		}
		int rgb = color.getValue() & 0xFFFFFF;
		int best = 0;
		long bestDist = Long.MAX_VALUE;
		for (int i = 0; i < LEGACY_RGB.length; i++) {
			long dist = colourDistance(rgb, LEGACY_RGB[i]);
			if (dist < bestDist) {
				bestDist = dist;
				best = i;
			}
		}
		return LEGACY_CODE[best];
	}

	private static long colourDistance(int a, int b) {
		long dr = ((a >> 16) & 0xFF) - ((b >> 16) & 0xFF);
		long dg = ((a >> 8) & 0xFF) - ((b >> 8) & 0xFF);
		long db = (a & 0xFF) - (b & 0xFF);
		return dr * dr + dg * dg + db * db;
	}
}
