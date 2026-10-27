package org.beconscient.collector.focus

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Telephony
import android.telecom.TelecomManager
import android.view.inputmethod.InputMethodManager
import org.beconscient.collector.launcher.InstalledApps

/** Which apps a focus period lets through. */
interface FocusApps {
    /** Packages with a launcher entry. Only these can be blocked: system dialogs, keyboards and services always work. */
    fun launchable(): Set<String>

    /** The allowed list while the owner has not edited it. */
    fun defaults(): Set<String>

    /** Never blocked: the collector, the home app, the dialer's call screens. */
    fun always(): Set<String>
}

/** Reads the package manager at most every few minutes: the home screen asks every second during a period. */
class AndroidFocusApps(private val context: Context) : FocusApps {
    private val launchable = Cached { InstalledApps.load(context).map { it.packageName }.toSet() }
    private val defaults = Cached { readDefaults() }
    private val always = Cached { readAlways() }

    override fun launchable(): Set<String> = launchable.get()

    override fun defaults(): Set<String> = defaults.get()

    override fun always(): Set<String> = always.get()

    private class Cached(private val read: () -> Set<String>) {
        @Volatile
        private var value: Pair<Long, Set<String>>? = null

        fun get(): Set<String> {
            val now = System.currentTimeMillis()
            value?.let { (at, set) -> if (now - at < CACHE_MS) return set }
            return read().also { value = now to it }
        }
    }

    private fun readDefaults(): Set<String> {
        val telecom = context.getSystemService(TelecomManager::class.java)
        val dialer = runCatching { telecom?.defaultDialerPackage }.getOrNull()
        val sms = runCatching { Telephony.Sms.getDefaultSmsPackage(context) }.getOrNull()
        return DEFAULTS + listOfNotNull(dialer, sms)
    }

    private fun readAlways(): Set<String> {
        val keyboards = runCatching {
            context.getSystemService(InputMethodManager::class.java)?.enabledInputMethodList.orEmpty().map { it.packageName }
        }.getOrDefault(emptyList())
        val home = runCatching {
            context.packageManager.resolveActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                PackageManager.MATCH_DEFAULT_ONLY,
            )?.activityInfo?.packageName
        }.getOrNull()
        return ALWAYS + keyboards + listOfNotNull(context.packageName, home)
    }

    companion object {
        private const val CACHE_MS = 10 * 60_000L

        val ALWAYS = setOf(
            "android",
            "com.android.systemui",
            "com.android.incallui",
            "com.android.server.telecom",
            "com.android.phone",
            "com.android.emergency",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.lbe.security.miui",
        )

        /** Phone, messages, contacts, maps, camera, clock, reading, sleep, rides and sign-in codes. */
        val DEFAULTS = setOf(
            "com.google.android.dialer",
            "com.google.android.apps.messaging",
            "com.google.android.contacts",
            "com.android.contacts",
            "com.google.android.apps.maps",
            "com.android.camera",
            "com.google.android.GoogleCamera",
            "com.android.deskclock",
            "com.google.android.deskclock",
            "com.amazon.kindle",
            "com.northcube.sleepcycle",
            "com.ubercab",
            "ee.mtakso.client",
            "com.citymapper.app.release",
            "com.azure.authenticator",
            "com.google.android.apps.authenticator2",
        )
    }
}
