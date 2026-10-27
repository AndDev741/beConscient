package org.beconscient.collector.notifications

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.beconscient.collector.AppGraph
import org.beconscient.collector.focus.FocusPhase

/**
 * Bound by the system once the person grants notification access. It reads
 * only the package, post time, flags, importance and category. During a focus
 * period it snoozes notifications from blocked apps until the period ends.
 * Exported only to holders of BIND_NOTIFICATION_LISTENER_SERVICE, that is,
 * the system.
 */
class NotificationListener : NotificationListenerService() {

    private val graph by lazy { AppGraph.get(this) }
    private var scope: CoroutineScope? = null

    override fun onListenerConnected() {
        connected = true
        val ranking = runCatching { currentRanking }.getOrNull()
        val sbns = runCatching { activeNotifications?.toList() }.getOrNull().orEmpty()
        val active = sbns.map { describe(it, ranking) }
        graph.notificationQueue.submit { graph.notificationCollector.onConnected(active) }
        for (p in active) if (p.category == Notification.CATEGORY_CALL) graph.focus.callShown(p.packageName)
        scope?.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { s ->
            // Each new period holds what is already in the shade, not only what arrives after
            s.launch {
                graph.focus.phase
                    .map { (it as? FocusPhase.Locked)?.period?.startedAt }
                    .distinctUntilChanged()
                    .collect { if (it != null) holdActive() }
            }
        }
    }

    override fun onListenerDisconnected() {
        connected = false
        scope?.cancel()
        scope = null
        graph.notificationQueue.submit { graph.notificationCollector.onDisconnected() }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?, rankingMap: RankingMap?) {
        sbn ?: return
        val posted = describe(sbn, rankingMap)
        graph.notificationQueue.submit { graph.notificationCollector.onPosted(posted) }
        if (posted.category == Notification.CATEGORY_CALL) graph.focus.callShown(posted.packageName)
        hold(posted)
    }

    private fun holdActive() {
        val ranking = runCatching { currentRanking }.getOrNull()
        runCatching { activeNotifications?.toList() }.getOrNull().orEmpty().forEach { hold(describe(it, ranking)) }
    }

    private fun hold(p: NotificationCollector.Posted) {
        val ms = graph.focus.holdFor(p.key, p.packageName, p.isOngoing, p.category)
        if (ms > 0) runCatching { snoozeNotification(p.key, ms) }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?, rankingMap: RankingMap?, reason: Int) {
        sbn ?: return
        if (sbn.notification?.category == Notification.CATEGORY_CALL) graph.focus.callGone(sbn.packageName)
        val key = sbn.key
        val pkg = sbn.packageName
        val postTime = sbn.postTime
        graph.notificationQueue.submit { graph.notificationCollector.onRemoved(key, pkg, postTime, reason) }
    }

    private fun describe(sbn: StatusBarNotification, rankingMap: RankingMap?): NotificationCollector.Posted {
        val n: Notification? = sbn.notification
        val flags = n?.flags ?: 0
        val importance = rankingMap?.let { map ->
            val r = Ranking()
            if (map.getRanking(sbn.key, r)) r.importance else null
        }?.takeIf { it in NotificationManager.IMPORTANCE_NONE..NotificationManager.IMPORTANCE_MAX }
        return NotificationCollector.Posted(
            key = sbn.key,
            packageName = sbn.packageName,
            postTime = sbn.postTime,
            isGroupSummary = flags and Notification.FLAG_GROUP_SUMMARY != 0,
            isOngoing = sbn.isOngoing,
            importance = importance,
            category = n?.category,
        )
    }

    companion object {
        /** True while the system has this listener connected in this process. */
        @Volatile
        var connected: Boolean = false
            private set

        /**
         * Makes the system bind the listener again. MIUI drops the binding and
         * nothing restores it until access is toggled by hand; requestRebind only
         * undoes a requestUnbind, so this flips the component off and back to its
         * manifest state, which the notification manager answers with a rebind.
         */
        fun rebind(context: Context) {
            val pm = context.packageManager
            val component = ComponentName(context, NotificationListener::class.java)
            pm.setComponentEnabledSetting(component, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
            pm.setComponentEnabledSetting(component, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, PackageManager.DONT_KILL_APP)
        }
    }
}
