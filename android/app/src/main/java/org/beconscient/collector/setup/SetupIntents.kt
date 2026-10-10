package org.beconscient.collector.setup

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.IntentCompat
import androidx.core.net.toUri
import org.beconscient.collector.notifications.NotificationListener

/** Opens the system settings page for each step, with fallbacks for OEM builds that lack the precise page. */
object SetupIntents {

    fun open(context: Context, step: Step): Boolean = candidates(context, step).any { start(context, it) }

    /** App info, where Android 13+ hides "Allow restricted settings" in the three-dot menu. */
    fun openAppInfo(context: Context): Boolean =
        start(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri(context)))

    @SuppressLint("BatteryLife") // Sideloaded personal tool: the direct exemption request is the point of this step.
    private fun candidates(context: Context, step: Step): List<Intent> {
        val pkg = context.packageName
        return when (step) {
            Step.PAIRING -> emptyList()
            Step.USAGE -> listOf(
                Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, packageUri(context)),
                Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS),
            )
            Step.NOTIFICATIONS -> buildList {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    add(
                        Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
                            Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                            ComponentName(context, NotificationListener::class.java).flattenToString(),
                        ),
                    )
                }
                add(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
            Step.ACCESSIBILITY -> listOf(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Step.BATTERY -> listOf(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:$pkg".toUri()),
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            )
            Step.UNUSED_APPS -> listOf(
                IntentCompat.createManageUnusedAppRestrictionsIntent(context, pkg),
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri(context)),
            )
        }
    }

    private fun packageUri(context: Context) = Uri.fromParts("package", context.packageName, null)

    private fun start(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }
}
