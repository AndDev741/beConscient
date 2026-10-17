package org.beconscient.collector.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.DayOfWeek.FRIDAY
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.SATURDAY
import java.time.DayOfWeek.SUNDAY
import java.time.LocalDateTime
import java.time.LocalTime

class LauncherSettingsTest {
    // 2026-10-12 is a Monday
    private fun at(day: Int, time: String) = LocalDateTime.of(2026, 10, day, 0, 0).with(LocalTime.parse(time))

    private val family = Block("f", "Family", setOf(MONDAY, FRIDAY), LocalTime.of(19, 0), LocalTime.of(20, 0), "Family time")
    private val bed = Block("b", "Before bed", DayOfWeek.entries.toSet(), LocalTime.of(21, 0), LocalTime.of(1, 0), "Kindle", "com.amazon.kindle")

    @Test
    fun `a block covers its start and stops at its end`() {
        assertTrue(family.inForceAt(at(12, "19:00")))
        assertTrue(family.inForceAt(at(12, "19:59")))
        assertFalse(family.inForceAt(at(12, "20:00")))
        assertFalse(family.inForceAt(at(12, "18:59")))
    }

    @Test
    fun `a block holds only on its days`() {
        assertFalse("Tuesday", family.inForceAt(at(13, "19:30")))
        assertTrue("Friday", family.inForceAt(at(16, "19:30")))
    }

    @Test
    fun `a block past midnight belongs to the day it started`() {
        val fridayNight = bed.copy(days = setOf(FRIDAY))
        assertTrue(fridayNight.inForceAt(at(16, "23:30")))
        assertTrue("Saturday 00:30 is still Friday's block", fridayNight.inForceAt(at(17, "00:30")))
        assertFalse(fridayNight.inForceAt(at(17, "01:00")))
        assertFalse("Saturday night has no block", fridayNight.inForceAt(at(17, "23:30")))
        assertFalse("Friday 00:30 belongs to Thursday", fridayNight.inForceAt(at(16, "00:30")))
    }

    @Test
    fun `the first block in the owner's order wins`() {
        val overlap = Block("o", "Overlap", setOf(MONDAY), LocalTime.of(19, 30), LocalTime.of(21, 30), "Other")
        val s = LauncherSettings(blocks = listOf(family, overlap))
        assertEquals("f", s.blockAt(at(12, "19:45"))!!.id)
        assertEquals("o", s.blockAt(at(12, "20:15"))!!.id)
        assertNull(s.blockAt(at(12, "22:00")))
    }

    @Test
    fun `settings survive a round trip`() {
        val s = LauncherSettings(
            pinned = listOf("com.whatsapp", "org.mozilla.firefox"),
            controlled = setOf("com.google.android.youtube", "com.whatsapp"),
            blocks = listOf(family, bed.copy(days = setOf(SATURDAY, SUNDAY))),
        )
        assertEquals(s, LauncherSettings.decode(s.encode()))
    }

    @Test
    fun `unreadable settings fall back to empty instead of crashing home`() {
        assertEquals(LauncherSettings(), LauncherSettings.decode(null))
        assertEquals(LauncherSettings(), LauncherSettings.decode("not json"))
        val partial = """{"pinned":["a","a",3],"blocks":[{"id":"x","start":"nope","end":"10:00"},{"id":"y","name":"Ok","days":[1,9],"start":"09:00","end":"10:00"}]}"""
        val s = LauncherSettings.decode(partial)
        assertEquals(listOf("a", "3"), s.pinned)
        assertEquals(listOf("y"), s.blocks.map { it.id })
        assertEquals(setOf(MONDAY), s.blocks.single().days)
    }
}
