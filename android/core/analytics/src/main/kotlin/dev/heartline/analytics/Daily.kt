package dev.heartline.analytics

import java.time.*
import kotlin.math.*

/** InsightsDB.health_by_day: minimum resting HR, arithmetic mean for every other series. */
fun healthByDay(
    health: List<HealthValue>, since: Long = Long.MIN_VALUE, zone: ZoneId = ZoneId.systemDefault(),
): Map<String, Map<LocalDate, Double>> {
    class Aggregate(var sum: Double = 0.0, var count: Int = 0, var min: Double = Double.POSITIVE_INFINITY)
    val groups = linkedMapOf<String, MutableMap<LocalDate, Aggregate>>()
    for (v in health) if (v.ts >= since) {
        val g = groups.getOrPut(v.type) { linkedMapOf() }.getOrPut(dayOf(v.ts, zone)) { Aggregate() }
        g.sum += v.value; g.count++; g.min = min(g.min, v.value)
    }
    return groups.mapValues { (type, days) -> days.mapValues { (_, g) ->
        rounded(if (type == "resting_hr") g.min else g.sum / g.count, 1)
    } }
}

/** Health type of the automatic daytime HRV samples (OvernightHrv.DAY_SAMPLE); only clean ones carry a value. */
const val DAY_HRV_SAMPLE = "hrv_day_sample"

/** Awake HRV per day: the median RMSSD of the day's clean heart checks and clean automatic daytime samples. */
fun hrvByDay(checks: List<CheckSummary>, zone: ZoneId = ZoneId.systemDefault(), daySamples: List<HealthValue> = emptyList()): Map<LocalDate, Double> =
    (checks.filter { it.rmssdMs != null && it.signalQuality >= 0.7 && it.usableS >= 45 }
        .map { Instant.ofEpochSecond(floor(it.start).toLong()).atZone(zone).toLocalDate() to it.rmssdMs!! } +
        daySamples.filter { it.value > 0 }.map { dayOf(it.ts, zone) to it.value })
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, values) -> rounded(median(values.toDoubleArray()), 1) }

/**
 * HRV is compared on a log scale, ln(RMSSD), as in the training literature (Plews, Buchheit): readings skew high, so one
 * high morning would stretch a plain average and its spread. The spread is at least 0.1, about 10%.
 */
internal const val HRV_LN_FLOOR = 0.1
/** How far one signal can move readiness: z-scores beyond ±2 count as 2. */
internal const val Z_CAP = 2.0

/** Overnight HRV against earlier nights: z on the log scale, the usual as a geometric mean, and how many nights it rests on. */
internal data class NightHrv(val value: Double, val usual: Double, val z: Double, val nights: Int)
internal fun nightHrv(value: Double?, base: List<Double>): NightHrv? {
    if (value == null || value <= 0 || base.isEmpty()) return null
    val logs = base.map { ln(it) }
    val mu = average(logs)
    val sd = if (logs.size >= 2) sqrt(variance(logs.toDoubleArray())) else 0.0
    return NightHrv(value, exp(mu), (ln(value) - mu) / max(sd, HRV_LN_FLOOR), base.size)
}
/** Overnight HRV and readiness: full weight once three earlier nights set the usual, a third of it per night before. */
internal fun nightShare(nights: Int): Double = min(1.0, nights / 3.0)

private data class DayWork(var row: DayResult, val awakeHr: List<Int>, val factors: MutableList<ReadinessFactor> = arrayListOf(), var sleepBase: Int? = null)
private fun nonzero(v: Double?): Boolean = v != null && v != 0.0
private fun average(values: List<Double>): Double = values.sum() / values.size
private fun zScore(value: Double?, base: List<Double>, floor: Double): Double? {
    if (value == null || base.size < 3) return null
    return (value - average(base)) / max(sqrt(variance(base.toDoubleArray())), floor)
}

