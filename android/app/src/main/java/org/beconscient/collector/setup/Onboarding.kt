package org.beconscient.collector.setup

import android.content.SharedPreferences
import androidx.core.content.edit

/** The setup steps, in the order the onboarding shows them. */
enum class Step(val title: String, val required: Boolean) {
    PAIRING("Pair with your server", true),
    USAGE("Usage access", true),
    NOTIFICATIONS("Notification access", true),
    ACCESSIBILITY("YouTube Shorts detection", false),
    BATTERY("Battery optimization", true),
    UNUSED_APPS("Pause app activity if unused", true),
}

enum class StepStatus { DONE, PENDING, DENIED, SKIPPED, NOT_APPLICABLE }

data class StepState(val step: Step, val status: StepStatus) {
    val needsWork: Boolean get() = status == StepStatus.PENDING || status == StepStatus.DENIED
    val warning: Boolean get() = needsWork || status == StepStatus.SKIPPED
}

/** Remembers which steps were tried and which were skipped on purpose. */
class SetupPrefs(private val prefs: SharedPreferences) {
    fun attempted(step: Step) = prefs.getBoolean("attempted.${step.name}", false)
    fun markAttempted(step: Step) = prefs.edit { putBoolean("attempted.${step.name}", true) }
    fun skipped(step: Step) = prefs.getBoolean("skipped.${step.name}", false)
    fun setSkipped(step: Step, skipped: Boolean) = prefs.edit { putBoolean("skipped.${step.name}", skipped) }
    var finished: Boolean
        get() = prefs.getBoolean("finished", false)
        set(value) = prefs.edit { putBoolean("finished", value) }

    companion object {
        const val PREFS_NAME = "setup"
    }
}

object Onboarding {
    /**
     * Done when granted; skipped when the person chose to skip; denied when they
     * went to the settings page and came back without granting; pending before
     * any attempt. A null [granted] means the setting does not exist here.
     */
    fun statusOf(granted: Boolean?, attempted: Boolean, skipped: Boolean): StepStatus = when {
        granted == null -> StepStatus.NOT_APPLICABLE
        granted -> StepStatus.DONE
        skipped -> StepStatus.SKIPPED
        attempted -> StepStatus.DENIED
        else -> StepStatus.PENDING
    }

    fun granted(step: Step, probe: SensorProbe, paired: Boolean): Boolean? = when (step) {
        Step.PAIRING -> paired
        Step.USAGE -> probe.usageAccess()
        Step.NOTIFICATIONS -> probe.notificationAccess()
        Step.ACCESSIBILITY -> probe.accessibilityEnabled()
        Step.BATTERY -> probe.batteryExempt()
        Step.UNUSED_APPS -> probe.unusedAppRestrictionsOff()
    }

    fun evaluate(probe: SensorProbe, paired: Boolean, prefs: SetupPrefs): List<StepState> =
        Step.entries.map { step ->
            StepState(step, statusOf(granted(step, probe, paired), prefs.attempted(step), prefs.skipped(step)))
        }

    /** The onboarding opens by itself until it was finished once and while a step is still untouched. */
    fun shouldShow(states: List<StepState>, finished: Boolean): Boolean =
        !finished || states.any { it.status == StepStatus.PENDING }
}
