package org.beconscient.collector.crypto

import org.bouncycastle.crypto.InvalidCipherTextException
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter

/**
 * The encrypted response of every 200 from the ingest endpoints: ChaCha20-Poly1305 with the
 * exported key, a random 12-byte nonce and AAD "beconscient/v1/response".
 */
object ResponseCipher {
    private val AAD = Hpke.RESPONSE_LABEL.toByteArray(Charsets.UTF_8)
    const val NONCE_LENGTH = 12
    private const val TAG_BITS = 128

    /** Returns the plaintext, or throws [InvalidCipherTextException] when the tag does not verify. */
    fun open(key: ByteArray, nonce: ByteArray, ct: ByteArray): ByteArray {
        if (nonce.size != NONCE_LENGTH) throw InvalidCipherTextException("bad nonce length")
        return run(false, key, nonce, ct)
    }

    /** Server side, used by tests and the vector writer. */
    fun seal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray): ByteArray = run(true, key, nonce, plaintext)

    private fun run(encrypt: Boolean, key: ByteArray, nonce: ByteArray, input: ByteArray): ByteArray {
        val aead = ChaCha20Poly1305()
        aead.init(encrypt, AEADParameters(KeyParameter(key), TAG_BITS, nonce, AAD))
        val out = ByteArray(aead.getOutputSize(input.size))
        val n = aead.processBytes(input, 0, input.size, out, 0)
        val total = n + aead.doFinal(out, n)
        return if (total == out.size) out else out.copyOf(total)
    }
}
