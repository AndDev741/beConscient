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
import org.beconscient.collector.AppGraph
import org.beconscient.collector.events.Events
import org.beconscient.collector.queue.Kv

/**
 * Watches only com.google.android.youtube (see res/xml/shorts_accessibility.xml)
 * and reads only view resource ids, never text. It feeds [ShortsDetector] and
 * writes `android.shorts` markers. Exported only to holders of
 * BIND_ACCESSIBILITY_SERVICE, that is, the system.
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

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) {
                cancelScan()
                detector.leave(graph.clock.now())
            }
        }
    }

    override fun onServiceConnected() {
        running = true
        ContextCompat.registerReceiver(
            this,
            screenReceiver,
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.packageName?.toString() != ShortsRules.YOUTUBE_PACKAGE) return
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
        cancelScan()
        detector.leave(graph.clock.now())
        runCatching { unregisterReceiver(screenReceiver) }
    }

    companion object {
        private const val DEBOUNCE_MS = 300L
        private const val MIN_SCAN_INTERVAL_MS = 500L
        private const val PENDING_TICK_MS = 500L
        private const val IDLE_TICK_MS = 3_000L

        @Volatile
        var running: Boolean = false
            private set
    }
}
