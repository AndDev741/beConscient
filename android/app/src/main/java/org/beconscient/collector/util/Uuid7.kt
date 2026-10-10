package org.beconscient.collector.util

import java.security.SecureRandom

/**
 * UUIDv7 (RFC 9562): 48-bit Unix milliseconds, version 7, 12-bit counter seeded
 * randomly each millisecond, variant 10, 62 random bits. Ids made in the same
 * millisecond stay ordered because the counter grows.
 */
object Uuid7 {
    private val random = SecureRandom()
    private var lastMs = -1L
    private var counter = 0

    @Synchronized
    fun generate(nowMs: Long = System.currentTimeMillis()): String {
        var ms = nowMs
        if (ms <= lastMs) {
            ms = lastMs
            counter++
            if (counter > 0xFFF) {
                ms = lastMs + 1
                counter = random.nextInt(0x800)
            }
        } else {
            counter = random.nextInt(0x800)
        }
        lastMs = ms
        val tail = ByteArray(8).also { random.nextBytes(it) }
        val b = ByteArray(16)
        for (i in 0 until 6) b[i] = (ms ushr (8 * (5 - i))).toByte()
        b[6] = (0x70 or ((counter ushr 8) and 0x0F)).toByte()
        b[7] = (counter and 0xFF).toByte()
        System.arraycopy(tail, 0, b, 8, 8)
        b[8] = ((b[8].toInt() and 0x3F) or 0x80).toByte()
        return format(b)
    }

    /** Random (version 4) UUID, used for local notification refs. */
    fun random4(): String {
        val b = ByteArray(16).also { random.nextBytes(it) }
        b[6] = ((b[6].toInt() and 0x0F) or 0x40).toByte()
        b[8] = ((b[8].toInt() and 0x3F) or 0x80).toByte()
        return format(b)
    }

    private fun format(b: ByteArray): String {
        val hex = b.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-" +
            "${hex.substring(16, 20)}-${hex.substring(20)}"
    }
}
