package org.beconscient.collector.util

/** Crockford base32, used by pairing codes and installation fingerprints. */
object Crockford {
    const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    /** Encodes [bytes] most significant bit first and returns the first [chars] characters. */
    fun encode(bytes: ByteArray, chars: Int): String {
        require(chars * 5 <= bytes.size * 8) { "not enough bits" }
        val out = StringBuilder(chars)
        var buffer = 0
        var bits = 0
        var index = 0
        while (out.length < chars) {
            if (bits < 5) {
                buffer = (buffer shl 8) or (bytes[index++].toInt() and 0xFF)
                bits += 8
            }
            val value = (buffer shr (bits - 5)) and 0x1F
            bits -= 5
            out.append(ALPHABET[value])
        }
        return out.toString()
    }

    /**
     * Normalizes what a person typed: uppercase, drop spaces and dashes, map the
     * look-alike letters O to 0 and I/L to 1. Returns null when a character is
     * outside the alphabet.
     */
    fun normalize(input: String): String? {
        val out = StringBuilder()
        for (c in input.uppercase()) {
            when (c) {
                ' ', '-', '\t' -> continue
                'O' -> out.append('0')
                'I', 'L' -> out.append('1')
                else -> if (c in ALPHABET) out.append(c) else return null
            }
        }
        return out.toString()
    }

    /** "ABCDEFGH" to "ABCD-EFGH". */
    fun group4(s: String): String = if (s.length == 8) "${s.substring(0, 4)}-${s.substring(4)}" else s
}
