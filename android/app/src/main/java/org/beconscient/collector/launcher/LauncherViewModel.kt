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

    val settings: StateFlow<LauncherSettings> = store.settings

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

    /** Opens [app] straight away, or puts the pause screen in front of it when the owner controls it. */
    fun open(context: Context, app: LaunchableApp) {
        val pause = Pauses.decide(store.settings.value, app.packageName, LocalDateTime.now())
        if (pause == null) {
            launch(context, app)
            return
        }
        // Offer the alternative only when it is installed, and report only what was offered
        val alternative = pause.alternative?.let { pkg -> _apps.value.firstOrNull { it.packageName == pkg } }
        _pause.value = PauseUi(pause.copy(alternative = alternative?.packageName), app, alternative, graph.clock.now())
    }

    /** The owner's answer on the pause screen. Opening the app itself is refused until the wait is over. */
    fun choose(context: Context, outcome: Events.PauseOutcome) {
        val p = _pause.value ?: return
        if (outcome == Events.PauseOutcome.OPENED && graph.clock.now() - p.shownAt < Pauses.WAIT_MS) return
        _pause.value = null
        viewModelScope.launch { log.record(p.pause, p.shownAt, outcome) }
        when (outcome) {
            Events.PauseOutcome.OPENED -> launch(context, p.app)
            Events.PauseOutcome.ALTERNATIVE -> p.alternative?.let { launch(context, it) }
            Events.PauseOutcome.BACKED_OUT -> Unit
        }
    }

    /** Screen off or another app on top: a pause still showing counts as backing out. */
    fun backOutOfPause(context: Context) {
        if (_pause.value != null) choose(context, Events.PauseOutcome.BACKED_OUT)
    }

    /** Home pressed: back to the home screen, like any launcher. */
    fun leave(context: Context) {
        backOutOfPause(context)
        _screen.value = Screen.Home
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
