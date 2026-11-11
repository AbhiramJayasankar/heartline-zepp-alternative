package dev.heartline.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.heartline.app.RhythmStatus
import dev.heartline.app.SessionUi
import dev.heartline.ui.Navigator
import dev.heartline.ui.Route
import dev.heartline.ui.components.*
import dev.heartline.ui.theme.HL
import kotlin.math.roundToInt

/** How a finding reads: fine, worth a look, or just context. */
enum class Level { OK, LOOK, INFO }
private class Finding(val level: Level, val title: String, val text: String)

/** One reading compared with your own clean checks: average, lowest, highest, how many. */
private class Yours(val avg: Double, val low: Double, val high: Double, val n: Int)
private fun yours(history: List<SessionUi>, pick: (SessionUi) -> Double?): Yours? {
    val v = history.filter { it.signalQuality >= 0.7 }.mapNotNull(pick)
    return if (v.size < 2) null else Yours(v.average(), v.min(), v.max(), v.size)
}

/**
 * Everything below a heart check's charts: what stood out, then each number with what it is, how Heartline works it out,
 * your own range from earlier clean checks, the usual range for adults, and whether it is worth a look.
 * [history] is earlier checks (not this one); [resting] is your recent resting heart rate.
 */
/** What stood out in a check, worst first, for the top of the result page. */
@Composable
fun CheckGlance(s: SessionUi, history: List<SessionUi>, resting: Double?, friend: Boolean = false) {
    val c = HL.colors
    val q = s.signalQuality
    val hrvUsual = yours(history) { it.rmssd }
    val slowBreathing = s.respiratoryRate?.let { it < 9 } == true

    // ---- At a glance ----
    val findings = buildList {
        when {
            q < 0.5 -> add(Finding(Level.LOOK, "Too little clean signal (${pct(q)})", "Fewer than half the beats were clean, so treat every number here as rough. Tighten the strap a notch, rest your arm on a table and keep your hand still."))
            friend -> add(Finding(if (q < 0.7) Level.INFO else Level.OK, (if (q < 0.7) "Usable but noisy signal" else "Clean signal") + " (${pct(q)})",
                if (q < 0.7) "Rhythm was checked, but treat the HRV numbers as rough: they need 70% clean beats." else "Enough clean beats to trust these numbers."))
            q < 0.7 -> add(Finding(Level.INFO, "Usable but noisy signal (${pct(q)})", "Rhythm was checked, but this reading is left out of your HRV trend, which needs 70% clean beats."))
            s.usableS > 0 && s.usableS < 45 -> add(Finding(Level.INFO, "Clean, but short (${s.usableS.roundToInt()} s of usable beats)", "The numbers are fine to read, but your HRV trend and readiness only use checks with 45 s of clean beats, so this one isn't counted there. A 2-minute check leaves room for a few dropped beats."))
            else -> add(Finding(Level.OK, "Clean signal (${pct(q)})", "Enough clean beats to trust these numbers; this check counts toward your HRV trend and readiness."))
        }
        when (s.rhythm) {
            RhythmStatus.REGULAR -> add(Finding(Level.OK, "Regular rhythm", "The beat-to-beat changes followed a steady, breathing-linked pattern."))
            RhythmStatus.IRREGULAR -> add(Finding(Level.LOOK, "Irregular rhythm", s.notes.firstOrNull { !it.contains("diagnosis") } ?: "The timing varied more than usual."))
            RhythmStatus.POSSIBLE_AF -> add(Finding(Level.LOOK, "Signs of possible AFib", "Random, highly irregular timing. Repeat sitting still; if it keeps showing, or you feel palpitations, breathlessness or dizziness, see a doctor for an ECG."))
            RhythmStatus.INSUFFICIENT -> add(Finding(Level.INFO, "Rhythm not judged", "Fewer than 30 clean beat intervals. Measure again sitting still."))
        }
        if (s.hr != null) {
            val hr = s.hr
            when {
                hr < 40 -> add(Finding(Level.LOOK, "Very low heart rate (${hr.roundToInt()} bpm)", "Below 40 at rest is unusual unless you are a trained endurance athlete. If you feel faint or tired, mention it to a doctor."))
                hr > 100 -> add(Finding(Level.LOOK, "High heart rate for rest (${hr.roundToInt()} bpm)", "Above 100 while sitting still. Recent exercise, caffeine, heat, stress or illness raise it; if it is high at rest every time, it is worth a check-up."))
                resting != null && hr - resting > 12 -> add(Finding(Level.INFO, "Not fully at rest", "${hr.roundToInt()} bpm is ${(hr - resting).roundToInt()} above your awake resting rate of ${resting.roundToInt()}. A higher heart rate lowers HRV, so this check may read low. Sit quietly for two minutes before starting next time."))
                else -> add(Finding(Level.OK, "Heart rate ${hr.roundToInt()} bpm", if (resting != null) "Close to your awake resting rate of ${resting.roundToInt()} bpm: a good resting measurement." else "A normal resting rate."))
            }
        }
        if (s.rmssd != null) when {
            friend -> add(Finding(Level.OK, "HRV ${s.rmssd.roundToInt()} ms", "One reading on its own. HRV varies a lot between people, so it says most when compared with the same person's other checks."))
            hrvUsual == null || hrvUsual.n < 3 -> add(Finding(Level.INFO, "HRV ${s.rmssd.roundToInt()} ms: still learning your usual", "Take a check at the same time each day, ideally soon after waking. After three clean ones Heartline can tell you whether a reading is high or low for you."))
            s.rmssd > hrvUsual.avg * 1.25 -> add(Finding(Level.OK, "HRV well above your usual", "${s.rmssd.roundToInt()} ms against your usual ${hrvUsual.avg.roundToInt()}. Usually a sign of good recovery" + if (slowBreathing) ", though slow breathing in this check will have pushed it up too." else "."))
            s.rmssd < hrvUsual.avg * 0.75 -> add(Finding(Level.LOOK, "HRV well below your usual", "${s.rmssd.roundToInt()} ms against your usual ${hrvUsual.avg.roundToInt()}. Common after short sleep, hard training, alcohol, a late meal, stress or the start of an illness. One low check means little; several in a row are worth easing off for."))
            else -> add(Finding(Level.OK, "HRV about your usual", "${s.rmssd.roundToInt()} ms against your usual ${hrvUsual.avg.roundToInt()} (your clean checks range ${hrvUsual.low.roundToInt()}–${hrvUsual.high.roundToInt()})."))
        }
        s.respiratoryRate?.let { r ->
            if (r < 9) add(Finding(Level.INFO, "Slow breathing (${"%.0f".format(r)} a minute)", "If you were breathing slowly on purpose, that is fine, but it lifts HRV (SDNN and RMSSD especially), so compare it only with other slow-breathing checks. If you weren't, the estimate may have latched onto something else."))
            else if (r > 20) add(Finding(Level.INFO, "Fast breathing (${"%.0f".format(r)} a minute)", "Faster than usual for rest. Talking, a recent climb or feeling anxious all speed it up."))
        }
        when {
            s.ectopic >= 3 -> add(Finding(Level.LOOK, "${s.ectopic} extra beats", "Early beats followed by a pause. A few a day are normal; frequent ones, or ones you can feel, are worth mentioning to a doctor."))
            s.ectopic > 0 -> add(Finding(Level.INFO, "${s.ectopic} extra beat${if (s.ectopic > 1) "s" else ""}", "An occasional early beat is common and usually harmless. Movement can also look like one."))
        }
        if (s.lfHf == null && s.durationS < 120) add(Finding(Level.INFO, "No LF/HF in a ${s.durationS.roundToInt()} s check", "The frequency split needs at least two minutes. Pick 2 or 5 min next time if you want it."))
    }
    Heading("At a glance")
    Card(padding = 14.dp) {
        findings.sortedBy { when (it.level) { Level.LOOK -> 0; Level.INFO -> 1; Level.OK -> 2 } }.forEachIndexed { i, f ->
            if (i > 0) HorizontalDivider(color = c.line, modifier = Modifier.padding(vertical = 8.dp))
            Row(Modifier.semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.Top) {
                Box(Modifier.padding(top = 6.dp).size(10.dp).clip(RoundedCornerShape(50)).background(levelColor(f.level)))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(f.title, style = HL.type.bodyStrong, color = c.ink)
                    Text(f.text, style = HL.type.small, color = c.inkSoft)
                }
            }
        }
    }
    val looks = findings.count { it.level == Level.LOOK }
    Gap(8)
    MutedText(if (looks == 0) "Nothing here needs attention. Compare checks with each other, taken the same way at the same time of day." else
        "$looks thing${if (looks > 1) "s" else ""} worth a look. A single check is a snapshot; a pattern across several checks tells you more.")

}

