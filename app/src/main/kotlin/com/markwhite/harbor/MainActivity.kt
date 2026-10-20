package com.markwhite.harbor

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.markwhite.harbor.ui.Bars
import com.markwhite.harbor.ui.HarborRoot
import com.markwhite.harbor.ui.PendingOpen
import com.markwhite.harbor.ui.theme.HarborTheme
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var pending by mutableStateOf<PendingOpen?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // the panel is dark in every theme: light system bar icons
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        val app = application as HarborApp
        takeIntent(intent)
        setContent {
            HarborTheme {
                HarborRoot(app, pending, onPendingHandled = { pending = null }, onPerformance = ::immersive)
            }
        }
        // keep the screen on while a firmware is docked (setting, on by default)
        lifecycleScope.launch {
            combine(app.harbor.docked, app.settings.keepScreenOn) { d, on -> d != null && on }.collect { on ->
                if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeIntent(intent)
    }

    private fun takeIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) intent.data?.let { pending = PendingOpen(it) }
    }

    /** Hides the system bars the screen asks to (a swipe from the edge brings them back). */
    private fun immersive(bars: Bars) {
        val c = WindowCompat.getInsetsController(window, window.decorView)
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        when (bars) {
            Bars.Shown -> c.show(WindowInsetsCompat.Type.systemBars())
            Bars.NavHidden -> { c.show(WindowInsetsCompat.Type.statusBars()); c.hide(WindowInsetsCompat.Type.navigationBars()) }
            Bars.Hidden -> c.hide(WindowInsetsCompat.Type.systemBars())
        }
    }
}
