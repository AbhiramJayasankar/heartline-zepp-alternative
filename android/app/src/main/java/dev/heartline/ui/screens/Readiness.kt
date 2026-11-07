package dev.heartline.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.heartline.app.Factor
import dev.heartline.app.HeartlineController
import dev.heartline.app.Impact
import dev.heartline.app.SleepNightUi
import dev.heartline.app.TrendSeries
import dev.heartline.ui.Navigator
import dev.heartline.ui.Route
import dev.heartline.ui.Tab
import dev.heartline.ui.components.*
import dev.heartline.ui.theme.Area
import dev.heartline.ui.theme.HL
import dev.heartline.ui.theme.hue
import dev.heartline.ui.theme.raised
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.roundToInt

/** What each readiness signal is, why it counts, and where to look at it. Weights are Daily.kt's. */
private class Signal(val name: String, val key: String, val trend: String, val weight: Double, val area: Area,
                     val what: String, val how: String, val missing: String)

private val SIGNALS = listOf(
    Signal("HRV", "hrv", "hrv", 0.35, Area.HEART,
        "Heart rate variability: how much the time between heartbeats varies, measured as RMSSD. When you're rested your nervous system " +
            "lets each beat drift a little; stress, illness, alcohol and hard training make the rhythm stiffer and the number drops.",
        "Today's RMSSD from your heart checks (the GTR 2e keeps no HRV record of its own) against your last 28 days. Higher than usual helps. It is the " +
            "biggest weight because it reacts first, often a day before you feel run down.",
        "Not counted today: it needs an HRV reading today and at least three earlier days with one in the last 28. A one-minute heart check " +
            "after waking, before coffee, gives the cleanest number."),
    Signal("Sleeping heart rate", "rhr", "rhr", 0.30, Area.HEART,
        "Your calmest half hour asleep last night (the lowest 30-minute median). A fitter, rested heart pumps the same blood with fewer beats.",
        "Last night's value against your last 28 nights. Lower than usual helps; a few beats higher often means poor sleep, a late meal, " +
            "alcohol, heat or the start of an illness. The awake resting rate isn't used: it depends too much on when you happened to sit still.",
        "Not counted today: it needs a night with the watch on and at least three earlier nights."),
    Signal("Sleep", "sleep", "sleep_score", 0.25, Area.SLEEP,
        "Last night's sleep score (0 to 100), built from how long you slept, how much of your time in bed was asleep, and your share of deep and REM sleep.",
        "Compared with a fixed 70, a decent night. Every 15 points above or below moves it as much as one standard deviation does for the heart signals.",
        "Not counted today: it needs a finished night with at least an hour asleep. Wear the watch to bed."),
    Signal("Yesterday's strain", "strain", "strain", 0.10, Area.MOVE,
        "How hard your heart worked yesterday, on a 0 to 21 scale.",
        "Only a hard day counts against you: strain above 14 lowers readiness, by one standard deviation's worth for every 4 points over. " +
            "Anything at or below 14 is neutral.",
        "Not counted today: it needs yesterday's heart rate and your maximum heart rate (set your age in Profile)."),
)

private class Band(val low: Int, val high: Int, val word: String, val advice: String)
private val BANDS = listOf(
    Band(80, 100, "Primed", "Well recovered. A good day for a hard workout."),
    Band(65, 79, "Good to go", "Recovered enough for a normal training day."),
    Band(50, 64, "Steady", "Partly recovered. Keep effort moderate and get to bed on time."),
    Band(0, 49, "Go easy", "Your body is under strain. Favour rest, easy movement and an early night."),
)

/**
 * How readiness is worked out, factor by factor, with today's numbers; then sleep score, strain and body battery the same way.
 * [focus] is a factor name or "sleep_score", "strain", "battery": the page scrolls there and outlines it.
 */
