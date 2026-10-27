package org.beconscient.collector.shorts

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.beconscient.collector.AppGraph
import org.beconscient.collector.events.Events
import org.beconscient.collector.focus.FocusMachine
import org.beconscient.collector.focus.FocusPhase
import org.beconscient.collector.queue.Kv

/**
 * Watches only com.google.android.youtube (see res/xml/shorts_accessibility.xml)
 * and reads only view resource ids, never text. It feeds [ShortsDetector] and
 * writes `android.shorts` markers. During a focus period it also receives
 * window changes from every app, reads only the package of the window in
 * front and sends a blocked app back to the home screen. Exported only to
 * holders of BIND_ACCESSIBILITY_SERVICE, that is, the system.
 */
class ShortsAccessibilityService : AccessibilityService() {

    private val graph by lazy { AppGraph.get(this) }
    private val handler = Handler(Looper.getMainLooper())
    private var youtubeVersion: String? = null
    private var youtubeVersionCode: Long? = null
    private var lastScanAt = 0L
    private var scanAt: Long? = null

    private val detector = ShortsDetector { state, at -> write(state, at) }

    private val scanRunnable = Runnable { scan() }

    private var scope: CoroutineScope? = null
    private var watchingAll = false
    private var youtubeOnlyTypes = 0

    /** Notices a locking block starting or a break ending while no window changes. */
    private val focusTick = object : Runnable {
        override fun run() {
            graph.focus.refresh()
            handler.postDelayed(this, FOCUS_TICK_MS)
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    cancelScan()
                    detector.leave(graph.clock.now())
                }
                Intent.ACTION_USER_PRESENT -> graph.focus.refresh()
            }
        }
    }

    override fun onServiceConnected() {
        running = true
        youtubeOnlyTypes = serviceInfo?.eventTypes ?: 0
        ContextCompat.registerReceiver(
            this,
            screenReceiver,
            IntentFilter(Intent.ACTION_SCREEN_OFF).apply { addAction(Intent.ACTION_USER_PRESENT) },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { s ->
            s.launch { graph.focus.phase.collect { applyFocus(it) } }
        }
        handler.post(focusTick)
    }

    /** Widens the service to every app while the phone is locked, and narrows it back to YouTube after. */
    private fun applyFocus(phase: FocusPhase) {
        val all = FocusMachine.locking(phase)
        val info = serviceInfo
        if (all != watchingAll && info != null) {
            watchingAll = all
            info.packageNames = if (all) null else arrayOf(ShortsRules.YOUTUBE_PACKAGE)
            info.eventTypes = if (all) AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED else youtubeOnlyTypes
            serviceInfo = info
        }
        if (all) frontPackage()?.let { if (graph.focus.blocks(it)) sendHome(it) }
    }

    private fun frontPackage(): String? = try {
        rootInActiveWindow?.packageName?.toString()
    } catch (e: RuntimeException) {
        null
    }

    private fun sendHome(pkg: String) {
        graph.focus.onBlocked(pkg)
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString()
        if (watchingAll && event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && graph.focus.blocks(pkg)) {
            // A dialog or a keyboard of another app can report a change: block only the window in front
            val front = frontPackage()
            if (front == null || front == pkg) {
                sendHome(pkg!!)
                return
            }
        }
        if (pkg != ShortsRules.YOUTUBE_PACKAGE) return
        val now = graph.clock.now()
        val wait = maxOf(DEBOUNCE_MS, lastScanAt + MIN_SCAN_INTERVAL_MS - now)
        // A scan that is already due soon is kept: a playing video sends events
        // continuously and must not postpone the scan forever.
        val due = scanAt
        if (due != null && due <= now + wait) return
        scheduleScan(now, wait)
    }

    private fun scheduleScan(now: Long, delayMs: Long) {
        handler.removeCallbacks(scanRunnable)
        scanAt = now + delayMs
        handler.postDelayed(scanRunnable, delayMs)
    }

    private fun cancelScan() {
        handler.removeCallbacks(scanRunnable)
        scanAt = null
    }

    private fun scan() {
        val now = graph.clock.now()
        lastScanAt = now
        scanAt = null
        val root: AccessibilityNodeInfo? = try {
            rootInActiveWindow
        } catch (e: RuntimeException) {
            null
        }
        val foreground = root?.packageName?.toString() == ShortsRules.YOUTUBE_PACKAGE
        if (foreground && detector.state == ShortsDetector.State.OUTSIDE) refreshVersion()
        val screen = if (foreground) ShortsRules.classify(presentIds(root)) else null
        detector.observe(now, foreground, screen)
        if (detector.state != ShortsDetector.State.OUTSIDE || detector.pending) {
            // Keep looking while YouTube is in front: stability windows elapse and leaving is noticed.
            scheduleScan(now, if (detector.pending) PENDING_TICK_MS else IDLE_TICK_MS)
        }
    }

    /** Looks up each candidate id. Reads ids and visibility only, never text. */
    private fun presentIds(root: AccessibilityNodeInfo): Set<String> {
        val found = HashSet<String>()
        for (id in ShortsRules.allIds) {
            val nodes = try {
                root.findAccessibilityNodeInfosByViewId(ShortsRules.fullId(id))
            } catch (e: RuntimeException) {
                emptyList()
            }
            if (nodes.any { it.isVisibleToUser }) found += id
        }
        return found
    }

    private fun refreshVersion() {
        try {
            val info = packageManager.getPackageInfo(ShortsRules.YOUTUBE_PACKAGE, 0)
            youtubeVersion = info.versionName
            youtubeVersionCode = info.longVersionCode
        } catch (e: Exception) {
            youtubeVersion = null
            youtubeVersionCode = null
        }
    }

    private fun write(state: Events.ShortsState, at: Long) {
        val version = youtubeVersion
        val code = youtubeVersionCode
        graph.shortsQueue.submit {
            val now = graph.clock.now()
            val row = Events.shorts(now, at, state, version, code, ShortsRules.VERSION)
            graph.db.dao().insertWithStamp(row, Kv.SHORTS_LAST_EVENT_AT, now)
        }
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        stop()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        stop()
        super.onDestroy()
    }

    private fun stop() {
        if (!running) return
        running = false
        handler.removeCallbacks(focusTick)
        scope?.cancel()
        scope = null
        watchingAll = false
        cancelScan()
        detector.leave(graph.clock.now())
        runCatching { unregisterReceiver(screenReceiver) }
    }

    companion object {
        private const val DEBOUNCE_MS = 300L
        private const val MIN_SCAN_INTERVAL_MS = 500L
        private const val PENDING_TICK_MS = 500L
        private const val IDLE_TICK_MS = 3_000L
        private const val FOCUS_TICK_MS = 30_000L

        @Volatile
        var running: Boolean = false
            private set
    }
}
