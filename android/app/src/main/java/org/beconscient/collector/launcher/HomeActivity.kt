package org.beconscient.collector.launcher

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.beconscient.collector.ui.MainActivity
import org.beconscient.collector.upload.Scheduling

/**
 * The home screen, once the owner picks beConscient as the default home app.
 * It runs in its own task, so the collector's status screen never stacks on it.
 */
class HomeActivity : ComponentActivity() {
    private val vm: LauncherViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        Scheduling.ensurePeriodic(this)
        setContent { LauncherTheme { LauncherRoot(vm) } }
    }

    override fun onResume() {
        super.onResume()
        // Apps may have been installed or removed while another app was in front
        vm.refreshApps()
    }

    /** Home pressed while the launcher is already in front, or on the way back from an app. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        vm.leave(this)
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) vm.backOutOfPause(this)
    }
}

@Composable
private fun LauncherRoot(vm: LauncherViewModel) {
    val context = LocalContext.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val apps by vm.apps.collectAsStateWithLifecycle()
    val screen by vm.screen.collectAsStateWithLifecycle()
    val pause by vm.pause.collectAsStateWithLifecycle()
    var menuFor by remember { mutableStateOf<LaunchableApp?>(null) }
    val now = rememberNow()

    Surface(Modifier.fillMaxSize()) {
        val shown = pause
        if (shown != null) {
            PauseScreen(shown) { vm.choose(context, it) }
            return@Surface
        }
        val open = { app: LaunchableApp -> vm.open(context, app) }
        val menu = { app: LaunchableApp -> menuFor = app }
        when (val s = screen) {
            Screen.Home -> HomeScreen(
                now = now,
                settings = settings,
                apps = apps,
                onOpen = open,
                onMenu = menu,
                onAllApps = { vm.show(Screen.AllApps) },
                onSettings = { vm.show(Screen.Settings) },
            )
            Screen.AllApps -> AllAppsScreen(apps, settings, onOpen = open, onMenu = menu, onClose = { vm.show(Screen.Home) })
            Screen.Settings -> LauncherSettingsScreen(
                settings = settings,
                apps = apps,
                onBack = { vm.show(Screen.Home) },
                onEditBlock = { vm.show(Screen.EditBlock(it)) },
                onToggleControlled = vm::toggleControlled,
                onHomeSettings = { InstalledApps.openHomeSettings(context) },
                onCollectorStatus = {
                    context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                },
            )
            is Screen.EditBlock -> BlockEditor(
                block = settings.blocks.firstOrNull { it.id == s.blockId },
                apps = apps,
                onSave = vm::saveBlock,
                onDelete = vm::deleteBlock,
                onCancel = { vm.show(Screen.Settings) },
            )
        }
        menuFor?.let { app ->
            AppMenu(
                app = app,
                settings = settings,
                onTogglePinned = { vm.togglePinned(app.packageName) },
                onToggleControlled = { vm.toggleControlled(app.packageName) },
                onAppInfo = {
                    menuFor = null
                    InstalledApps.openAppInfo(context, app.packageName)
                },
                onDismiss = { menuFor = null },
            )
        }
    }
}
