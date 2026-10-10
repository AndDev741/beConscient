package org.beconscient.collector

import android.app.Application
import org.beconscient.collector.upload.Scheduling

class CollectorApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Scheduling.ensurePeriodic(this)
    }
}
