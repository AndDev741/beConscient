package org.beconscient.collector.setup

import android.content.Context
import org.beconscient.collector.support.FakeProbe
import org.beconscient.collector.support.context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OnboardingTest {
    private val prefs = SetupPrefs(context().getSharedPreferences("setup-test", Context.MODE_PRIVATE))

    @Test
    fun `status follows grant, skip and attempt`() {
        assertEquals(StepStatus.DONE, Onboarding.statusOf(true, attempted = false, skipped = true))
        assertEquals(StepStatus.SKIPPED, Onboarding.statusOf(false, attempted = true, skipped = true))
        assertEquals(StepStatus.DENIED, Onboarding.statusOf(false, attempted = true, skipped = false))
        assertEquals(StepStatus.PENDING, Onboarding.statusOf(false, attempted = false, skipped = false))
        assertEquals(StepStatus.NOT_APPLICABLE, Onboarding.statusOf(null, attempted = false, skipped = false))
    }

    @Test
    fun `steps are evaluated in order from the live permission state`() {
        val probe = FakeProbe(usage = false, notifications = true, accessibility = false, battery = false, unusedOff = null)
        prefs.markAttempted(Step.USAGE)
        prefs.setSkipped(Step.ACCESSIBILITY, true)
        val states = Onboarding.evaluate(probe, paired = true, prefs = prefs)
        assertEquals(Step.entries.toList(), states.map { it.step })
        assertEquals(
            listOf(StepStatus.DONE, StepStatus.DENIED, StepStatus.DONE, StepStatus.SKIPPED, StepStatus.PENDING, StepStatus.NOT_APPLICABLE),
            states.map { it.status },
        )
        assertTrue(Onboarding.shouldShow(states, finished = true))
        val allDone = Onboarding.evaluate(FakeProbe(), paired = true, prefs = prefs)
        assertFalse(Onboarding.shouldShow(allDone, finished = true))
        assertTrue(Onboarding.shouldShow(allDone, finished = false))
    }
}
