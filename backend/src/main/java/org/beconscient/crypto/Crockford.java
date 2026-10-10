package org.beconscient.crypto;

import java.security.SecureRandom;

/**
 * Crockford base32 for pairing codes and fingerprints: 32 symbols without I, L,
 * O and U, encoded most significant bit first.
 */
public final class Crockford {

    public static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
    private Crockford() {
    }

    /** The first {@code chars} symbols of {@code data}, read as a big-endian bit string. */
    public static String encode(byte[] data, int chars) {
        if (chars * 5 > data.length * 8) {
            throw new IllegalArgumentException("not enough bits");
        }
        StringBuilder out = new StringBuilder(chars);
        for (int i = 0; i < chars; i++) {
            int bit = i * 5;
            int value = 0;
            for (int b = 0; b < 5; b++) {
                int pos = bit + b;
                int set = (data[pos / 8] >> (7 - pos % 8)) & 1;
                value = (value << 1) | set;
            }
            out.append(ALPHABET.charAt(value));
        }
        return out.toString();
    }

    /** "ABCDEFGH" shown as "ABCD-EFGH". */
    public static String group(String eight) {
        return eight.substring(0, 4) + "-" + eight.substring(4);
    }

    /** A fresh 8-symbol code (40 random bits), grouped as XXXX-XXXX. */
    public static String newPairingCode() {
        byte[] bits = new byte[5];
        // A fresh generator per call: a static one would be frozen into the native image heap
        new SecureRandom().nextBytes(bits);
        return group(encode(bits, 8));
    }

    /**
     * What a person typed, in the canonical form XXXX-XXXX the collectors also
     * use: whitespace and hyphens removed, upper case, the look-alikes O, I and L
     * folded to 0, 1 and 1, and one hyphen after the fourth symbol. Returns null
     * when it cannot be a code.
     */
    public static String normalizeCode(String typed) {
        if (typed == null) {
            return null;
        }
        StringBuilder out = new StringBuilder(8);
        for (char c : typed.toUpperCase(java.util.Locale.ROOT).toCharArray()) {
            if (c == '-' || Character.isWhitespace(c)) {
                continue;
            }
            char mapped = switch (c) {
                case 'O' -> '0';
                case 'I', 'L' -> '1';
                default -> c;
            };
            if (ALPHABET.indexOf(mapped) < 0) {
                return null;
            }
            out.append(mapped);
        }
        return out.length() == 8 ? group(out.toString()) : null;
    }
}
