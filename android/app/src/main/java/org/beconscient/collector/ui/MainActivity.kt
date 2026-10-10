package org.beconscient.collector.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.beconscient.collector.setup.SetupIntents
import org.beconscient.collector.upload.Scheduling

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CollectorTheme {
                val state by vm.ui.collectAsStateWithLifecycle()
                Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
                    val m = Modifier.padding(padding)
                    if (!state.loaded) return@Scaffold
                    if (state.showOnboarding) {
                        OnboardingScreen(
                            state = state,
                            onOpenStep = { step ->
                                vm.markAttempted(step)
                                SetupIntents.open(this, step)
                            },
                            onOpenAppInfo = { SetupIntents.openAppInfo(this) },
                            onSkip = vm::setSkipped,
                            onPair = vm::pair,
                            onDismissPairing = vm::dismissPairingMessage,
                            onFinish = vm::finishOnboarding,
                            modifier = m,
                        )
                    } else {
                        StatusScreen(
                            state = state,
                            onFix = vm::openOnboarding,
                            onUploadNow = vm::uploadNow,
                            countResendable = vm::countResendable,
                            onResend = vm::resendSince,
                            onDismissNotice = vm::dismissNotice,
                            modifier = m,
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Permissions may have changed in Settings; re-check them all and read usage now.
        vm.refresh()
        Scheduling.ensurePeriodic(this)
        Scheduling.collectNow(this)
    }
}
