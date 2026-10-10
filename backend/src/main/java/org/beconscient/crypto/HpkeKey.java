package org.beconscient.crypto;

import java.time.Instant;
import java.util.Arrays;

/**
 * One server key: its id, the raw X25519 private key and, optionally, the
 * instant after which envelopes sealed to it are refused (key rotation).
 */
public record HpkeKey(String kid, byte[] privateKey, byte[] publicKey, boolean testOnly, Instant retireAt) {

    /** The kid of contract/keys/test-keypair.json. Production refuses it. */
    public static final String TEST_KID = "test-0001";
    /** The public half of the test key pair, to recognise it under any kid. */
    public static final String TEST_PUBLIC_KEY = "y8OzVaM2hnilfWsxNBWGHCufC6d3x4qoX1CXPhGhy3A";

    public static HpkeKey of(String kid, byte[] privateKey, boolean testOnly, Instant retireAt) {
        return new HpkeKey(kid, privateKey.clone(), Hpke.publicKeyOf(privateKey), testOnly, retireAt);
    }

    /** True for anything that is, or is marked as, the public test key. */
    public boolean isTestKey() {
        return testOnly || TEST_KID.equals(kid) || TEST_PUBLIC_KEY.equals(Bytes.b64url(publicKey));
    }

    public boolean acceptsAt(Instant now) {
        return retireAt == null || now.isBefore(retireAt);
    }

    @Override
    public String toString() {
        // Never print the private key
        return "HpkeKey[" + kid + (testOnly ? ", test-only" : "") + "]";
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof HpkeKey other && kid.equals(other.kid) && Arrays.equals(publicKey, other.publicKey);
    }

    @Override
    public int hashCode() {
        return kid.hashCode();
    }
}
