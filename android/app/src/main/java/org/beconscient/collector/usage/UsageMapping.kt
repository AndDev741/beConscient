package org.beconscient.collector.usage

import android.app.usage.UsageEvents

/** Maps UsageStatsManager event types to the `kind` values in contract/events.md. */
object UsageMapping {
    fun kindOf(eventType: Int): String? = when (eventType) {
        UsageEvents.Event.ACTIVITY_RESUMED -> "ACTIVITY_RESUMED"
        UsageEvents.Event.ACTIVITY_PAUSED -> "ACTIVITY_PAUSED"
        UsageEvents.Event.ACTIVITY_STOPPED -> "ACTIVITY_STOPPED"
        UsageEvents.Event.SCREEN_INTERACTIVE -> "SCREEN_INTERACTIVE"
        UsageEvents.Event.SCREEN_NON_INTERACTIVE -> "SCREEN_NON_INTERACTIVE"
        UsageEvents.Event.KEYGUARD_SHOWN -> "KEYGUARD_SHOWN"
        UsageEvents.Event.KEYGUARD_HIDDEN -> "KEYGUARD_HIDDEN"
        UsageEvents.Event.DEVICE_SHUTDOWN -> "DEVICE_SHUTDOWN"
        UsageEvents.Event.DEVICE_STARTUP -> "DEVICE_STARTUP"
        else -> null
    }
}
