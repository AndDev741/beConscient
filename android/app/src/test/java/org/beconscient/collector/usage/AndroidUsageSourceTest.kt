package org.beconscient.collector.usage

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import org.beconscient.collector.support.context
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowUsageStatsManager

@RunWith(RobolectricTestRunner::class)
class AndroidUsageSourceTest {
    @Test
    fun `reads only the contract kinds from UsageStatsManager`() {
        val usm = context().getSystemService(UsageStatsManager::class.java)
        val shadow = shadowOf(usm)
        fun add(type: Int, t: Long, pkg: String) = shadow.addEvent(
            ShadowUsageStatsManager.EventBuilder.buildEvent()
                .setEventType(type).setTimeStamp(t).setPackage(pkg).setClass("$pkg.A").build(),
        )
        add(UsageEvents.Event.ACTIVITY_RESUMED, 1000, "com.a")
        add(UsageEvents.Event.USER_INTERACTION, 1500, "com.a")
        add(UsageEvents.Event.ACTIVITY_STOPPED, 2000, "com.a")
        add(UsageEvents.Event.SCREEN_NON_INTERACTIVE, 3000, "android")

        val events = AndroidUsageSource(context()).query(0, 10_000)
        assertEquals(
            listOf(UsageEvents.Event.ACTIVITY_RESUMED, UsageEvents.Event.ACTIVITY_STOPPED, UsageEvents.Event.SCREEN_NON_INTERACTIVE),
            events.map { it.eventType },
        )
        assertEquals("com.a.A", events[0].className)
    }
}