@Composable
fun ReadinessScreen(controller: HeartlineController, focus: String?, nav: Navigator) {
    val c = HL.colors
    val t by controller.today.collectAsStateWithLifecycle()
    val trends by controller.trends.collectAsStateWithLifecycle()
    val prefs by controller.prefs.collectAsStateWithLifecycle()
    val scroll = rememberScrollState()
    val tops = remember { mutableStateMapOf<String, Float>() }
    val density = LocalDensity.current
    // Keyed on the anchor appearing, not on its position: the position changes while scrolling and would cancel the scroll.
    val anchored = focus != null && tops.containsKey(focus)
    LaunchedEffect(focus, anchored) {
        if (!anchored) return@LaunchedEffect
        kotlinx.coroutines.delay(150)
        val y = tops[focus] ?: return@LaunchedEffect
        scroll.animateScrollTo((scroll.value + y - with(density) { 96.dp.toPx() }).roundToInt().coerceAtLeast(0))
    }
    fun Modifier.anchor(key: String) = onGloballyPositioned { tops[key] = it.positionInRoot().y }
    fun series(key: String): TrendSeries? = trends?.series?.firstOrNull { it.key == key }

    Screen("Readiness", onBack = { nav.pop() }, scroll = scroll,
        subtitle = "How ready your body is for effort today, from your own recent normal. Here is exactly how it is worked out.") {
        val today = t
        val ready = today?.readiness
        // ---- Today's number ----
        Card {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(ready?.toString() ?: "–", style = HL.type.hero.copy(fontSize = 88.sp, lineHeight = 80.sp), color = c.ink)
                Column(Modifier.padding(start = 12.dp, bottom = 10.dp)) {
                    Text(ready?.let { readinessWord(it) } ?: "Not yet", style = HL.type.section, color = c.ink)
                    Text(today?.readinessLabel ?: "Readiness", style = HL.type.small, color = c.inkSoft)
                }
            }
            Gap(8)
            Text(today?.recommendation ?: "Readiness appears after a night with the watch on, once Heartline has three days to compare with.",
                style = HL.type.body, color = c.ink)
        }

        // ---- The sum ----
        Heading("How today's number adds up")
        val factors = today?.factors.orEmpty()
        if (factors.isEmpty()) {
            Note(if (ready != null) "This is the last day readiness could be worked out (${today.readinessLabel.removePrefix("Readiness, ")}). " +
                "Today's appears after last night's sleep or this morning's resting heart rate syncs."
                else "Nothing to add up yet: readiness needs resting heart rate or sleep, compared with at least three earlier days.")
        } else {
            Card(padding = 14.dp) {
                MutedText("Every day starts at 70, an ordinary day for you. Each signal then adds or takes away points by how far it is " +
                    "from your own usual, weighted by how much it matters.")
                Gap(12)
                SumRow("Starting point", "70", null, null)
                factors.forEach { f -> HorizontalDivider(color = c.line); SumRow(f.name, signed(f.points), f.points, f.impact) }
                HorizontalDivider(color = c.ink.copy(alpha = 0.5f), thickness = 1.5.dp)
                val raw = 70 + factors.sumOf { it.points }
                SumRow("Readiness", ready?.toString() ?: "–", null, null, strong = true)
                if (abs(raw - (ready ?: 0)) > 0.5) MutedText("70 ${factors.joinToString(" ") { signed(it.points) }} = ${"%.1f".format(raw)}, kept between 0 and 100.")
                else MutedText("70 ${factors.joinToString(" ") { signed(it.points) }} = ${"%.1f".format(raw)}, rounded.")
            }
            Gap(10)
            MutedText("The maths: each signal becomes a z-score, how many standard deviations today sits from your last 28 days " +
                "(at least 2 bpm and 10% of your HRV, so a very steady month doesn't exaggerate small changes). Readiness is " +
                "70 + 15 × the weighted average of those z-scores, so a day one standard deviation better on everything scores 85, " +
                "one worse scores 55. Weights: HRV 35%, sleeping heart rate 30%, sleep 25%, yesterday's strain 10%; signals missing " +
                "today are left out and the rest share their weight.")
        }

        // ---- Each signal ----
        Heading("Each signal")
        val totalWeight = factors.sumOf { it.weight }.takeIf { it > 0 } ?: 1.0
        SIGNALS.forEach { s ->
            val f = factors.firstOrNull { it.name == s.name }
            val focused = focus == s.name
            Card(Modifier.padding(bottom = 10.dp).anchor(s.name).then(if (focused) Modifier.border(2.dp, c.pulse, RoundedCornerShape(18.dp)) else Modifier),
                onClick = { openTrend(nav, s.trend) }, onClickLabel = "Open ${s.name}") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AreaLabel(s.name, c.hue(s.area), Modifier.weight(1f))
                    if (f != null) Delta(when (f.impact) { Impact.POSITIVE -> "Helping ${signed(f.points)}"; Impact.NEGATIVE -> "Holding back ${signed(f.points)}"; Impact.NEUTRAL -> "About usual ${signed(f.points)}" },
                        when (f.impact) { Impact.POSITIVE -> true; Impact.NEGATIVE -> false; Impact.NEUTRAL -> null })
                    else Delta("Not counted", null)
                }
                Gap(10)
                if (f != null) {
                    Row(Modifier.fillMaxWidth()) {
                        Fact(if (s.key == "strain") "Yesterday" else if (s.key == "sleep") "Last night" else "Today", f.value?.let { num(it) } ?: "–", Modifier.weight(1f), unit = f.unit.ifEmpty { null })
                        Fact(if (s.key == "sleep" || s.key == "strain") "Compared with" else "Your usual", f.usual?.let { num(it) } ?: "–", Modifier.weight(1f), unit = f.unit.ifEmpty { null })
                        Fact("Share", "${(100 * f.weight / totalWeight).roundToInt()}", Modifier.weight(1f), unit = "%")
                    }
                    Gap(10)
                    ZBar(f.z)
                    Gap(4)
                    Text(if (s.key == "strain" && f.z == 0.0) "At or under 14, so it neither helps nor hurts today." else zWords(f.z, s.key), style = HL.type.small, color = c.inkSoft)
                } else {
                    Text(s.missing, style = HL.type.small, color = c.inkSoft)
                }
                Gap(10)
                Text(s.what, style = HL.type.body.copy(fontSize = 15.sp, lineHeight = 22.sp), color = c.ink)
                Gap(6)
                Text(s.how, style = HL.type.small, color = c.inkSoft)
                series(s.trend)?.points?.takeLast(14)?.takeIf { p -> p.any { it.value != null } }?.let { pts ->
                    Gap(10)
                    DayBars(pts, c.hue(s.area), height = 90.dp, reference = series(s.trend)?.baseline, referenceLabel = "usual", line = s.key == "rhr" || s.key == "hrv")
                }
                Gap(6)
                Text(when (s.trend) { "sleep_score" -> "Open Sleep ›"; "strain" -> "Open Activity ›"; else -> "Open Heart ›" }, style = HL.type.label, color = c.pulse)
            }
        }

        // ---- Bands ----
        Heading("What the number means")
        Card(padding = 14.dp) {
            BANDS.forEachIndexed { i, b ->
                if (i > 0) HorizontalDivider(color = c.line)
                val on = ready != null && ready in b.low..b.high
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.Top) {
                    Box(Modifier.width(64.dp)) {
                        Text("${b.low}–${b.high}", style = HL.type.bodyStrong, color = if (on) c.pulse else c.ink)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(b.word + if (on) "  · today" else "", style = HL.type.bodyStrong, color = if (on) c.pulse else c.ink)
                        Text(b.advice, style = HL.type.small, color = c.inkSoft)
                    }
                }
            }
        }

        series("readiness")?.points?.takeIf { p -> p.count { it.value != null } >= 2 }?.let { pts ->
            Heading("Last ${min(pts.size, 14)} days")
            Card(padding = 14.dp) {
                DayBars(pts.takeLast(14), c.ink, height = 130.dp, reference = 70.0, referenceLabel = "ordinary day")
                Gap(6)
                MutedText("Touch a day for its value. A run of days below 70 is worth more attention than any single low one.")
                series("readiness")?.let { TrendExtras(it, showAbout = false) }
            }
        }

        // ---- The other Today scores ----
        Heading("The other scores on Today")
        val night = today?.lastNight
        Card(Modifier.padding(bottom = 10.dp).anchor("sleep_score").then(if (focus == "sleep_score") Modifier.border(2.dp, c.pulse, RoundedCornerShape(18.dp)) else Modifier),
            onClick = { nav.toTab(Tab.SLEEP) }, onClickLabel = "Open Sleep") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AreaLabel("Sleep score", c.hue(Area.SLEEP), Modifier.weight(1f))
                Text(today?.sleepScore?.toString() ?: "–", style = HL.type.stat.copy(fontSize = 24.sp), color = c.ink)
            }
            Gap(8)
            Text("Out of 100, from four parts of last night:", style = HL.type.body.copy(fontSize = 15.sp), color = c.ink)
            Gap(8)
            sleepParts(night).forEach { (label, got, max, note) -> PartRow(label, got, max, note) }
            Gap(6)
            MutedText(if (night?.ongoing == true) "Last night may still be going on, so this can change at the next sync." else "Open Sleep for the stages minute by minute.")
        }
        Card(Modifier.padding(bottom = 10.dp).anchor("strain").then(if (focus == "strain") Modifier.border(2.dp, c.pulse, RoundedCornerShape(18.dp)) else Modifier),
            onClick = { nav.toTab(Tab.ACTIVITY) }, onClickLabel = "Open Activity") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AreaLabel("Strain", c.pulse, Modifier.weight(1f))
                Text(today?.strain?.let { "%.1f".format(it) } ?: "–", style = HL.type.stat.copy(fontSize = 24.sp), color = c.ink)
            }
            Gap(8)
            Text("How hard your heart has worked today, from 0 to 21.", style = HL.type.body.copy(fontSize = 15.sp), color = c.ink)
            Gap(6)
            val hrMax = prefs.profile.maxHr ?: prefs.profile.age?.let { (208 - 0.7 * it).roundToInt() }
            MutedText("Every awake minute at or above half your maximum heart rate${hrMax?.let { " (${it / 2} bpm of your $it)" } ?: ""} adds load, " +
                "growing steeply as you near your maximum (Banister's TRIMP, with your resting rate as the floor). The total is squeezed onto 0–21: " +
                "strain = 21 × (1 − e^(−load ÷ 120)), so the first hour of effort counts most and 21 is never quite reached.")
            Gap(8)
            Row(Modifier.fillMaxWidth()) {
                listOf("0–7" to "Light day", "8–13" to "Moderate", "14–17" to "Hard", "18–21" to "All out").forEach { (r, w) -> Fact(w, r, Modifier.weight(1f)) }
            }
            Gap(6)
            MutedText(if (hrMax == null) "Set your age in Profile so Heartline knows your maximum heart rate; strain needs it." else "Above 14 lowers tomorrow's readiness. Open Activity for today's heart rate zones.")
        }
        Card(Modifier.padding(bottom = 10.dp).anchor("battery").then(if (focus == "battery") Modifier.border(2.dp, c.pulse, RoundedCornerShape(18.dp)) else Modifier)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AreaLabel("Body battery", c.ok, Modifier.weight(1f))
                Text(today?.bodyBattery?.toString() ?: "–", style = HL.type.stat.copy(fontSize = 24.sp), color = c.ink)
            }
            Gap(8)
            Text("Energy left today, from 0 to 100.", style = HL.type.body.copy(fontSize = 15.sp), color = c.ink)
            Gap(6)
            val start = today?.sleepScore?.let { min(100.0, 20 + 0.8 * it).roundToInt() }
            MutedText("It wakes up with you at 20 + 0.8 × your sleep score${start?.let { " ($it this morning)" } ?: ""}. Each awake minute drains 0.06, more when your heart " +
                "rate is up: 0.05 extra in zone 1 rising to 0.50 in zone 5. A nap charges 0.15 a minute. So a calm desk day costs about 1 point an hour " +
                "and a hard hour of training 20 or more.")
            if (today?.bodyBattery == null) { Gap(6); MutedText("It starts after a night's sleep with the watch on.") }
        }
        Gap(4)
        MutedText("These are Heartline's own estimates from your watch's data, made for comparing you with yourself. They are not medical measurements.")
    }
}

