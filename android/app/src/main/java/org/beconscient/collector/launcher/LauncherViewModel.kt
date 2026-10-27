package org.beconscient.collector.launcher

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.beconscient.collector.AppGraph
import org.beconscient.collector.events.Events
import org.beconscient.collector.focus.BlockedApp
import org.beconscient.collector.focus.FocusPhase
import org.beconscient.collector.focus.FocusRecord
import org.beconscient.collector.focus.FocusToday
import java.time.LocalDateTime

sealed interface Screen {
    data object Home : Screen
    data object AllApps : Screen
    data object Settings : Screen

    /** [blockId] null adds a new block. */
    data class EditBlock(val blockId: String?) : Screen
}

/** The pause screen in front of [app]. [shownAt] is wall-clock time, sent as the event's `at`. */
data class PauseUi(val pause: Pause, val app: LaunchableApp, val alternative: LaunchableApp?, val shownAt: Long)

class LauncherViewModel(application: Application) : AndroidViewModel(application) {
    private val graph = AppGraph.get(application)
    private val store = graph.launcher
    private val log = PauseLog(graph.db.dao(), graph.clock)
    private val focus = graph.focus

    val settings: StateFlow<LauncherSettings> = store.settings
    val phase: StateFlow<FocusPhase> = focus.phase
    val history: StateFlow<List<FocusRecord>> = focus.history
    val lastBlocked: StateFlow<BlockedApp?> = focus.lastBlocked

    private val _apps = MutableStateFlow<List<LaunchableApp>>(emptyList())
    val apps: StateFlow<List<LaunchableApp>> = _apps.asStateFlow()

    private val _screen = MutableStateFlow<Screen>(Screen.Home)
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    private val _pause = MutableStateFlow<PauseUi?>(null)
    val pause: StateFlow<PauseUi?> = _pause.asStateFlow()

    fun refreshApps() {
        viewModelScope.launch(Dispatchers.IO) { _apps.value = InstalledApps.load(getApplication()) }
    }

    fun show(screen: Screen) {
        _screen.value = screen
    }

    /** Moves the focus phase forward; the home screen calls it when it comes back and every second while counting down. */
    fun refreshFocus() {
        focus.refresh()
    }

    /**
     * Opens [app] straight away, or puts the pause screen in front of it when the
     * owner controls it. During a focus period a blocked app does not open at all.
     */
    fun open(context: Context, app: LaunchableApp) {
        if (focus.blocks(app.packageName)) {
            focus.onBlocked(app.packageName)
            return
        }
        val now = LocalDateTime.now()
        val today = graph.pauseCounts.today(app.packageName, now.toLocalDate())
        val pause = Pauses.decide(store.settings.value, app.packageName, now, today)
        if (pause == null) {
            launch(context, app)
            return
        }
        val shownAt = graph.clock.now()
        graph.pauseCounts.shown(app.packageName, now.toLocalDate(), shownAt)
        // Offer the alternative only when it is installed, and report only what was offered
        val alternative = pause.alternative?.let { pkg -> _apps.value.firstOrNull { it.packageName == pkg } }
        _pause.value = PauseUi(pause.copy(alternative = alternative?.packageName), app, alternative, shownAt)
    }

    /**
     * The owner's answer on the pause screen. Opening the app itself is refused
     * until the wait is over and needs a reason.
     */
    fun choose(context: Context, outcome: Events.PauseOutcome, intent: Events.PauseIntent? = null) {
        val p = _pause.value ?: return
        if (outcome == Events.PauseOutcome.OPENED && (intent == null || graph.clock.now() - p.shownAt < p.pause.waitMs)) return
        _pause.value = null
        viewModelScope.launch { log.record(p.pause, p.shownAt, outcome, intent) }
        when (outcome) {
            Events.PauseOutcome.OPENED -> launch(context, p.app)
            Events.PauseOutcome.ALTERNATIVE -> p.alternative?.let { launch(context, it) }
            Events.PauseOutcome.REST -> {
                focus.startRest()
                _screen.value = Screen.Home
            }
            Events.PauseOutcome.BACKED_OUT -> Unit
        }
    }

    fun startFocus(minutes: Int) {
        focus.startManual(minutes)
        _screen.value = Screen.Home
    }

    fun decide(next: Events.FocusNext) {
        focus.decide(next)
    }

    fun today(): FocusToday = focus.today()

    /** The apps a focus period lets through, as the settings show them. */
    fun focusAllowed(): Set<String> = focus.allowed()

    fun toggleFocusAllowed(pkg: String) = store.toggleFocusAllowed(pkg, focus.allowed())

    /** Screen off or another app on top: a pause still showing counts as backing out. */
    fun backOutOfPause(context: Context) {
        if (_pause.value != null) choose(context, Events.PauseOutcome.BACKED_OUT)
    }

    /** Home pressed: back to the home screen, like any launcher. */
    fun leave(context: Context) {
        backOutOfPause(context)
        _screen.value = Screen.Home
        focus.refresh()
    }

    fun togglePinned(pkg: String) = store.togglePinned(pkg)

    fun toggleControlled(pkg: String) = store.toggleControlled(pkg)

    fun saveBlock(block: Block) {
        store.saveBlock(block)
        _screen.value = Screen.Settings
    }

    fun deleteBlock(id: String) {
        store.deleteBlock(id)
        _screen.value = Screen.Settings
    }

    private fun launch(context: Context, app: LaunchableApp) {
        if (!InstalledApps.launch(context, app)) refreshApps()
        _screen.value = Screen.Home
    }
}