/** Inputs are in timestamp order, with states and valid HR prepared by the data layer (insights.py).
 * The supplied now's zone replaces Python's implicit process-local timezone. */
fun computeDaily(
    minutes: List<MinuteSample>, health: List<HealthValue>, checks: List<CheckSummary>,
    profile: Profile, days: Int, now: ZonedDateTime,
    /** Python's daily.night_for left out a night that may still be going on; Heartline shows it, flagged [DayResult.sleepOngoing]. */
    holdOngoingNights: Boolean = false,
    /** Recorded workouts as (start, end) seconds; their minutes count as active while heart rate or steps show work. */
    workouts: List<Pair<Long, Long>> = emptyList(),
): DailyResult {
    require(days > 0)
    val today = now.toLocalDate()
    val zone = now.zone
    val hrMax = hrMaxOf(profile)
    val since = today.minusDays(max(days, 35).toLong() + 1).atStartOfDay(zone).toEpochSecond()
    val ownHealth = healthByDay(health, since, zone)
    val hrv = hrvByDay(checks, zone, health.filter { it.type == DAY_HRV_SAMPLE && it.ts >= since })
    fun nightBase(d: LocalDate) = (1..28).mapNotNull { ownHealth["hrv_sleep"]?.get(d.minusDays(it.toLong()))?.takeIf { v -> v > 0 } }
    val byDay = minutes.groupBy { dayOf(it.ts, zone) }
    val blocks = sleepBlocks(minutes)
    val naps = napsOf(blocks, zone)
    val first = byDay.keys.minOrNull() ?: today
    val rows = sortedMapOf<LocalDate, DayWork>()
    for (i in max(days, 35) - 1 downTo 0) {
        val d = today.minusDays(i.toLong())
        if (d < first.minusDays(1)) continue
        val ms = byDay[d].orEmpty()
        val (main, asleep, ongoing) = nightFor(blocks, d, minutes.lastOrNull()?.ts, zone).let { if (holdOngoingNights && it.ongoing) Night(null, 0) else it }
        val eff = main?.let { rounded(100 * it.asleep / ((it.end - it.start) / 60.0 + 1), 1) }
        val resting = ownHealth["resting_hr"]?.get(d) ?: main?.let { restingHrFrom(it.rows) }
        val awakeHr = ms.filter { it.hr != null && it.hr != 0 && it.state !in sleepStates }.map { it.hr!! }
        // Sleep score: duration, efficiency, deep and REM, plus up to 10 points for overnight HRV against your earlier
        // nights. With HRV the other parts are scaled into the remaining 90, so a night without it scores as before.
        val baseExact = main?.let { sleepScoreExact(it.asleep, eff ?: 0.0, it.deep, it.rem) }
        val hrvPart = if (baseExact != null && baseExact > 0) nightHrv(ownHealth["hrv_sleep"]?.get(d), nightBase(d))?.let { h ->
            val possible = 10 * nightShare(h.nights)
            SleepHrvPart(h.value, rounded(h.usual, 1), rounded(h.z, 2), h.nights, rounded(possible * (1 + h.z / 2).coerceIn(0.0, 1.0), 2), rounded(possible, 2))
        } else null
        rows[d] = DayWork(DayResult(
            date = d.toString(), restingHr = resting, hrvRmssd = hrv[d] ?: ownHealth["hrv"]?.get(d),
            sleepMinutes = main?.let { asleep }, sleepEfficiency = eff, deepMin = main?.deep,
            lightMin = main?.light, remMin = main?.rem, awakeMin = main?.awake,
            sleepScore = baseExact?.let { b -> hrvPart?.let { h -> rounded(b * (1 - h.possible / 100) + h.points).toInt() } ?: rounded(b).toInt() },
            steps = ms.takeIf { it.isNotEmpty() }?.sumOf { it.steps },
            activeMinutes = null, // set below, once resting heart rate is known
            wornMinutes = ms.takeIf { it.isNotEmpty() }?.count { it.state !in noDataStates },
            strain = null, trimp = null, hrZones = hrMax?.takeIf { it != 0 && awakeHr.isNotEmpty() }?.let { zoneMinutes(awakeHr, it) },
            spo2Avg = ownHealth["spo2"]?.get(d), stressAvg = ownHealth["stress"]?.get(d),
            readiness = null, caloriesActive = null, sleepStart = main?.start, sleepEnd = main?.end, sleepOngoing = ongoing,
            awakeRestingHr = awakeRestingHrFrom(ms), hrvSleep = ownHealth["hrv_sleep"]?.get(d), naps = naps[d].orEmpty(), sleepHrv = hrvPart,
        ), awakeHr, sleepBase = baseExact?.let { rounded(it).toInt() })
    }
    // Heart rate reserve wants resting heart rate awake (Karvonen): the profile's, else the median of each day's calmest
    // still, awake 10 minutes. Sleeping heart rate runs lower, so it is only a fallback, raised by 8 bpm.
    val restingValues = rows.values.mapNotNull { it.row.restingHr?.takeIf { v -> v != 0.0 } }
    val awakeValues = rows.values.mapNotNull { it.row.awakeRestingHr?.takeIf { v -> v != 0.0 } }
    val hrRest = profile.restingHr?.takeIf { it != 0 }?.toDouble()
        ?: awakeValues.takeIf { it.isNotEmpty() }?.let { median(it.toDoubleArray()) }
        ?: restingValues.takeIf { it.isNotEmpty() }?.let { median(it.toDoubleArray()) + 8 }
    for ((d, work) in rows) byDay[d]?.takeIf { it.isNotEmpty() }?.let { ms ->
        work.row = work.row.copy(activeMinutes = activeMinutesOf(ms, hrMax, hrRest, workouts))
    }
    for (work in rows.values) if (hrMax != null && hrMax != 0 && nonzero(hrRest) && work.awakeHr.isNotEmpty()) {
        val trimp = banisterTrimp(work.awakeHr, hrRest!!, hrMax, profile.sex)
        work.row = work.row.copy(trimp = rounded(trimp, 1), strain = rounded(21 * (1 - exp(-trimp / 120)), 1),
            caloriesActive = keytelKcal(work.awakeHr, profile, hrMax))
    }
    for ((d, work) in rows) {
        val row = work.row
        val prev = rows.filterKeys { it >= d.minusDays(28) && it < d }.values.map { it.row }
        val zs = arrayListOf<Pair<Double, Double>>()
        fun factor(name: String, zRaw: Double, weight: Double, detail: String, value: Double? = null, usual: Double? = null, unit: String = "") {
            val z = zRaw.coerceIn(-Z_CAP, Z_CAP)
            zs.add(z to weight)
            work.factors.add(ReadinessFactor(name, if (z > 0.3) "positive" else if (z < -0.3) "negative" else "neutral", detail, z, weight,
                value = value, usual = usual, unit = unit))
        }
        val baseRest = prev.mapNotNull { it.restingHr?.takeIf { v -> v != 0.0 } }
        val rhrText = row.restingHr?.let { if (ownHealth["resting_hr"]?.containsKey(d) == true) it.toString() else it.toLong().toString() }
        zScore(row.restingHr, baseRest, 2.0)?.let { z -> factor("Resting heart rate", -z, 0.30,
            "$rhrText bpm vs your usual ${rounded(average(baseRest)).toLong()}", row.restingHr, average(baseRest), "bpm") }
        // Overnight HRV comes first, from the second night on: asleep HRV runs higher than awake, so each is only ever
        // compared with itself. Its weight grows to the full 0.35 over the first three earlier nights. Without it, the
        // day's awake HRV (heart checks and automatic daytime samples) against earlier days counts instead.
        val night = nightHrv(row.hrvSleep, nightBase(d))
        if (night != null) factor("HRV", night.z, 0.35 * nightShare(night.nights),
            "Overnight RMSSD ${rounded(night.value).toLong()} ms vs your usual ${rounded(night.usual).toLong()}" +
                (if (night.nights < 3) " from ${night.nights} earlier night${if (night.nights == 1) "" else "s"}, so it counts for less until there are 3" else ""),
            night.value, night.usual, "ms")
        val baseHrv = prev.mapNotNull { it.hrvRmssd?.takeIf { v -> v > 0 } }.map { ln(it) }
        if (night == null) zScore(row.hrvRmssd?.takeIf { it > 0 }?.let { ln(it) }, baseHrv, HRV_LN_FLOOR)?.let { z ->
            val usual = exp(average(baseHrv))
            factor("HRV", z, 0.35, "Awake RMSSD ${rounded(row.hrvRmssd!!).toLong()} ms vs your usual ${rounded(usual).toLong()}", row.hrvRmssd, usual, "ms")
        }
        // The sleep score's HRV part is left out here: HRV already counts on its own.
        if (row.sleepScore != null) work.sleepBase?.let { score -> factor("Sleep", (score - 70) / 15.0, 0.25,
            "sleep score $score" + (if (row.sleepHrv != null) " without its HRV part" else "") +
                ", ${row.sleepMinutes!! / 60} h ${row.sleepMinutes % 60} min asleep", score.toDouble(), 70.0) }
        rows[d.minusDays(1)]?.row?.strain?.let { strain -> factor("Yesterday's strain",
            if (strain > 14) -(strain - 14) / 4 else 0.0, 0.10, "strain $strain", strain, 14.0) }
        // Each factor's share of the score: readiness = 70 + the sum of these points.
        val totalWeight = zs.sumOf { it.second }
        if (totalWeight > 0) work.factors.replaceAll { it.copy(points = 15 * it.z * it.weight / totalWeight) }
        if (zs.isNotEmpty() && work.factors.any { it.name == "Resting heart rate" || it.name == "Sleep" }) {
            work.row = row.copy(readiness = rounded(70 + 15 * zs.sumOf { it.first * it.second } / zs.sumOf { it.second }).toInt().coerceIn(0, 100))
        }
    }
    fun avg(key: (DayResult) -> Double?, n: Long): Double? {
        val values = rows.filterKeys { it > today.minusDays(n) }.values.mapNotNull { key(it.row) }
        return values.takeIf { it.isNotEmpty() }?.let { rounded(average(it), 1) }
    }
    val rhr7 = avg({ it.restingHr }, 7)
    // Uth's formula wants resting heart rate measured awake and lying still; sleeping heart rate runs lower and would
    // inflate VO2max, so this uses the awake resting rate.
    val vo2 = vo2maxUth(hrMax, avg({ it.awakeRestingHr }, 7), profile.sex)
    val trimps = rows.mapNotNull { (d, w) -> w.row.trimp?.let { d to it } }.toMap()
    val acute = trimps.filterKeys { it > today.minusDays(7) }.values.toList()
    val chronic = trimps.filterKeys { it > today.minusDays(28) }.values.toList()
    val haveDays = java.time.temporal.ChronoUnit.DAYS.between(first, today) + 1
    val acwr = if (haveDays >= 21 && acute.isNotEmpty() && chronic.isNotEmpty() && average(chronic) > 0)
        rounded(average(acute) / average(chronic), 2) else null
    val twork = rows[today]
    val trow = twork?.row
    val doneWork = rows.values.lastOrNull { it.row.readiness != null }
    val done = doneWork?.row
    val todayOut = TodayResult(trow?.readiness, twork?.factors.orEmpty(), trow?.strain, trow?.sleepScore,
        trow?.let { bodyBattery(byDay[today].orEmpty(), it, hrMax) }, trow?.let { recommendation(it.readiness) },
        if (trow != null && trow.sleepScore == null) "No finished night yet today. Readiness, sleep score and body battery appear " +
            "after you wake up with the watch on." else null,
        done?.let { LastReadiness(it.date, it.readiness!!, doneWork.factors.toList()) })
    val latestRhythm = checks.filter { it.signalQuality >= 0.5 }.maxByOrNull { it.start }?.rhythmStatus
    val alerts = makeAlerts(rows.mapValues { it.value.row }, today, profile, hrMax, acwr, latestRhythm, ownHealth)
    return DailyResult(rows.filterKeys { it > today.minusDays(days.toLong()) }.values.map { it.row }, todayOut,
        Trends(rhr7, avg({ it.restingHr }, 28), avg({ it.hrvRmssd }, 7), avg({ it.hrvRmssd }, 28), vo2,
            fitnessAge(vo2, profile.sex), sleepRegularity(minutes.filter { dayOf(it.ts, zone) > today.minusDays(days.toLong()) }),
            acute.takeIf { it.isNotEmpty() }?.let { rounded(it.sum(), 1) }, acwr, hrMax, hrRest), alerts)
}