@Composable
private fun SumRow(label: String, value: String, points: Double?, impact: Impact?, strong: Boolean = false) {
    val c = HL.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 9.dp).semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = if (strong) HL.type.bodyStrong else HL.type.body, color = c.ink, modifier = Modifier.weight(1f))
        if (points != null) {
            // A diverging bar: ±6 points (about the most one signal moves a day) fills one side.
            Box(Modifier.width(110.dp).height(14.dp)) {
                Box(Modifier.align(Alignment.Center).fillMaxWidth().height(2.dp).background(c.line))
                Box(Modifier.align(Alignment.Center).width(2.dp).height(14.dp).background(c.inkSoft))
                val frac = (abs(points) / 6.0).coerceIn(0.0, 1.0).toFloat()
                val color = when (impact) { Impact.POSITIVE -> c.ok; Impact.NEGATIVE -> c.pulse; else -> c.inkSoft }
                Box(Modifier.fillMaxWidth(0.5f).align(if (points >= 0) Alignment.CenterEnd else Alignment.CenterStart)) {
                    Box(Modifier.fillMaxWidth(frac).height(10.dp).clip(RoundedCornerShape(5.dp)).background(color)
                        .align(if (points >= 0) Alignment.CenterStart else Alignment.CenterEnd))
                }
            }
            Spacer(Modifier.width(12.dp))
        }
        Text(value, style = if (strong) HL.type.stat.copy(fontSize = 26.sp) else HL.type.bodyStrong, color = c.ink, modifier = Modifier.widthIn(min = 44.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.End)
    }
}

