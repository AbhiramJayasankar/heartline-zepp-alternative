package dev.heartline

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import kotlinx.coroutines.flow.update
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
    private val callPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        // Ending calls from the watch is optional; seeing that the phone rings is what call alerts need.
        (application as HeartlineApp).realController.callPermissionResult(granted[android.Manifest.permission.READ_PHONE_STATE] == true ||
            checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) == android.content.pm.PackageManager.PERMISSION_GRANTED)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as HeartlineApp
        val demo = app.debuggable && intent.getBooleanExtra("demo", false)
        val controller = app.selectController(demo)
        // Debug only: "demo" plus "onboarding" shows the welcome screens over the demo data, to check their design.
        if (demo && intent.getBooleanExtra("onboarding", false)) (controller as? dev.heartline.app.DemoController)?.prefs?.update { it.copy(onboarded = false) }
        // Only setForwarding (the forwarding screen's call switch) invokes this permission request.
        app.realController.requestCallPermission = { callPermission.launch(arrayOf(android.Manifest.permission.READ_PHONE_STATE, android.Manifest.permission.ANSWER_PHONE_CALLS)) }
        if (!demo) app.realController.startFromActivity(app.debuggable && intent.getBooleanExtra("verify_idle", false))
        setContent {
            val prefs by controller.prefs.collectAsStateWithLifecycle()
            val dark = dev.heartline.ui.theme.paletteFor(prefs.theme, isSystemInDarkTheme()).isDark
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
