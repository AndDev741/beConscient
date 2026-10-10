package org.beconscient.collector.usage

import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process
import java.lang.reflect.Method

/** One raw record from UsageStatsManager, before mapping. */
data class RawUsageEvent(
    val eventType: Int,
    val timeStamp: Long,
    val packageName: String?,
    val className: String?,
    val instanceId: Int? = null,
)

/** Where usage events come from. The Android one wraps UsageStatsManager; tests use fakes. */
interface UsageSource {
    fun hasAccess(): Boolean

    /** Events with `from <= timeStamp < to`, oldest first. */
    fun query(from: Long, to: Long): List<RawUsageEvent>
}

class AndroidUsageSource(private val context: Context) : UsageSource {

    override fun hasAccess(): Boolean = hasUsageAccess(context)

    override fun query(from: Long, to: Long): List<RawUsageEvent> {
        val usm = context.getSystemService(UsageStatsManager::class.java) ?: return emptyList()
        val events = usm.queryEvents(from, to) ?: return emptyList()
        val out = ArrayList<RawUsageEvent>()
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (UsageMapping.kindOf(e.eventType) == null) continue
            out += RawUsageEvent(
                eventType = e.eventType,
                timeStamp = e.timeStamp,
                packageName = e.packageName,
                className = e.className,
                instanceId = InstanceId.read(e),
            )
        }
        return out
    }

    companion object {
        fun hasUsageAccess(context: Context): Boolean {
            val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
            val op = AppOpsManager.OPSTR_GET_USAGE_STATS
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
                appOps.checkOpNoThrow(op, Process.myUid(), context.packageName)
            } else {
                @Suppress("DEPRECATION")
                appOps.unsafeCheckOpNoThrow(op, Process.myUid(), context.packageName)
            }
            return if (mode == AppOpsManager.MODE_DEFAULT) {
                context.checkSelfPermission(android.Manifest.permission.PACKAGE_USAGE_STATS) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            } else {
                mode == AppOpsManager.MODE_ALLOWED
            }
        }
    }
}

/**
 * The activity instance id. `UsageEvents.Event#getInstanceId` is a system API,
 * not part of the public SDK, so it is read reflectively on API 31+ and simply
 * left out when the platform blocks the call. Verify on the real device.
 */
internal object InstanceId {
    @Volatile private var method: Method? = null
    @Volatile private var unavailable = Build.VERSION.SDK_INT < Build.VERSION_CODES.S

    @SuppressLint("DiscouragedPrivateApi", "SoonBlockedPrivateApi", "BlockedPrivateApi")
    fun read(e: UsageEvents.Event): Int? {
        if (unavailable) return null
        if (UsageMapping.kindOf(e.eventType)?.startsWith("ACTIVITY_") != true) return null
        return try {
            val m = method ?: UsageEvents.Event::class.java.getMethod("getInstanceId").also { method = it }
            (m.invoke(e) as? Int)?.takeIf { it != 0 }
        } catch (t: Throwable) {
            unavailable = true
            null
        }
    }
}
