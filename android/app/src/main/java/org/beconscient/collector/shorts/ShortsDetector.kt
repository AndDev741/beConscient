package org.beconscient.collector.shorts

import org.beconscient.collector.events.Events.ShortsState

/**
 * State machine behind the Shorts markers. A screen classification must hold
 * for [stableMs] before the state changes, and the marker carries the instant
 * the new classification first appeared. Leaving YouTube or turning the screen
 * off closes an open `enter` at once.
 *
 * - two or more signals, stable: `enter`
 * - from Shorts to anything else, stable: `exit`
 * - a partial signal set or no known id, stable while YouTube is in front: `unclassified`
 */
class ShortsDetector(
    private val stableMs: Long = ShortsRules.STABLE_MS,
    private val emit: (ShortsState, Long) -> Unit,
) {
    enum class State { OUTSIDE, REGULAR, SHORTS, UNCLASSIFIED }

    var state: State = State.OUTSIDE
        private set

    private var candidate: State? = null
    private var candidateSince = 0L

    /** True while a classification is waiting to become stable. */
    val pending: Boolean get() = candidate != null

    /** [screen] is null when YouTube is in front but its window could not be read. */
    fun observe(now: Long, youtubeForeground: Boolean, screen: ShortsRules.Screen?) {
        if (!youtubeForeground) {
            leave(now)
            return
        }
        val target = when (screen) {
            ShortsRules.Screen.SHORTS -> State.SHORTS
            ShortsRules.Screen.REGULAR -> State.REGULAR
            ShortsRules.Screen.PARTIAL, ShortsRules.Screen.NONE, null -> State.UNCLASSIFIED
        }
        if (target == state) {
            candidate = null
            return
        }
        if (candidate != target) {
            candidate = target
            candidateSince = now
        }
        tick(now)
    }

    /** Re-evaluates the pending classification without a new observation. */
    fun tick(now: Long) {
        val next = candidate ?: return
        if (now - candidateSince < stableMs) return
        candidate = null
        if (state == State.SHORTS) emit(ShortsState.EXIT, candidateSince)
        when (next) {
            State.SHORTS -> emit(ShortsState.ENTER, candidateSince)
            State.UNCLASSIFIED -> emit(ShortsState.UNCLASSIFIED, candidateSince)
            else -> Unit
        }
        state = next
    }

    /** YouTube left the foreground, the screen turned off, or the service stopped. */
    fun leave(now: Long) {
        if (state == State.SHORTS) emit(ShortsState.EXIT, now)
        state = State.OUTSIDE
        candidate = null
    }
}
