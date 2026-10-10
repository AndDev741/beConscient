package org.beconscient.collector.setup

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import org.beconscient.collector.notifications.NotificationListener
import org.beconscient.collector.shorts.ShortsAccessibilityService
import org.beconscient.collector.usage.AndroidUsageSource

/** What the health record and the setup screens need to know about the device. */
interface SensorProbe {
    fun usageAccess(): Boolean
    fun notificationAccess(): Boolean
    fun listenerConnected(): Boolean
    fun accessibilityEnabled(): Boolean
    fun shortsRunning(): Boolean
    fun batteryExempt(): Boolean

    /** True when "Pause app activity if unused" is off; null when this Android version has no such setting. */
    fun unusedAppRestrictionsOff(): Boolean?

    /** Package of the current default launcher, or null when none is set. */
    fun homePackage(): String?
}

class AndroidSensorProbe(context: Context) : SensorProbe {
    private val app = context.applicationContext

    override fun usageAccess() = AndroidUsageSource.hasUsageAccess(app)

    override fun notificationAccess() =
        NotificationManagerCompat.getEnabledListenerPackages(app).contains(app.packageName)

    override fun listenerConnected() = NotificationListener.connected

    override fun accessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(app.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        val ours = ComponentName(app, ShortsAccessibilityService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == ours }
    }

    override fun shortsRunning() = ShortsAccessibilityService.running

    override fun batteryExempt(): Boolean =
        app.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(app.packageName) == true

    override fun unusedAppRestrictionsOff(): Boolean? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) app.packageManager.isAutoRevokeWhitelisted else null

    override fun homePackage(): String? {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val info = app.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) ?: return null
        // "android" is the chooser shown when no default launcher is set.
        return info.activityInfo?.packageName?.takeIf { it != "android" }
    }
}
