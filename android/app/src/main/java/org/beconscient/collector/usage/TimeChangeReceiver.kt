package org.beconscient.collector.usage

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.beconscient.collector.AppGraph
import org.beconscient.collector.events.Events

/**
 * ACTION_TIMEZONE_CHANGED and ACTION_TIME_CHANGED are exempt from the implicit
 * broadcast limits, so a manifest receiver gets them. Not exported: only the
 * system sends them.
 */
class TimeChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val reason = when (intent.action) {
            Intent.ACTION_TIMEZONE_CHANGED -> Events.TzReason.TIMEZONE_CHANGED
            Intent.ACTION_TIME_CHANGED -> Events.TzReason.TIME_SET
            else -> return
        }
        val zone = intent.getStringExtra(EXTRA_TIME_ZONE)
        val graph = AppGraph.get(context)
        graph.runAsync(this) { graph.tzMarkers.record(reason, zone) }
    }

    companion object {
        /** Intent.EXTRA_TIMEZONE is hidden in the SDK; its value is stable. */
        const val EXTRA_TIME_ZONE = "time-zone"
    }
}
