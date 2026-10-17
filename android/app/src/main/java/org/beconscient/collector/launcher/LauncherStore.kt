package org.beconscient.collector.launcher

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Keeps [LauncherSettings] in its own preferences file; the backup rules exclude it like everything else. */
class LauncherStore(private val prefs: SharedPreferences) {
    private val state = MutableStateFlow(LauncherSettings.decode(prefs.getString(KEY, null)))
    val settings: StateFlow<LauncherSettings> = state.asStateFlow()

    fun update(change: (LauncherSettings) -> LauncherSettings) {
        synchronized(this) {
            val next = change(state.value)
            if (next == state.value) return
            prefs.edit { putString(KEY, next.encode()) }
            state.value = next
        }
    }

    fun togglePinned(pkg: String) = update {
        it.copy(pinned = if (pkg in it.pinned) it.pinned - pkg else it.pinned + pkg)
    }

    fun toggleControlled(pkg: String) = update {
        it.copy(controlled = if (pkg in it.controlled) it.controlled - pkg else it.controlled + pkg)
    }

    /** Adds [block], or replaces the one with the same id in place. */
    fun saveBlock(block: Block) = update { s ->
        val at = s.blocks.indexOfFirst { it.id == block.id }
        s.copy(blocks = if (at < 0) s.blocks + block else s.blocks.toMutableList().also { it[at] = block })
    }

    fun deleteBlock(id: String) = update { s -> s.copy(blocks = s.blocks.filterNot { it.id == id }) }

    companion object {
        private const val FILE = "launcher"
        private const val KEY = "settings"

        fun android(context: Context) = LauncherStore(context.getSharedPreferences(FILE, Context.MODE_PRIVATE))
    }
}
