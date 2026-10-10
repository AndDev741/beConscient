package org.beconscient.collector.crypto

import org.beconscient.collector.util.Crockford
import java.security.MessageDigest

/**
 * Installation fingerprint: the first 8 Crockford base32 characters of
 * SHA-256("beconscient-fp" || installation_id || SHA-256(secret)), shown as XXXX-XXXX.
 */
object Fingerprint {
    fun of(installationId: String, secret: ByteArray): String {
        val inner = sha256(secret)
        val outer = MessageDigest.getInstance("SHA-256").run {
            update("beconscient-fp".toByteArray(Charsets.UTF_8))
            update(installationId.lowercase().toByteArray(Charsets.UTF_8))
            update(inner)
            digest()
        }
        return Crockford.group4(Crockford.encode(outer, 8))
    }

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)
}