/** Each number of a check explained: what it is, how it's worked out, your range, the typical range, whether it's worth a look. */
@Composable
fun CheckExplained(s: SessionUi, history: List<SessionUi>, resting: Double?, age: Int?, nav: Navigator, friend: Boolean = false) {
    val c = HL.colors
    val q = s.signalQuality
    val hrvUsual = yours(history) { it.rmssd }
    val pnn = s.pnn50?.let { if (it <= 1) it * 100 else it }
    Heading("What each number means")
    Metric("Heart rate", s.hr?.roundToInt()?.toString(), "bpm",
        verdict = s.hr?.let { if (it < 40 || it > 100) Level.LOOK else if (resting != null && it - resting > 12) Level.INFO else Level.OK },
        what = "How many times your heart beat per minute during the check.",
        how = "60 divided by the average time between clean beats. Beats Heartline flags as early or as noise are left out.",
        yours = yours(history) { it.hr }?.let { "Your clean checks: ${it.avg.roundToInt()} bpm on average, ${it.low.roundToInt()}–${it.high.roundToInt()} (${it.n} checks)." }
            ?: resting?.let { "Your awake resting rate lately: ${it.roundToInt()} bpm." },
        typical = "60–100 bpm is the usual adult range at rest; fit people often sit in the 50s or lower. During a check it is normally close to your awake resting rate and a few beats above your sleeping one.",
        worth = "Only if it is below 40 or above 100 while you are truly at rest, or far from your usual without a reason.",
        link = ("Awake resting heart rate, Heart tab" to { openTrend(nav, "awake_rhr") }).takeIf { !friend })
    Metric("HRV (RMSSD)", s.rmssd?.roundToInt()?.toString(), "ms",
        verdict = s.rmssd?.let { v -> hrvUsual?.takeIf { it.n >= 3 }?.let { if (v < it.avg * 0.75) Level.LOOK else Level.OK } ?: Level.INFO },
        what = "Heart rate variability: how much the gap between one beat and the next changes. It mostly reflects the vagus nerve, your " +
            "\"rest and digest\" system. Higher usually means more relaxed and better recovered.",
        how = "For each pair of neighbouring beat gaps, the change in milliseconds is squared; RMSSD is the square root of their average. Only clean beats count (Heartline drops early beats and any gap more than 30% off its neighbours).",
        yours = hrvUsual?.let { "Your clean checks: ${it.avg.roundToInt()} ms on average, ${it.low.roundToInt()}–${it.high.roundToInt()} (${it.n} checks)." },
        typical = "About 19–75 ms in short resting readings from healthy adults (Nunan et al., 2010). It falls with age${age?.let { a -> if (a < 30) ", and people in their 20s usually sit in the upper half" else "" } ?: ""}. Genes make a big difference, so your own trend matters far more than anyone else's number.",
        worth = "When it stays well below your usual for several days, especially with a raised resting heart rate. That combination feeds straight into readiness.",
        link = ("HRV, Heart tab" to { openTrend(nav, "hrv") }).takeIf { !friend })
    Metric("SDNN", s.sdnn?.roundToInt()?.toString(), "ms", verdict = s.sdnn?.let { Level.OK },
        what = "The overall spread of your beat gaps: both quick beat-to-beat changes and slower swings with breathing and posture.",
        how = "The standard deviation of every clean beat gap in the check.",
        yours = yours(history) { it.sdnn }?.let { "Your clean checks: ${it.avg.roundToInt()} ms on average, ${it.low.roundToInt()}–${it.high.roundToInt()}." },
        typical = "About 32–93 ms in short resting readings (Nunan et al., 2010). Longer checks give higher SDNN, so compare checks of the same length.",
        worth = "Rarely on its own. Slow, deep breathing raises it a lot; use RMSSD for day-to-day recovery.")
    Metric("pNN50", pnn?.roundToInt()?.toString(), "%", verdict = pnn?.let { Level.OK },
        what = "The share of neighbouring beat gaps that differ by more than 50 ms. Another measure of the same rest-and-digest activity as RMSSD.",
        how = "Count the beat-to-beat changes larger than 50 ms and divide by all of them.",
        yours = yours(history) { it.pnn50?.let { p -> if (p <= 1) p * 100 else p } }?.let { "Your clean checks: ${it.avg.roundToInt()}% on average, ${it.low.roundToInt()}–${it.high.roundToInt()}%." },
        typical = "No agreed normal range; it can be near 0% in older adults and above 40% in young, fit ones. It moves with RMSSD.",
        worth = "Not separately; if RMSSD looks fine, pNN50 will too.")
    Metric("Stress index", s.stressIndex?.roundToInt()?.toString(), null,
        verdict = s.stressIndex?.let { if (it > 500) Level.LOOK else Level.OK },
        what = "Baevsky's stress index: how tightly your beat gaps cluster. When the \"fight or flight\" side is in charge, the heart beats like a metronome and the index climbs.",
        how = "Heartline sorts the beat gaps into 50 ms bins. Index = (share of beats in the most common bin, %) ÷ (2 × that bin's gap in seconds × the spread from shortest to longest gap in seconds). It needs at least 30 clean beats. This is the original formula, not the square-rooted one some apps show.",
        yours = yours(history) { it.stressIndex }?.let { "Your clean checks: ${it.avg.roundToInt()} on average, ${it.low.roundToInt()}–${it.high.roundToInt()}." },
        typical = "Commonly quoted bands: under 50 very relaxed, 50–150 normal, 150–500 tense or tired, above 500 strong strain.",
        worth = "If it is over 500 at rest, or several times your usual. It is the mirror image of HRV, so they usually agree.")
    Metric("Breathing", s.respiratoryRate?.let { "%.1f".format(it) }, "/min",
        verdict = s.respiratoryRate?.let { if (it < 9 || it > 20) Level.INFO else Level.OK },
        what = "Breaths per minute, estimated from your pulse alone.",
        how = "Each breath nudges the pulse: beat gaps stretch and shrink, and beats get taller and shorter. A slower blood-pressure wave (about 6 a minute, " +
            "the Mayer wave) moves the pulse too, and used to be mistaken for breathing, so Heartline filters it out and looks only between 9 and 30 a minute. " +
            "When beat timing and beat height agree within 3 a minute it averages them; otherwise it takes the peak they share, and reports nothing if neither is clear.",
        yours = yours(history) { it.respiratoryRate }?.let { "Your clean checks: ${"%.1f".format(it.avg)} on average, ${"%.1f".format(it.low)}–${"%.1f".format(it.high)}." },
        typical = "12–20 breaths a minute at rest. Slow breathing on purpose (about 6 a minute) raises HRV a lot, which is why it calms people down.",
        worth = "Only if it is fast at rest most of the time. It is an estimate, usually within 2 or 3 breaths a minute of a hand count; breathing slower than 9 a minute can't be read from the pulse.")
    Metric("LF/HF", s.lfHf?.let { "%.2f".format(it) }, null, verdict = s.lfHf?.let { Level.INFO },
        what = "The balance between slower (0.04–0.15 Hz) and faster (0.15–0.4 Hz, the breathing band) swings in your heart rate.",
        how = "A Lomb-Scargle spectrum of the beat gaps, split into the two bands. It needs at least two minutes of beats" + if (s.durationS < 120) ", so this ${s.durationS.roundToInt()} s check has none." else ".",
        yours = yours(history) { it.lfHf }?.let { "Your clean checks: ${"%.1f".format(it.avg)} on average." },
        typical = "Typically 1.1–11.6, average 2.8 (Nunan et al., 2010). Breathing slower than 9 a minute moves breathing into the low band and inflates it.",
        worth = "Treat it as a curiosity. Researchers no longer agree it measures stress balance; RMSSD is the better recovery signal.")
    Metric("Extra beats", s.ectopic.toString(), null, verdict = when { s.ectopic >= 3 -> Level.LOOK; s.ectopic > 0 -> Level.INFO; else -> Level.OK },
        what = "Early beats: a beat that comes too soon, followed by a longer pause. They are usually felt, if at all, as a skip or a thump.",
        how = "A gap at least 20% shorter than the local median, straight after which comes one at least 10% longer. Heartline leaves these out of the HRV numbers.",
        yours = null,
        typical = "Most people have some every day. A few in a check is common.",
        worth = "If you see three or more in most checks, or you feel them with dizziness, breathlessness or chest discomfort. Then a doctor can check with an ECG.")
    Metric("Signal", pct(q).removeSuffix("%"), "%", verdict = if (q < 0.5) Level.LOOK else if (q < 0.7) Level.INFO else Level.OK,
        what = "How much of the recording Heartline could trust.",
        how = "The share of detected beats whose shape, height and timing passed the checks, with movement from the accelerometer taken into account." +
            if (s.usableS > 0) " This check had ${s.usableS.roundToInt()} s of clean beat intervals out of ${s.durationS.roundToInt()} s." else "",
        yours = yours(history) { it.signalQuality * 100 }?.let { "Your checks: ${it.avg.roundToInt()}% on average." },
        typical = "70% clean and 45 s of clean beats count toward your HRV trend and readiness; 50% is enough to judge rhythm.",
        worth = "Below 50%: measure again with the strap a notch tighter, arm resting on a table, hand relaxed.")
    Gap(4)
    if (!friend) Card(onClick = { nav.push(Route.Readiness("HRV")) }, onClickLabel = "How HRV feeds readiness", padding = 14.dp) {
        Text("How this feeds readiness ›", style = HL.type.bodyStrong, color = c.ink)
        MutedText("Clean checks (70% signal, 45 s of beats) set the day's HRV, which is 35% of readiness. If you take more than one in a day, readiness uses their median. Take them the same way each day, ideally soon after waking.")
    }
}

