package dev.heartline.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.heartline.app.HeartlineController
import dev.heartline.app.LinkState
import dev.heartline.ui.Navigator
import dev.heartline.ui.components.ButtonStyle
import dev.heartline.ui.components.MutedText
import dev.heartline.ui.components.Panel
import dev.heartline.ui.components.PillButton
import dev.heartline.ui.components.SectionTitle
import dev.heartline.ui.theme.HL
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * GPS assist: the week of satellite predictions the watch holds, drawn as the week it covers, and the Zepp sign-in
 * that fetching it needs.
 */
@Composable
fun AgpsScreen(controller: HeartlineController, nav: Navigator) {
    val c = HL.colors
    val s by controller.agps.collectAsStateWithLifecycle()
    val conn by controller.connection.collectAsStateWithLifecycle()
    val connected = conn.state == LinkState.CONNECTED
    val now = System.currentTimeMillis() / 1000
    val zone = ZoneId.systemDefault()
    val until = s.validUntil
    val current = until != null && until > now
    Screen("GPS assist", onBack = { nav.pop() },
        subtitle = "A week of satellite positions on the watch, so its GPS finds you in seconds instead of minutes when a run starts.") {
        // ---- The week the watch's data covers ----
        val dayFmt = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ENGLISH)
        Text(when {
            s.running -> "Updating…"
            until == null -> "Not sent yet"
            current -> "Current until " + Instant.ofEpochSecond(until).atZone(zone).format(dayFmt)
            else -> "Out of date since " + Instant.ofEpochSecond(until).atZone(zone).format(dayFmt)
        }, style = HL.type.section, color = if (current || s.running) c.ink else c.warn)
        Gap(12)
        WeekStrip(s.lastUpdate, now, zone)
        Gap(8)
        MutedText(when {
            s.running -> s.message ?: "Working…"
            s.lastUpdate != null -> "Sent to the watch ${agoOf(s.lastUpdate!!)}. The predictions are exact for the first days and drift after a week."
            else -> "Without it, the watch works out where the satellites are from their own slow broadcast, which can take several minutes outdoors."
        })
        if (s.running && s.progress != null) {
            Gap(10)
            Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(c.line)) {
                Box(Modifier.fillMaxWidth(s.progress!! / 100f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(c.oxygen))
            }
        }
        if (!s.running && s.failed && s.message != null) { Gap(8); Text(s.message!!, style = HL.type.small, color = c.pulse) }
        Gap(24)
        if (s.signedInAs == null) {
            // Nothing can be updated before the sign-in, so it takes the place of the controls.
            SectionTitle("Sign in to Zepp")
            Gap(4)
            ZeppSignIn(controller)
        } else {
            PillButton(if (s.running) "Updating…" else "Update now", { controller.updateAgps() }, Modifier.fillMaxWidth(),
                style = ButtonStyle.PULSE, enabled = !s.running && connected)
            if (!connected) { Gap(6); MutedText("Connect the watch first.") }
            Gap(12)
            ToggleRow("Update by itself", "Every 3 days while the watch is connected, never during a recording or heart check.", s.auto,
                enabled = true) { controller.setAgpsAuto(it) }
            Gap(24)
            SectionTitle("Zepp account")
            Gap(4)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(s.signedInAs!!, style = HL.type.bodyStrong, color = c.ink)
                    MutedText("Signed in. Only the sign-in token is kept, encrypted on this phone.")
                }
                TextButton({ controller.zeppSignOut() }) { Text("Sign out", style = HL.type.label, color = c.pulse) }
            }
        }

        Gap(24)
        Panel {
            Text("Where it comes from", style = HL.type.bodyStrong, color = c.ink)
            Gap(6)
            MutedText("Zepp publishes the data each day for its watches and only hands it to a signed-in account. Heartline downloads the " +
                "almanacs and the week's orbit predictions, packs them the way the watch expects and sends them over Bluetooth, " +
                "as Zepp does. It goes to the watch's GPS, nowhere else.")
        }
        Gap(24)
    }
}

/** Seven days from the last update, filled while they are still ahead; today is outlined. */
@Composable
private fun WeekStrip(last: Long?, now: Long, zone: ZoneId) {
    val c = HL.colors
    val start = last?.let { Instant.ofEpochSecond(it).atZone(zone).toLocalDate() } ?: LocalDate.now(zone)
    val today = Instant.ofEpochSecond(now).atZone(zone).toLocalDate()
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
        contentDescription = if (last == null) "Heartline hasn't sent satellite data yet" else "Satellite data covers ${start.format(DateTimeFormatter.ofPattern("d MMMM", Locale.ENGLISH))} to ${start.plusDays(6).format(DateTimeFormatter.ofPattern("d MMMM", Locale.ENGLISH))}"
    }, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (i in 0 until 7) {
            val d = start.plusDays(i.toLong())
            val covered = last != null && !d.isBefore(today)
            val past = last != null && d.isBefore(today)
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.fillMaxWidth().height(28.dp).clip(RoundedCornerShape(6.dp)).background(when {
                    covered -> c.oxygen
                    past -> c.oxygen.copy(alpha = 0.25f)
                    else -> c.line
                }).then(if (d == today) Modifier.border(2.dp, c.ink, RoundedCornerShape(6.dp)) else Modifier))
                Spacer(Modifier.height(4.dp))
                Text(d.format(DateTimeFormatter.ofPattern("EEE", Locale.ENGLISH)), style = HL.type.label,
                    color = if (d == today) c.ink else c.inkSoft)
            }
        }
    }
}

@Composable
private fun ZeppSignIn(controller: HeartlineController) {
    val c = HL.colors
    val scope = rememberCoroutineScope()
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    var opening by remember { mutableStateOf(false) }
    MutedText("Zepp only hands the data to its own accounts. Sign in on Zepp's page in your browser, where Google is usually " +
        "signed in already; the page hands the sign-in back to Heartline.")
    Gap(12)
    PillButton(if (opening) "Opening…" else "Sign in with your browser", {
        opening = true; error = null
        scope.launch {
            runCatching { controller.zeppBrowserSignIn()?.let { openInBrowserTab(context, it) } }.onFailure { error = it.message ?: "Couldn't open the browser" }
            opening = false
        }
    }, Modifier.fillMaxWidth(), style = ButtonStyle.PULSE, enabled = !opening)
    Gap(20)
    Text("Or with an email and password", style = HL.type.bodyStrong, color = c.ink)
    Gap(2)
    MutedText("Your password goes to Zepp once and isn't saved.")
    Gap(10)
    OutlinedTextField(email, { email = it; error = null }, label = { Text("Zepp email") }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())
    Gap(8)
    OutlinedTextField(password, { password = it; error = null }, label = { Text("Password") }, singleLine = true,
        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth())
    error?.let { Gap(6); Text(it, style = HL.type.small, color = c.pulse) }
    Gap(10)
    PillButton(if (busy) "Signing in…" else "Sign in", {
        busy = true; error = null
        scope.launch {
            runCatching { controller.zeppSignIn(email, password) }
                .onSuccess { password = "" }.onFailure { error = it.message ?: "Sign-in failed" }
            busy = false
        }
    }, enabled = !busy && email.contains('@') && password.isNotEmpty())
}

/** A Chrome Custom Tab when the browser has them (the browser's own sign-ins, Google included), else the browser. */
private fun openInBrowserTab(context: android.content.Context, url: String) {
    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
        .putExtras(android.os.Bundle().apply { putBinder("android.support.customtabs.extra.SESSION", null) })
        .putExtra("android.support.customtabs.extra.TITLE_VISIBILITY", 1)
    if (context !is android.app.Activity) intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
}
