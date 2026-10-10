package org.beconscient.collector.support

import org.beconscient.collector.util.Uuid7
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Uuid7Test {
    private val shape = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")

    @Test
    fun `ids are version 7, carry the millisecond and sort in creation order`() {
        // The generator is process-wide and never goes back in time, so use a base later than any other test.
        val base = System.currentTimeMillis() + 10L * 365 * 24 * 60 * 60 * 1000
        val ids = (0 until 5000).map { Uuid7.generate(base + it / 1000) }
        ids.forEach { assertTrue(it, shape.matches(it)) }
        assertEquals(ids, ids.sorted())
        assertEquals(ids.size, ids.toSet().size)
        val ms = ids.first().replace("-", "").substring(0, 12).toLong(16)
        assertEquals(base, ms)
    }

    @Test
    fun `random refs are version 4`() {
        val ref = Uuid7.random4()
        assertTrue(Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$").matches(ref))
    }
}