@Composable
private fun levelColor(l: Level): Color = when (l) { Level.OK -> HL.colors.ok; Level.LOOK -> HL.colors.pulse; Level.INFO -> HL.colors.warn }
private fun pct(q: Double) = "${(q * 100).roundToInt()}%"

@Composable
private fun Metric(name: String, value: String?, unit: String?, verdict: Level?, what: String, how: String, yours: String?, typical: String, worth: String,
                   link: Pair<String, () -> Unit>? = null) {
    val c = HL.colors
    Card(Modifier.padding(bottom = 10.dp), padding = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(name, style = HL.type.label, color = c.inkSoft)
                BigValue(value ?: "–", unit, size = 30)
            }
            Delta(when (verdict) { Level.OK -> "Looks normal"; Level.LOOK -> "Worth a look"; Level.INFO -> "Read the note"; null -> "Not measured" },
                when (verdict) { Level.OK -> true; Level.LOOK -> false; else -> null })
        }
        Gap(10)
        Text(what, style = HL.type.body.copy(fontSize = 15.sp, lineHeight = 22.sp), color = c.ink)
        Part("How it's worked out", how)
        if (yours != null) Part("Your range", yours)
        Part("Typical", typical)
        Part("Worth a look?", worth)
        if (link != null) {
            Gap(8)
            Text(link.first + " ›", style = HL.type.label, color = c.pulse,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickableLink(link.second).padding(vertical = 6.dp))
        }
    }
}

@Composable
private fun Part(title: String, text: String) {
    Gap(8)
    Text(title, style = HL.type.label, color = HL.colors.ink)
    Text(text, style = HL.type.small, color = HL.colors.inkSoft)
}

private fun Modifier.clickableLink(onClick: () -> Unit): Modifier = clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
