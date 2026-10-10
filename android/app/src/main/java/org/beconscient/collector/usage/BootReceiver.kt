package org.beconscient.collector.usage

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.beconscient.collector.AppGraph
import org.beconscient.collector.events.Events
import org.beconscient.collector.upload.Scheduling

/** After boot or an app update: write a BOOT marker, make sure work is scheduled and read usage now. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val boot = intent.action == Intent.ACTION_BOOT_COMPLETED
        if (!boot && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val graph = AppGraph.get(context)
        graph.runAsync(this) {
            if (boot) graph.tzMarkers.record(Events.TzReason.BOOT)
            Scheduling.ensurePeriodic(context)
            Scheduling.collectNow(context)
        }
    }
}
