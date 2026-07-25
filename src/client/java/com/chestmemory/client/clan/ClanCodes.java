package com.chestmemory.client.clan;

import java.security.SecureRandom;
import java.util.Locale;

/**
 * Short join codes: {@code CM-K7M2} (easy to type / paste in chat).
 */
public final class ClanCodes {
	private static final char[] ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
	private static final SecureRandom RNG = new SecureRandom();

	private ClanCodes() {
	}

	public static String generate() {
		StringBuilder sb = new StringBuilder(7);
		sb.append("CM-");
		for (int i = 0; i < 4; i++) {
			sb.append(ALPHABET[RNG.nextInt(ALPHABET.length)]);
		}
		return sb.toString();
	}

	/** Normalize user input: trim, upper, ensure CM- prefix optional. */
	public static String normalize(String raw) {
		if (raw == null) {
			return "";
		}
		String s = raw.trim().toUpperCase(Locale.ROOT).replace(' ', '-');
		// strip common prefixes from chat paste
		if (s.startsWith("CHESTMEMORY") || s.startsWith("СБОР")) {
			int idx = s.lastIndexOf("CM-");
			if (idx >= 0) {
				s = s.substring(idx);
			}
		}
		// Allow bare 4-char codes
		if (s.matches("[A-Z0-9]{4}")) {
			s = "CM-" + s;
		}
		// Keep only CM-XXXX shape characters
		s = s.replaceAll("[^A-Z0-9-]", "");
		if (s.startsWith("CM") && !s.startsWith("CM-") && s.length() >= 6) {
			s = "CM-" + s.substring(2);
		}
		return s;
	}

	public static boolean isValid(String code) {
		String n = normalize(code);
		return n.matches("CM-[A-Z0-9]{4}");
	}
}
