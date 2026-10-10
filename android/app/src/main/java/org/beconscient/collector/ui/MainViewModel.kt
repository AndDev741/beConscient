package org.beconscient.collector.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.beconscient.collector.AppGraph
import org.beconscient.collector.events.Events
import org.beconscient.collector.pairing.PairingClient
import org.beconscient.collector.queue.EventState
import org.beconscient.collector.queue.Kv
import org.beconscient.collector.queue.SensorGapRow
import org.beconscient.collector.setup.Onboarding
import org.beconscient.collector.setup.SetupPrefs
import org.beconscient.collector.setup.Step
import org.beconscient.collector.setup.StepState
import org.beconscient.collector.upload.Scheduling

/** Everything the status screen shows. It reads only local state, so it works without the server. */
data class StatusSnapshot(
    val paired: Boolean,
    val deviceName: String?,
    val fingerprint: String?,
    val serverFingerprint: String?,
    val sensors: Map<String, Events.Sensor>,
    val pending: Int,
    val inBatch: Int,
    val acked: Int,
    val rejected: Int,
    val lastUploadOkAt: Long?,
    val lastAttemptAt: Long?,
    val lastError: String?,
    val watermark: Long?,
    val homePackage: String?,
    val gaps: List<SensorGapRow>,
)

sealed interface PairingUi {
    data object Idle : PairingUi
    data object Working : PairingUi
    data class Paired(val fingerprint: String, val serverFingerprint: String, val match: Boolean) : PairingUi
    data object InvalidCode : PairingUi
    data object CodeRejected : PairingUi
    data class Failed(val reason: String) : PairingUi
}

data class UiState(
    val loaded: Boolean = false,
    val steps: List<StepState> = emptyList(),
    val status: StatusSnapshot? = null,
    val showOnboarding: Boolean = true,
    val pairing: PairingUi = PairingUi.Idle,
    val notice: String? = null,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val graph = AppGraph.get(application)
    private val prefs = SetupPrefs(application.getSharedPreferences(SetupPrefs.PREFS_NAME, Context.MODE_PRIVATE))
    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    /** Re-checks every permission and reloads the local status. Called on every resume. */
    fun refresh() {
        viewModelScope.launch {
            val steps = Onboarding.evaluate(graph.probe, graph.credentials.load() != null, prefs)
            val status = snapshot()
            _ui.update {
                it.copy(
                    loaded = true,
                    steps = steps,
                    status = status,
                    showOnboarding = if (it.loaded) it.showOnboarding else Onboarding.shouldShow(steps, prefs.finished),
                )
            }
        }
    }

    private suspend fun snapshot(): StatusSnapshot {
        val dao = graph.db.dao()
        val creds = graph.credentials.load()
        return StatusSnapshot(
            paired = creds != null,
            deviceName = creds?.deviceName,
            fingerprint = creds?.fingerprint,
            serverFingerprint = creds?.serverFingerprint,
            sensors = graph.health.sensors(),
            pending = dao.countByState(EventState.PENDING),
            inBatch = dao.countByState(EventState.IN_BATCH),
            acked = dao.countByState(EventState.ACKED),
            rejected = dao.countRejected(),
            lastUploadOkAt = dao.long(Kv.UPLOAD_LAST_OK_AT),
            lastAttemptAt = dao.long(Kv.UPLOAD_LAST_ATTEMPT_AT),
            lastError = dao.kv(Kv.UPLOAD_LAST_ERROR),
            watermark = dao.long(Kv.USAGE_WATERMARK),
            homePackage = graph.probe.homePackage(),
            gaps = dao.recentGaps(5),
        )
    }

    fun markAttempted(step: Step) = prefs.markAttempted(step)

    fun setSkipped(step: Step, skipped: Boolean) {
        prefs.setSkipped(step, skipped)
        refresh()
    }

    fun finishOnboarding() {
        prefs.finished = true
        _ui.update { it.copy(showOnboarding = false) }
        refresh()
    }

    fun openOnboarding() {
        _ui.update { it.copy(showOnboarding = true, pairing = PairingUi.Idle) }
        refresh()
    }

    fun pair(code: String, name: String) {
        _ui.update { it.copy(pairing = PairingUi.Working) }
        viewModelScope.launch {
            val result = graph.pairing.pair(code, name)
            val ui = when (result) {
                is PairingClient.Result.Paired -> {
                    Scheduling.collectNow(getApplication())
                    PairingUi.Paired(
                        result.credentials.fingerprint,
                        result.credentials.serverFingerprint,
                        result.fingerprintsMatch,
                    )
                }
                PairingClient.Result.InvalidCode -> PairingUi.InvalidCode
                PairingClient.Result.CodeRejected -> PairingUi.CodeRejected
                is PairingClient.Result.Failed -> PairingUi.Failed(result.reason)
            }
            _ui.update { it.copy(pairing = ui) }
            refresh()
        }
    }

    fun dismissPairingMessage() = _ui.update { it.copy(pairing = PairingUi.Idle) }

    fun uploadNow() {
        Scheduling.upload(getApplication(), now = true)
        _ui.update { it.copy(notice = "Upload requested. It runs as soon as a network is available.") }
    }

    suspend fun countResendable(since: Long): Int = graph.maintenance.countResendable(since)

    fun resendSince(since: Long) {
        viewModelScope.launch {
            val n = graph.maintenance.resendSince(since)
            Scheduling.upload(getApplication(), now = true)
            _ui.update { it.copy(notice = "$n acknowledged events queued again.") }
            refresh()
        }
    }

    fun dismissNotice() = _ui.update { it.copy(notice = null) }
}
