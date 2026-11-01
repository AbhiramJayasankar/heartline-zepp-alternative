package dev.heartline

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.heartline.ui.AppRoot
import dev.heartline.ui.theme.HeartlineTheme
import dev.heartline.ui.theme.ThemeMode

class MainActivity : ComponentActivity() {
    private val callPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        (application as HeartlineApp).realController.callPermissionResult(granted)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as HeartlineApp
        val demo = app.debuggable && intent.getBooleanExtra("demo", false)
        val controller = app.selectController(demo)
        // Only setForwarding (the forwarding screen's call switch) invokes this permission request.
        app.realController.requestCallPermission = { callPermission.launch(android.Manifest.permission.READ_PHONE_STATE) }
        if (!demo) app.realController.startFromActivity(app.debuggable && intent.getBooleanExtra("verify_idle", false))
        setContent {
            val prefs by controller.prefs.collectAsStateWithLifecycle()
            val dark = when (prefs.theme) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            // Status and navigation bar icons follow the app's theme, not the phone's.
            DisposableEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose {}
            }
            HeartlineTheme(prefs.theme) { AppRoot(controller) }
        }
    }
    override fun onDestroy() {
        (application as HeartlineApp).realController.requestCallPermission = null
        super.onDestroy()
    }
}