/** Where today's z-score sits on −3…+3. */
@Composable
private fun ZBar(z: Double) {
    val c = HL.colors
    val color = when { z > 0.3 -> c.ok; z < -0.3 -> c.pulse; else -> c.inkSoft }
    BoxWithConstraints(Modifier.fillMaxWidth().height(14.dp)) {
        val w = maxWidth
        Box(Modifier.align(Alignment.Center).fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(c.line))
        Box(Modifier.align(Alignment.Center).width(1.dp).height(14.dp).background(c.inkSoft))
        val x = ((z.coerceIn(-3.0, 3.0) + 3) / 6).toFloat()
        Box(Modifier.offset(x = w * x - 7.dp).size(14.dp).clip(RoundedCornerShape(50)).background(color).border(2.dp, c.surface, RoundedCornerShape(50)))
    }
    Row(Modifier.fillMaxWidth()) {
        Text("worse", style = HL.type.small.copy(fontSize = 11.sp), color = c.inkSoft, modifier = Modifier.weight(1f))
        Text("your usual", style = HL.type.small.copy(fontSize = 11.sp), color = c.inkSoft)
        Text("better", style = HL.type.small.copy(fontSize = 11.sp), color = c.inkSoft, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
    }
}

@Composable
private fun PartRow(label: String, got: Double, max: Int, note: String) {
    val c = HL.colors
    Column(Modifier.padding(vertical = 5.dp).semantics(mergeDescendants = true) {}) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = HL.type.bodyStrong.copy(fontSize = 14.sp), color = c.ink, modifier = Modifier.weight(1f))
            Text("${"%.1f".format(got)} of $max", style = HL.type.label, color = c.ink)
        }
        Gap(4)
        Meter((got / max).toFloat(), c.hue(Area.SLEEP), height = 6.dp)
        Text(note, style = HL.type.small.copy(fontSize = 12.sp), color = c.inkSoft, modifier = Modifier.padding(top = 3.dp))
    }
}

