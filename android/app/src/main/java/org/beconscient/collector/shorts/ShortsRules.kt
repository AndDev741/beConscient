package org.beconscient.collector.shorts

/**
 * The versioned Shorts rule set. Bump [VERSION] whenever an id list or a
 * threshold changes: the server shows, per YouTube version, whether any rule
 * version has produced an `enter` there yet.
 *
 * The ids are candidates taken from public layout dumps and must be confirmed
 * on the real device with `adb shell uiautomator dump` on the installed YouTube.
 * Only resource ids are ever read; never text or content descriptions.
 */
object ShortsRules {
    const val VERSION = 1
    const val YOUTUBE_PACKAGE = "com.google.android.youtube"

    /** A screen must keep the same classification this long before the state changes. */
    const val STABLE_MS = 1_500L

    /** How many distinct Shorts signals make a Shorts screen. */
    const val REQUIRED_SIGNALS = 2

    /** Each signal is present when any of its ids is on screen and visible. */
    val shortsSignals: Map<String, List<String>> = linkedMapOf(
        "reel_pager" to listOf("reel_recycler", "reel_watch_pager", "shorts_pager"),
        "reel_page" to listOf("reel_player_page_container", "reel_watch_player", "shorts_player_container"),
        "reel_overlay" to listOf(
            "reel_player_overlay_container",
            "reel_dyn_remix",
            "reel_time_bar",
            "reel_like_button",
            "shorts_like_button",
        ),
    )

    /** Ids that show a known, non-Shorts YouTube screen (feed, watch page, search). */
    val regularIds: List<String> = listOf(
        "pivot_bar",
        "watch_player",
        "watch_while_layout",
        "results",
        "watch_list",
    )

    val allIds: List<String> = shortsSignals.values.flatten() + regularIds

    fun fullId(id: String) = "$YOUTUBE_PACKAGE:id/$id"

    enum class Screen { SHORTS, REGULAR, PARTIAL, NONE }

    /** Classifies a screen from the short ids (without package prefix) found on it. */
    fun classify(present: Set<String>): Screen {
        val signals = shortsSignals.count { (_, ids) -> ids.any { it in present } }
        return when {
            signals >= REQUIRED_SIGNALS -> Screen.SHORTS
            signals > 0 -> Screen.PARTIAL
            regularIds.any { it in present } -> Screen.REGULAR
            else -> Screen.NONE
        }
    }
}
