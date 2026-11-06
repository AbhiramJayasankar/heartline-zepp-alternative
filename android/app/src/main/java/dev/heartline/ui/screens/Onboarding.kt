package dev.heartline.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import dev.heartline.app.HeartlineController
import dev.heartline.ui.Icons
import dev.heartline.ui.components.ButtonStyle
import dev.heartline.ui.components.MutedText
import dev.heartline.ui.components.PillButton
import dev.heartline.ui.components.paper
import dev.heartline.ui.theme.HL
import dev.heartline.ui.theme.hue

private val PERMISSIONS = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS)
private val ADDRESS = Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}")

/** First run: what Heartline is, the two permissions it needs, then the watch's address and key. */
@Composable
fun OnboardingScreen(controller: HeartlineController) {
    val c = HL.colors
    val ctx = LocalContext.current
    var step by rememberSaveable { mutableIntStateOf(0) }
    fun has(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
    fun granted() = PERMISSIONS.all { has(it) }
    var permsOk by remember { mutableStateOf(granted()) }
    var asked by rememberSaveable { mutableStateOf(false) }
    val askPerms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { asked = true; permsOk = granted(); if (permsOk) step = 2 }
    var mac by rememberSaveable { mutableStateOf("") }
    var key by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().background(c.paper).paper().statusBarsPadding().navigationBarsPadding().padding(Gutter)) {
        // Progress: three segments, filled up to the current step.
        Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = "Step ${step + 1} of 3" }, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(3) { i ->
                val col by animateColorAsState(if (i <= step) c.pulse else c.line, label = "segment")
                Box(Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(col))
            }
        }
        Gap(22)
        // Steps scroll on short screens; the buttons stay at the bottom.
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            when (step) {
                0 -> {
                    Text("Heartline", style = HL.type.hero.copy(fontSize = 84.sp, lineHeight = 80.sp), color = c.ink)
                    PulseTrace(Modifier.fillMaxWidth().height(44.dp))
                    Gap(6)
                    Text("Your Amazfit GTR 2e, without the Zepp app.", style = HL.type.section, color = c.pulse)
                    Gap(20)
                    Feature(Icons.heart, c.pulse, "Heart, measured properly", "All-day heart rate, plus heart checks with HRV, rhythm screening and the raw pulse.")
                    Feature(Icons.sleep, c.hue(dev.heartline.ui.theme.Area.SLEEP), "Sleep in depth", "Stages minute by minute, with heart rate and blood oxygen through the night.")
                    Feature(Icons.activity, c.ok, "Activity and workouts", "Steps, active time, and gym captures with sets, reps and recovery.")
                    Feature(Icons.sparkle, c.warn, "Insights and Alexa", "A daily read of your numbers, and spoken answers from the watch.")
                    Gap(16)
                    MutedText("No account and no cloud: everything stays on this phone. Turn off Bluetooth for the Zepp app first; the watch takes one connection at a time.")
                }
                1 -> {
                    Text("Two permissions", style = HL.type.title, color = c.ink)
                    Gap(6)
                    MutedText("Heartline asks only for what it uses.")
                    Gap(18)
                    PermissionRow(Icons.watch, "Nearby devices", "To find the watch and talk to it over Bluetooth.",
                        has(Manifest.permission.BLUETOOTH_CONNECT) && has(Manifest.permission.BLUETOOTH_SCAN))
                    PermissionRow(Icons.bell, "Notifications", "For the small notice that keeps the connection alive in the background, and for alerts.",
                        has(Manifest.permission.POST_NOTIFICATIONS))
                    if (asked && !permsOk) {
                        Gap(8)
                        Text("Some were refused. Allow them in Android Settings, Apps, Heartline, Permissions, then come back.", style = HL.type.small, color = c.pulse)
                    }
                }
                else -> {
                    Text("Your watch", style = HL.type.title, color = c.ink)
                    Gap(6)
                    MutedText("The watch only talks to apps that know its 32-character auth key. Get it from your Zepp account with huami-token, or from the PC dashboard's watch.json.")
                    Gap(16)
                    // Watches already paired with the phone fill the address in one tap.
                    val paired = remember(permsOk) { pairedWatches(ctx) }
                    paired.filter { it.second != mac }.forEach { (name, address) ->
                        val shape = RoundedCornerShape(16.dp)
                        Row(Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(shape).background(c.surface).border(1.dp, c.line, shape)
                            .clickable(onClickLabel = "Use this watch") { mac = address; error = null }.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(36.dp).clip(CircleShape).background(c.pulse.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                                Icon(Icons.watch, null, tint = c.pulse, modifier = Modifier.size(18.dp))
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(name, style = HL.type.bodyStrong, color = c.ink)
                                Text("Paired with this phone, $address", style = HL.type.small, color = c.inkSoft)
                            }
                            Text("Use", style = HL.type.bodyStrong, color = c.pulse)
                        }
                    }
                    OutlinedTextField(mac, { mac = it.uppercase().take(17); error = null }, label = { Text("Bluetooth address") },
                        placeholder = { Text("C1:2A:3B:4C:5D:6E") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        supportingText = { if (mac.isNotEmpty()) Text(if (ADDRESS.matches(mac)) "Looks right." else "Six pairs of letters and digits, separated by colons.") })
                    Gap(4)
                    val hex = key.count { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
                    OutlinedTextField(
                        key, { key = it.trim().removePrefix("0x").take(32); error = null }, label = { Text("Auth key") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        supportingText = { Text(if (key.isEmpty()) "32 hex characters, masked as you type." else "$hex of 32" + if (hex == 32 && key.length == 32) ", looks right." else "") },
                        // A password field: masked on screen and kept out of the keyboard's learning and suggestions.
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    )
                    error?.let { Gap(6); Text(it, style = HL.type.small, color = c.pulse) }
                    Gap(8)
                    MutedText("The key is stored encrypted with the phone's keystore and never leaves it.")
                }
            }
        }
        Gap(14)
        when (step) {
            0 -> PillButton("Get started", { step = if (permsOk) 2 else 1 }, Modifier.fillMaxWidth())
            1 -> Row(verticalAlignment = Alignment.CenterVertically) {
                PillButton("Back", { step = 0 }, style = ButtonStyle.QUIET)
                Spacer(Modifier.weight(1f))
                PillButton(if (permsOk) "Continue" else "Allow", { if (permsOk) step = 2 else askPerms.launch(PERMISSIONS) })
            }
            else -> Row(verticalAlignment = Alignment.CenterVertically) {
                PillButton("Back", { step = if (permsOk) 0 else 1 }, style = ButtonStyle.QUIET)
                Spacer(Modifier.weight(1f))
                PillButton("Connect", {
                    error = if (!ADDRESS.matches(mac)) "The address is six pairs like C1:2A:3B:4C:5D:6E." else controller.saveWatchKey(mac, key)
                    if (error == null) { controller.finishOnboarding(); controller.connect() }
                }, enabled = mac.isNotEmpty() && key.isNotEmpty())
            }
        }
        Gap(4)
    }
}