/** DailySleep.sleepScore's four parts for [n]: label, points earned, points possible, how it was earned. */
private fun sleepParts(n: SleepNightUi?): List<Quad> {
    if (n == null) return listOf(
        Quad("Time asleep", 0.0, 50, "Full marks at 8 hours."), Quad("Efficiency", 0.0, 20, "Asleep for 95% of the time in bed earns all 20; 70% earns none."),
        Quad("Deep sleep", 0.0, 15, "Full marks at 13% of sleep."), Quad("REM sleep", 0.0, 15, "Full marks at 20% of sleep."))
    val asleep = n.deepMin + n.lightMin + n.remMin
    val eff = 100.0 * asleep / ((n.end - n.start) / 60.0 + 1)
    val deepShare = if (asleep > 0) n.deepMin.toDouble() / asleep else 0.0
    val remShare = if (asleep > 0) n.remMin.toDouble() / asleep else 0.0
    return listOf(
        Quad("Time asleep", 50 * min(1.0, asleep / 480.0), 50, "${hm(asleep)} of the 8 hours that earn full marks."),
        Quad("Efficiency", 20 * ((eff - 70) / 25).coerceIn(0.0, 1.0), 20, "Asleep ${"%.0f".format(eff)}% of the time in bed; 95% earns all 20, 70% none."),
        Quad("Deep sleep", 15 * min(1.0, deepShare / 0.13), 15, "${hm(n.deepMin)}, ${"%.0f".format(deepShare * 100)}% of sleep; full marks at 13%."),
        Quad("REM sleep", 15 * min(1.0, remShare / 0.20), 15, "${hm(n.remMin)}, ${"%.0f".format(remShare * 100)}% of sleep; full marks at 20%."),
    )
}
private data class Quad(val a: String, val b: Double, val c: Int, val d: String)

private fun signed(p: Double): String = if (p >= 0) "+%.1f".format(p) else "−%.1f".format(-p)
private fun num(v: Double): String = if (v % 1.0 == 0.0 || v >= 100) "%.0f".format(v) else "%.1f".format(v)

private fun zWords(z: Double, key: String): String {
    val size = abs(z)
    val how = when { size < 0.3 -> "right at"; size < 1 -> "a little"; size < 2 -> "clearly"; else -> "well" }
    if (size < 0.3) return "Right at your usual (z ${"%+.1f".format(z)})."
    val dir = if (z > 0) "better than" else "worse than"
    val unit = if (key == "sleep" || key == "strain") "" else " your usual"
    return "${how.replaceFirstChar { it.uppercase() }} $dir${unit.ifEmpty { " an ordinary day" }}: ${"%.1f".format(size)} standard deviations (z ${"%+.1f".format(z)})."
}
