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
 * True RGB survives as {@code §#RRGGBB}, an extension of the legacy syntax. It exists
 * because servers colour names with per-character gradients, and snapping every character to
 * one of sixteen colours flattened them — adjacent characters landed on the same code, so
 * the gradient came out as a solid block. Two decoders read it back: {@link #toComponent}
 * wherever a {@link Component} can be rendered, which is where the gradient actually shows,
 * and {@link #downgrade} for the many call sites that still pass a plain string around,
 * since the vanilla font renderer understands the sixteen codes and nothing else.
 * <p>
 * A colour that IS one of the sixteen exactly still encodes as the plain old code, so the
 * storage keys of ordinary renamed items are unchanged by this.
 */
public final class LegacyText {
	private static final char PREFIX = '§';

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
		b.append(colourCode(style.getColor()));
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

	/**
	 * Colour part of a style: the plain legacy code when the colour is exactly one of the
	 * sixteen, otherwise {@code §#RRGGBB}.
	 * <p>
	 * Preferring the old code for exact matches is what keeps storage keys stable — a
	 * "§6Меч босса" scanned before this change and after it must still be the same item.
	 */
	private static String colourCode(@Nullable TextColor color) {
		return color == null ? "" : LegacyColors.code(color.getValue());
	}

	/**
	 * Parse a legacy string — including {@code §#RRGGBB} — back into a styled component.
	 * <p>
	 * This is the only decoder that can show a gradient: a string can carry the colours but
	 * only a component can be handed to the renderer with real RGB. A colour code resets the
	 * modifiers before it, matching legacy behaviour, so "§lbold §cred" leaves red unbolded
	 * exactly as it would in chat.
	 */
	public static Component toComponent(@Nullable String legacy) {
		if (legacy == null || legacy.isEmpty()) {
			return Component.empty();
		}
		if (legacy.indexOf(PREFIX) < 0) {
			return Component.literal(legacy);
		}
		net.minecraft.network.chat.MutableComponent out = Component.empty();
		StringBuilder run = new StringBuilder();
		Style style = Style.EMPTY;
		int i = 0;
		while (i < legacy.length()) {
			char c = legacy.charAt(i);
			if (c != PREFIX || i + 1 >= legacy.length()) {
				run.append(c);
				i++;
				continue;
			}
			char code = legacy.charAt(i + 1);
			Style next = null;
			int skip = 2;
			int marker = LegacyColors.markerAt(legacy, i);
			if (marker >= 0) {
				// A colour clears the modifiers, as a legacy colour code does.
				next = Style.EMPTY.withColor(TextColor.fromRgb(marker));
				skip = LegacyColors.MARKER_LEN;
			} else if (code == '#') {
				// A literal "§#" somebody typed into an anvil, or a truncated marker.
				run.append(c);
				i++;
				continue;
			} else {
				next = styleFor(code, style);
				if (next == null) {
					// Unknown code: keep it as text rather than swallowing part of the name.
					run.append(c);
					i++;
					continue;
				}
			}
			if (run.length() > 0) {
				out.append(Component.literal(run.toString()).withStyle(style));
				run.setLength(0);
			}
			style = next;
			i += skip;
		}
		if (run.length() > 0) {
			out.append(Component.literal(run.toString()).withStyle(style));
		}
		return out;
	}

	/** Style after applying one legacy code to the current one, or null when unrecognised. */
	private static @Nullable Style styleFor(char code, Style current) {
		Style colour = colourStyle(code);
		if (colour != null) {
			return colour;
		}
		return switch (Character.toLowerCase(code)) {
			case 'k' -> current.withObfuscated(true);
			case 'l' -> current.withBold(true);
			case 'm' -> current.withStrikethrough(true);
			case 'n' -> current.withUnderlined(true);
			case 'o' -> current.withItalic(true);
			case 'r' -> Style.EMPTY;
			default -> null;
		};
	}

	/**
	 * Rewrite {@code §#RRGGBB} as the nearest of the sixteen legacy codes.
	 * <p>
	 * For the call sites that pass names around as plain strings — chat lines, the CSV
	 * export, status text, search. The font renderer would print the RGB marker literally,
	 * so those places get the approximation they had before; only the places that can render
	 * a component get the real gradient.
	 */
	public static String downgrade(@Nullable String legacy) {
		return LegacyColors.downgrade(legacy);
	}

	/** The name with all styling removed, marker form included — for search and CSV. */
	public static String strip(@Nullable String legacy) {
		return LegacyColors.strip(legacy);
	}

	/** Style after applying one legacy colour code, or null when it is not a colour. */
	private static @Nullable Style colourStyle(char code) {
		int rgb = LegacyColors.rgbOfCode(code);
		return rgb < 0 ? null : Style.EMPTY.withColor(TextColor.fromRgb(rgb));
	}
}
