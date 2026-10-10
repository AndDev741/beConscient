package org.beconscient.device;

import org.beconscient.crypto.Bytes;
import org.beconscient.crypto.Crockford;

import java.util.UUID;

/**
 * The short installation fingerprint both the page and the collector display:
 * the first 8 Crockford base32 symbols of
 * SHA-256("beconscient-fp" || installation_id || SHA-256(secret)), where
 * installation_id is its canonical lowercase string (UTF-8) and SHA-256(secret)
 * is the 32-byte digest of the decoded 32-byte secret.
 */
public final class Fingerprint {

    private Fingerprint() {
    }

    public static String of(UUID installationId, byte[] secretHash) {
        byte[] digest = Bytes.sha256(
                Bytes.utf8("beconscient-fp"),
                Bytes.utf8(installationId.toString().toLowerCase()),
                secretHash);
        return Crockford.group(Crockford.encode(digest, 8));
    }
}
