package org.beconscient.crypto;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/** Small byte helpers shared by the envelope, pairing and credential checks. */
public final class Bytes {

    private static final Base64.Encoder B64URL = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64URL_DEC = Base64.getUrlDecoder();

    private Bytes() {
    }

    /** Unpadded base64url, RFC 4648 §5, as the contract uses everywhere. */
    public static String b64url(byte[] data) {
        return B64URL.encodeToString(data);
    }

    /**
     * Decodes unpadded base64url. Padding and the standard alphabet are refused,
     * so one value has exactly one spelling.
     */
    public static byte[] fromB64url(String text) {
        if (text == null || text.indexOf('=') >= 0 || text.indexOf('+') >= 0 || text.indexOf('/') >= 0) {
            throw new IllegalArgumentException("not unpadded base64url");
        }
        return B64URL_DEC.decode(text);
    }

    public static byte[] sha256(byte[]... parts) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (byte[] p : parts) {
                md.update(p);
            }
            return md.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /** Constant time for equal lengths; MessageDigest.isEqual does not short-circuit. */
    public static boolean constantTimeEquals(byte[] a, byte[] b) {
        return MessageDigest.isEqual(a, b);
    }
}