/** Amazfit and other Huami watches the phone has already paired with, as (name, address). */
@android.annotation.SuppressLint("MissingPermission")
private fun pairedWatches(ctx: android.content.Context): List<Pair<String, String>> = runCatching {
    val bt = ctx.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter ?: return emptyList()
    bt.bondedDevices.orEmpty().mapNotNull { d ->
        d.name?.takeIf { n -> listOf("amazfit", "gtr", "gts", "bip", "zepp", "mi band").any { it in n.lowercase() } }?.let { it to d.address.uppercase() }
    }
}.getOrDefault(emptyList())

@Composable
private fun Feature(icon: ImageVector, tint: Color, title: String, detail: String) {
    val c = HL.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp).semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.Top) {
        Box(Modifier.size(38.dp).clip(CircleShape).background(tint.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = HL.type.bodyStrong, color = c.ink)
            Text(detail, style = HL.type.small, color = c.inkSoft)
        }
    }
}

@Composable
private fun PermissionRow(icon: ImageVector, title: String, detail: String, ok: Boolean) {
    val c = HL.colors
    val shape = RoundedCornerShape(18.dp)
    Row(Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(shape).background(c.surface).border(1.dp, if (ok) c.ok.copy(alpha = 0.5f) else c.line, shape)
        .semantics(mergeDescendants = true) { contentDescription = "$title, ${if (ok) "allowed" else "not allowed yet"}. $detail" }.padding(16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(c.wash), contentAlignment = Alignment.Center) { Icon(icon, null, tint = c.ink, modifier = Modifier.size(20.dp)) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = HL.type.bodyStrong, color = c.ink)
            Text(detail, style = HL.type.small, color = c.inkSoft)
        }
        Spacer(Modifier.width(8.dp))
        Text(if (ok) "Allowed" else "Needed", style = HL.type.label, color = if (ok) c.ok else c.inkSoft)
    }
}

/** One heartbeat drawn across the page once: the welcome's single moment of motion (skipped when animations are off). */
@Composable
private fun PulseTrace(modifier: Modifier) {
    val c = HL.colors
    val ctx = LocalContext.current
    val still = remember { android.provider.Settings.Global.getFloat(ctx.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
    val progress = remember { Animatable(if (still) 1f else 0f) }
    LaunchedEffect(Unit) { progress.animateTo(1f, tween(1400, easing = LinearEasing)) }
    Canvas(modifier) {
        val w = size.width; val h = size.height; val mid = h * 0.62f
        // A flat line with one P-QRS-T complex about a third of the way along.
        val pts = listOf(0f to 0f, 0.26f to 0f, 0.29f to -0.12f, 0.32f to 0f, 0.35f to 0.12f, 0.38f to -1f, 0.41f to 0.35f, 0.44f to 0f, 0.52f to 0f, 0.56f to -0.2f, 0.60f to 0f, 1f to 0f)
        val path = Path()
        pts.forEachIndexed { i, (x, y) -> val px = x * w; val py = mid + y * h * 0.55f; if (i == 0) path.moveTo(px, py) else path.lineTo(px, py) }
        clipRect(right = w * progress.value) {
            drawPath(path, c.pulse, style = Stroke(2.dp.toPx(), join = StrokeJoin.Round, cap = StrokeCap.Round))
        }
    }
}
