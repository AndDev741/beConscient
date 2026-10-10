package org.beconscient.collector.util

import java.util.Base64

/** Unpadded base64url (RFC 4648 section 5), as the envelope contract requires. */
object Base64Url {
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun encode(bytes: ByteArray): String = encoder.encodeToString(bytes)

    /** Decodes unpadded or padded base64url. Throws [IllegalArgumentException] on bad input. */
    fun decode(text: String): ByteArray = decoder.decode(text.trimEnd('='))
}
