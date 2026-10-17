package org.beconscient.collector

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.beconscient.collector.events.TzMarkers
import org.beconscient.collector.health.HealthReporter
import org.beconscient.collector.launcher.LauncherStore
import org.beconscient.collector.notifications.NotificationCollector
import org.beconscient.collector.pairing.CredentialStore
import org.beconscient.collector.pairing.PairingClient
import org.beconscient.collector.pairing.PrefsCredentialStore
import org.beconscient.collector.queue.EventDb
import org.beconscient.collector.setup.AndroidSensorProbe
import org.beconscient.collector.setup.SensorProbe
import org.beconscient.collector.upload.HttpTransport
import org.beconscient.collector.upload.QueueMaintenance
import org.beconscient.collector.upload.ServerConfig
import org.beconscient.collector.upload.Uploader
import org.beconscient.collector.upload.UrlConnectionTransport
import org.beconscient.collector.usage.AndroidUsageSource
import org.beconscient.collector.usage.UsageReader
import org.beconscient.collector.util.Clock
import org.beconscient.collector.util.SerialQueue

/** Hand-made dependency graph, one per process. */
class AppGraph(
    context: Context,
    val clock: Clock = Clock.System,
    val db: EventDb = EventDb.open(context),
    val config: ServerConfig = ServerConfig.fromBuild(),
    val credentials: CredentialStore = PrefsCredentialStore.android(context),
    val transport: HttpTransport = UrlConnectionTransport(allowCleartext = BuildConfig.DEBUG),
    val probe: SensorProbe = AndroidSensorProbe(context),
) {
    val app: Context = context.applicationContext
    val clientVersion: String = BuildConfig.VERSION_NAME
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val notificationQueue = SerialQueue(scope, "notifications")
    val shortsQueue = SerialQueue(scope, "shorts")

    val tzMarkers = TzMarkers(db.dao(), clock)
    val usageReader = UsageReader(db.dao(), AndroidUsageSource(app), clock)
    val notificationCollector = NotificationCollector(db, clock)
    val uploader = Uploader(db.dao(), credentials, transport, config, clock, clientVersion)
    val maintenance = QueueMaintenance(db.dao(), clock)
    val health = HealthReporter(db.dao(), probe, clock, clientVersion)
    val pairing = PairingClient(config, transport, credentials, db.dao(), tzMarkers, clock, clientVersion)
    val launcher = LauncherStore.android(app)

    /** Runs [block] off the main thread while keeping a receiver's broadcast alive. */
    fun runAsync(receiver: BroadcastReceiver, block: suspend () -> Unit) {
        // Null only when onReceive is called directly, outside a real broadcast.
        val pending: BroadcastReceiver.PendingResult? = receiver.goAsync()
        scope.launch {
            try {
                block()
            } finally {
                pending?.finish()
            }
        }
    }

    companion object {
        // Holds only the application context, which lives as long as the process.
        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: AppGraph? = null

        fun get(context: Context): AppGraph =
            instance ?: synchronized(this) {
                instance ?: AppGraph(context.applicationContext).also { instance = it }
            }

        /** Tests install their own graph. */
        fun install(graph: AppGraph?) {
            synchronized(this) { instance = graph }
        }
    }
}
