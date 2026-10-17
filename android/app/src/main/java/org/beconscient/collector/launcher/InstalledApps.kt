package org.beconscient.collector.launcher

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import java.text.Collator

/** An app with a launcher entry. */
data class LaunchableApp(val packageName: String, val label: String, val component: ComponentName)

object InstalledApps {
    /** Every app with a launcher entry, one per package, sorted by label. Slow enough to keep off the main thread. */
    fun load(context: Context): List<LaunchableApp> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val collator = Collator.getInstance()
        return pm.queryIntentActivities(intent, 0)
            .map { info ->
                val a = info.activityInfo
                LaunchableApp(a.packageName, info.loadLabel(pm).toString().trim(), ComponentName(a.packageName, a.name))
            }
            .distinctBy { it.packageName }
            .sortedWith { x, y -> collator.compare(x.label, y.label) }
    }

    /** False when the app is gone, for example uninstalled since the list was read. */
    fun launch(context: Context, app: LaunchableApp): Boolean = start(
        context,
        Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(app.component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED),
    )

    fun openAppInfo(context: Context, packageName: String): Boolean = start(
        context,
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )

    /** The system page where the owner picks the default home app; MIUI moves it around, hence the fallbacks. */
    fun openHomeSettings(context: Context): Boolean =
        listOf(Settings.ACTION_HOME_SETTINGS, Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS, Settings.ACTION_SETTINGS)
            .any { start(context, Intent(it).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }

    private fun start(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }
}
