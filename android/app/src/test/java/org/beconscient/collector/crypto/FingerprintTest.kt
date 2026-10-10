package org.beconscient.collector.crypto

import org.beconscient.collector.util.Crockford
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FingerprintTest {
    @Test
    fun `fingerprint matches an independent implementation of the contract formula`() {
        // Computed with Python hashlib from envelope.md:
        // first 8 Crockford chars of SHA-256("beconscient-fp" || installation_id || SHA-256(secret)).
        val fp = Fingerprint.of("0192f3a0-1c2b-7cde-8f00-112233445566", ByteArray(32) { it.toByte() })
        assertEquals("AWJC-42F0", fp)
    }

    @Test
    fun `installation id is used in canonical lowercase`() {
        val secret = ByteArray(32) { it.toByte() }
        assertEquals(
            Fingerprint.of("0192f3a0-1c2b-7cde-8f00-112233445566", secret),
            Fingerprint.of("0192F3A0-1C2B-7CDE-8F00-112233445566", secret),
        )
    }

    @Test
    fun `crockford encodes most significant bits first`() {
        assertEquals("ZZZZZZZZ", Crockford.encode(byteArrayOf(-1, -1, -1, -1, -1), 8))
        assertEquals("00000000", Crockford.encode(ByteArray(5), 8))
        assertEquals("28T5CY4T", Crockford.encode(byteArrayOf(0x12, 0x34, 0x56, 0x78, 0x9a.toByte()), 8))
    }

    @Test
    fun `typed codes are normalized`() {
        assertEquals("7KQ2M9XD", Crockford.normalize("7kq2-m9xd"))
        assertEquals("0110ABCD", Crockford.normalize("OIL0 abcd"))
        assertNull(Crockford.normalize("7KQ2-M9XU"))
    }
}
