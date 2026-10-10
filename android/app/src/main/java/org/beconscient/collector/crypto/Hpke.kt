package org.beconscient.collector.crypto

import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.crypto.hpke.HPKE

/**
 * HPKE (RFC 9180) base mode with the suite fixed by contract/envelope.md:
 * DHKEM(X25519, HKDF-SHA256), HKDF-SHA256, ChaCha20-Poly1305.
 */
object Hpke {
    const val INFO_PAIR = "beconscient/v1/pair"
    const val INFO_BATCH = "beconscient/v1/batch"
    const val RESPONSE_LABEL = "beconscient/v1/response"
    const val RESPONSE_KEY_LENGTH = 32

    private fun suite() = HPKE(
        HPKE.mode_base,
        HPKE.kem_X25519_SHA256,
        HPKE.kdf_HKDF_SHA256,
        HPKE.aead_CHACHA20_POLY1305,
    )

    /** What a sender keeps after sealing: the wire parts and the key that opens the response. */
    class Sealed(val enc: ByteArray, val ct: ByteArray, val responseKey: ByteArray)

    /**
     * Seals [plaintext] as sequence number 0 of a fresh sender context bound to
     * [info], with the UTF-8 [kid] as AAD, and exports the response key.
     */
    fun seal(recipientPublicKey: ByteArray, kid: String, info: String, plaintext: ByteArray): Sealed {
        val hpke = suite()
        val pk = hpke.deserializePublicKey(recipientPublicKey)
        val ctx = hpke.setupBaseS(pk, info.toByteArray(Charsets.UTF_8))
        val ct = ctx.seal(kid.toByteArray(Charsets.UTF_8), plaintext)
        val key = ctx.export(RESPONSE_LABEL.toByteArray(Charsets.UTF_8), RESPONSE_KEY_LENGTH)
        return Sealed(ctx.encapsulation, ct, key)
    }

    /** What a recipient gets after opening: the plaintext and the response key. */
    class Opened(val plaintext: ByteArray, val responseKey: ByteArray)

    /**
     * Recipient side. The app never needs it; tests use it to play the server and
     * to open the vectors other implementations wrote.
     */
    fun open(privateKey: ByteArray, publicKey: ByteArray, kid: String, info: String, enc: ByteArray, ct: ByteArray): Opened {
        val hpke = suite()
        val pair: AsymmetricCipherKeyPair = hpke.deserializePrivateKey(privateKey, publicKey)
        val ctx = hpke.setupBaseR(enc, pair, info.toByteArray(Charsets.UTF_8))
        val pt = ctx.open(kid.toByteArray(Charsets.UTF_8), ct)
        val key = ctx.export(RESPONSE_LABEL.toByteArray(Charsets.UTF_8), RESPONSE_KEY_LENGTH)
        return Opened(pt, key)
    }
}
