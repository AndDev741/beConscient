package org.beconscient.crypto;

import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.crypto.InvalidCipherTextException;
import org.bouncycastle.crypto.hpke.HPKE;
import org.bouncycastle.crypto.hpke.HPKEContext;
import org.bouncycastle.crypto.hpke.HPKEContextWithEncapsulation;
import org.bouncycastle.crypto.modes.ChaCha20Poly1305;
import org.bouncycastle.crypto.params.AEADParameters;
import org.bouncycastle.crypto.params.AsymmetricKeyParameter;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters;

import java.security.SecureRandom;
import java.util.Arrays;

/**
 * The envelope of contract/envelope.md: HPKE base mode with
 * DHKEM(X25519, HKDF-SHA256), HKDF-SHA256 and ChaCha20-Poly1305, aad = kid,
 * info = purpose. Responses use a key exported from the same context.
 *
 * Only BouncyCastle's lightweight API is used. No JCA provider is registered,
 * which keeps the native image free of provider reflection.
 */
public final class Hpke {

    public static final String RESPONSE_LABEL = "beconscient/v1/response";
    public static final int RESPONSE_KEY_BYTES = 32;
    public static final int NONCE_BYTES = 12;

    /** One purpose per endpoint; the info string binds an envelope to it. */
    public enum Purpose {
        PAIR("beconscient/v1/pair"),
        BATCH("beconscient/v1/batch");

        public final String info;

        Purpose(String info) {
            this.info = info;
        }
    }

    /** Thrown for any envelope that does not open. Carries no detail on purpose. */
    public static final class OpenFailed extends Exception {
        public OpenFailed(Throwable cause) {
            super("envelope did not open", cause);
        }
    }

    public record Opened(byte[] plaintext, byte[] responseKey) {
    }

    public record Sealed(byte[] enc, byte[] ct, byte[] responseKey) {
    }

    public record Box(byte[] nonce, byte[] ct) {
    }

    private Hpke() {
    }

    static HPKE suite() {
        return new HPKE(HPKE.mode_base, HPKE.kem_X25519_SHA256, HPKE.kdf_HKDF_SHA256, HPKE.aead_CHACHA20_POLY1305);
    }

    /** The X25519 public key of a raw 32-byte private key. */
    public static byte[] publicKeyOf(byte[] privateKey) {
        if (privateKey == null || privateKey.length != 32) {
            throw new IllegalArgumentException("an X25519 private key is 32 bytes");
        }
        return new X25519PrivateKeyParameters(privateKey, 0).generatePublicKey().getEncoded();
    }

    static AsymmetricCipherKeyPair keyPair(byte[] privateKey) {
        return suite().deserializePrivateKey(privateKey, publicKeyOf(privateKey));
    }

    /** Recipient side: opens the single-shot ciphertext and exports the response key. */
    public static Opened open(byte[] privateKey, String kid, Purpose purpose, byte[] enc, byte[] ct) throws OpenFailed {
        try {
            if (enc == null || enc.length != 32 || ct == null || ct.length < 16) {
                throw new IllegalArgumentException("malformed envelope");
            }
            HPKE hpke = suite();
            HPKEContext ctx = hpke.setupBaseR(enc, keyPair(privateKey), Bytes.utf8(purpose.info));
            byte[] pt = ctx.open(Bytes.utf8(kid), ct);
            byte[] key = ctx.export(Bytes.utf8(RESPONSE_LABEL), RESPONSE_KEY_BYTES);
            return new Opened(pt, key);
        } catch (InvalidCipherTextException | RuntimeException e) {
            throw new OpenFailed(e);
        }
    }

    /** Sender side, as a collector does it. Used by tests and the vector generator. */
    public static Sealed seal(byte[] publicKey, String kid, Purpose purpose, byte[] plaintext) {
        try {
            HPKE hpke = suite();
            AsymmetricKeyParameter pk = hpke.deserializePublicKey(publicKey);
            HPKEContextWithEncapsulation ctx = hpke.setupBaseS(pk, Bytes.utf8(purpose.info));
            byte[] ct = ctx.seal(Bytes.utf8(kid), plaintext);
            byte[] key = ctx.export(Bytes.utf8(RESPONSE_LABEL), RESPONSE_KEY_BYTES);
            return new Sealed(ctx.getEncapsulation(), ct, key);
        } catch (InvalidCipherTextException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Encrypts a response body under the exported key with a fresh random nonce. */
    public static Box sealResponse(byte[] key, byte[] plaintext) {
        byte[] nonce = new byte[NONCE_BYTES];
        // A fresh generator per call: a static one would be frozen into the native image heap
        new SecureRandom().nextBytes(nonce);
        return new Box(nonce, aead(true, key, nonce, plaintext));
    }

    public static byte[] openResponse(byte[] key, byte[] nonce, byte[] ct) throws OpenFailed {
        try {
            return aead(false, key, nonce, ct);
        } catch (IllegalStateException e) {
            throw new OpenFailed(e);
        }
    }

    private static byte[] aead(boolean encrypt, byte[] key, byte[] nonce, byte[] input) {
        ChaCha20Poly1305 cipher = new ChaCha20Poly1305();
        cipher.init(encrypt, new AEADParameters(new KeyParameter(key), 128, nonce, Bytes.utf8(RESPONSE_LABEL)));
        byte[] out = new byte[cipher.getOutputSize(input.length)];
        int n = cipher.processBytes(input, 0, input.length, out, 0);
        try {
            n += cipher.doFinal(out, n);
        } catch (InvalidCipherTextException e) {
            throw new IllegalStateException("response does not open", e);
        }
        return n == out.length ? out : Arrays.copyOf(out, n);
    }
}
