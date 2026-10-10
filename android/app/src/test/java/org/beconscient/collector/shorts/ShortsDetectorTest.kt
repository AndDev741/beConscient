package org.beconscient.collector.shorts

import org.beconscient.collector.events.Events
import org.beconscient.collector.events.Events.ShortsState
import org.beconscient.collector.shorts.ShortsRules.Screen
import org.beconscient.collector.support.parse
import org.beconscient.collector.support.str
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShortsDetectorTest {
    private val markers = mutableListOf<Pair<ShortsState, Long>>()
    private val detector = ShortsDetector(stableMs = 1_500) { s, at -> markers += s to at }

    @Test
    fun `rules need two distinct signals for Shorts`() {
        assertEquals(Screen.SHORTS, ShortsRules.classify(setOf("reel_recycler", "reel_player_page_container")))
        assertEquals(Screen.PARTIAL, ShortsRules.classify(setOf("reel_recycler", "shorts_pager")))
        assertEquals(Screen.PARTIAL, ShortsRules.classify(setOf("reel_dyn_remix", "pivot_bar")))
        assertEquals(Screen.REGULAR, ShortsRules.classify(setOf("pivot_bar", "watch_player")))
        assertEquals(Screen.NONE, ShortsRules.classify(emptySet()))
        assertTrue(ShortsRules.allIds.all { ShortsRules.fullId(it).startsWith("com.google.android.youtube:id/") })
    }

    @Test
    fun `two signals stable for two seconds enter Shorts at the first sighting`() {
        detector.observe(0, true, Screen.REGULAR)
        detector.tick(1_600)
        detector.observe(10_000, true, Screen.SHORTS)
        detector.observe(11_000, true, Screen.SHORTS)
        assertTrue(markers.isEmpty())
        detector.observe(12_000, true, Screen.SHORTS)
        assertEquals(listOf(ShortsState.ENTER to 10_000L), markers)
        assertEquals(ShortsDetector.State.SHORTS, detector.state)
    }

    @Test
    fun `a single signal never enters Shorts and becomes unclassified past the window`() {
        detector.observe(0, true, Screen.REGULAR)
        detector.tick(2_000)
        detector.observe(5_000, true, Screen.PARTIAL)
        detector.tick(6_000)
        assertTrue(markers.isEmpty())
        detector.tick(6_600)
        assertEquals(listOf(ShortsState.UNCLASSIFIED to 5_000L), markers)
    }

    @Test
    fun `a flickering screen does not change state`() {
        detector.observe(0, true, Screen.REGULAR)
        detector.tick(2_000)
        var t = 3_000L
        repeat(10) {
            detector.observe(t, true, Screen.SHORTS)
            detector.observe(t + 1_000, true, Screen.REGULAR)
            t += 1_200
        }
        assertTrue(markers.isEmpty())
        assertEquals(ShortsDetector.State.REGULAR, detector.state)
    }

    @Test
    fun `leaving YouTube closes an open enter at once`() {
        detector.observe(0, true, Screen.SHORTS)
        detector.tick(2_000)
        detector.observe(30_000, false, null)
        assertEquals(listOf(ShortsState.ENTER to 0L, ShortsState.EXIT to 30_000L), markers)
        assertEquals(ShortsDetector.State.OUTSIDE, detector.state)
    }

    @Test
    fun `screen off closes an open enter`() {
        detector.observe(0, true, Screen.SHORTS)
        detector.tick(2_000)
        detector.leave(45_000)
        assertEquals(ShortsState.EXIT to 45_000L, markers.last())
    }

    @Test
    fun `back to the regular feed exits Shorts`() {
        detector.observe(0, true, Screen.SHORTS)
        detector.tick(2_000)
        detector.observe(20_000, true, Screen.REGULAR)
        detector.tick(21_600)
        assertEquals(listOf(ShortsState.ENTER to 0L, ShortsState.EXIT to 20_000L), markers)
    }

    @Test
    fun `signals degrading inside Shorts exit and report unclassified`() {
        detector.observe(0, true, Screen.SHORTS)
        detector.tick(2_000)
        detector.observe(20_000, true, Screen.PARTIAL)
        detector.tick(22_000)
        assertEquals(
            listOf(ShortsState.ENTER to 0L, ShortsState.EXIT to 20_000L, ShortsState.UNCLASSIFIED to 20_000L),
            markers,
        )
    }

    @Test
    fun `AE4 a YouTube with no known id is unclassified and the marker carries its version`() {
        detector.observe(0, true, Screen.NONE)
        detector.tick(1_500)
        assertEquals(listOf(ShortsState.UNCLASSIFIED to 0L), markers)
        val row = Events.shorts(10, markers[0].second, markers[0].first, "20.01.35", 1551000000, ShortsRules.VERSION)
        val obj = parse(row.payload)
        assertEquals("unclassified", obj.str("state"))
        assertEquals("20.01.35", obj.str("youtube_version"))
        assertEquals("1551000000", obj.str("youtube_version_code"))
        assertEquals(ShortsRules.VERSION.toString(), obj.str("rules_version"))
    }

    @Test
    fun `an unreadable YouTube window counts as unclassified only if it lasts`() {
        detector.observe(0, true, Screen.REGULAR)
        detector.tick(2_000)
        detector.observe(3_000, true, null)
        detector.observe(3_400, true, Screen.REGULAR)
        detector.tick(10_000)
        assertTrue(markers.isEmpty())
    }
}
