package dev.heartline.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import dev.heartline.app.HeartlineController
import dev.heartline.ui.components.ButtonStyle
import dev.heartline.ui.components.MutedText
import dev.heartline.ui.components.PillButton
import dev.heartline.ui.components.paper
import dev.heartline.ui.theme.HL

private val PERMISSIONS = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS)

/** First run: what Heartline is, Bluetooth permission, the watch's key and address, then the tabs. */
@Composable
fun OnboardingScreen(controller: HeartlineController) {
    val c = HL.colors
    val ctx = LocalContext.current
    var step by rememberSaveable { mutableIntStateOf(0) }
    fun granted() = PERMISSIONS.all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }
    var permsOk by remember { mutableStateOf(granted()) }
    val askPerms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permsOk = granted(); if (permsOk) step = 2 }
    var mac by rememberSaveable { mutableStateOf("") }
    var key by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().background(c.paper).paper().statusBarsPadding().navigationBarsPadding().padding(Gutter)) {
        Text("${step + 1} of 3", style = HL.type.label, color = c.inkSoft)
        Gap(18)
        when (step) {
            0 -> {
                Text("Heartline", style = HL.type.hero.copy(fontSize = 84.sp, lineHeight = 80.sp), color = c.ink)
                Gap(8)
                Text("Your Amazfit GTR 2e, without the Zepp app.", style = HL.type.section, color = c.pulse)
                Gap(18)
                Text("Heartline talks to the watch over Bluetooth and keeps everything on this phone: heart rate, sleep, activity, the raw pulse waveform, heart checks with HRV and rhythm screening, and your phone's notifications on your wrist.", style = HL.type.body, color = c.ink)
                Gap(12)
                MutedText("No account and no cloud. Turn your phone's Zepp app off first; the watch accepts one connection at a time.")
                Spacer(Modifier.weight(1f))
                PillButton("Get started", { step = if (permsOk) 2 else 1 }, Modifier.fillMaxWidth())
            }
            1 -> {
                Text("Bluetooth and alerts", style = HL.type.title, color = c.ink)
                Gap(10)
                Text("Heartline needs Bluetooth to find and talk to the watch, and permission to post the small notification that keeps the connection alive in the background.", style = HL.type.body, color = c.ink)
                Spacer(Modifier.weight(1f))
                PillButton(if (permsOk) "Continue" else "Allow", { if (permsOk) step = 2 else askPerms.launch(PERMISSIONS) }, Modifier.fillMaxWidth())
            }
            else -> {
                Text("Your watch", style = HL.type.title, color = c.ink)
                Gap(10)
                Text("The watch only talks to apps that know its 32-character auth key. The PC dashboard project has it in watch.json, or you can fetch it from your Zepp account with huami-token or Watchface Maker.", style = HL.type.body, color = c.ink)
                Gap(16)
                OutlinedTextField(mac, { mac = it.uppercase().take(17) }, label = { Text("Bluetooth address, like C1:0B:05:00:0A:46") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Gap(8)
                OutlinedTextField(
                    key, { key = it.trim().take(34) }, label = { Text("Auth key (32 hex characters)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    // A password field: masked on screen and kept out of the keyboard's learning and suggestions.
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password, autoCorrectEnabled = false),
                )
                error?.let { Gap(6); Text(it, style = HL.type.small, color = c.pulse) }
                Gap(8)
                MutedText("The key is stored encrypted with the phone's keystore and never leaves it.")
                Spacer(Modifier.weight(1f))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PillButton("Back", { step = 1 }, style = ButtonStyle.QUIET)
                    Spacer(Modifier.weight(1f))
                    PillButton("Finish", {
                        val macOk = Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}").matches(mac)
                        error = if (!macOk) "The address is six pairs like C1:0B:05:00:0A:46." else controller.saveWatchKey(mac, key)
                        if (error == null) { controller.finishOnboarding(); controller.connect() }
                    })
                }
            }
        }
        Gap(4)
    }
}