private fun makeAlerts(
    rows: Map<LocalDate, DayResult>, today: LocalDate, profile: Profile, hrMax: Int?, acwr: Double?,
    latestRhythm: String?, health: Map<String, Map<LocalDate, Double>>,
): List<Alert> {
    val out = arrayListOf<Alert>()
    val t = rows[today]
    val prev = rows.filterKeys { it >= today.minusDays(28) && it < today }.values
    val baseRest = prev.mapNotNull { it.restingHr?.takeIf { v -> v != 0.0 } }
    if (nonzero(t?.restingHr) && baseRest.size >= 3 && t!!.restingHr!! >= average(baseRest) + 5) {
        val baseHrv = prev.mapNotNull { it.hrvRmssd?.takeIf { v -> v != 0.0 } }
        val down = nonzero(t.hrvRmssd) && baseHrv.isNotEmpty() && t.hrvRmssd!! < 0.85 * average(baseHrv)
        val text = if (health["resting_hr"]?.containsKey(today) == true) t.restingHr.toString() else t.restingHr.toLong().toString()
        out.add(Alert("warn", "Resting heart rate is up", "$text bpm, ${rounded(t.restingHr - average(baseRest)).toLong()} above your usual" +
            (if (down) " and HRV is down." else ".") + " Hard training, alcohol, poor sleep or an oncoming illness can all do this."))
    }
    t?.sleepMinutes?.let { if (it < 360) out.add(Alert(if (it < 300) "warn" else "info", "Short night", "${it / 60} h ${it % 60} min of sleep.")) }
    if (acwr != null && acwr > 1.5) out.add(Alert("warn", "Training load jumped",
        "This week's load is ${String.format(java.util.Locale.ROOT, "%.1f", acwr)}x your 4-week average; injury risk rises above 1.5."))
    rows[today.minusDays(1)]?.wornMinutes?.let { if (it < 960) out.add(Alert("info", "Watch off for long stretches",
        "Yesterday it was worn ${it / 60} h; scores are less reliable.")) }
    t?.spo2Avg?.let { if (it != 0.0 && it < 92) out.add(Alert("warn", "Low blood oxygen readings",
        "Average SpO2 ${rounded(it).toLong()}%. Wrist SpO2 is noisy; if it persists, ask a doctor.")) }
    if (latestRhythm == "possible_af") out.add(Alert("warn", "Irregular rhythm in your last measurement",
        "Repeat it sitting still. If it recurs, ask a doctor for an ECG. Not a diagnosis."))
    if (profile.age == null || profile.age == 0 || profile.sex.isNullOrEmpty() || profile.weightKg == null || profile.weightKg == 0.0 || hrMax == null || hrMax == 0)
        out.add(Alert("info", "Complete your profile", "Add age, sex and weight to unlock heart rate zones, strain, calories and VO2max."))
    return out
}
