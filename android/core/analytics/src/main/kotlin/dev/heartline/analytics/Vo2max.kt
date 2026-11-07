package dev.heartline.analytics

import kotlin.math.roundToInt

/** A recorded workout, as much of it as a VO2max estimate needs. */
data class WorkoutEffort(val start: Long, val type: String, val activeS: Int, val distanceM: Int?, val avgHr: Int?)

/** One workout's VO2max estimate, with what went into it. */
data class WorkoutVo2(val start: Long, val type: String, val vo2max: Double, val speedKmh: Double, val avgHr: Int, val reservePct: Int)

/** VO2max from workouts when any qualify, else [fromHeartRates] as a stand-in. */
data class Vo2maxResult(
    val value: Double?, val fitnessAge: Int?, val fromWorkouts: Boolean,
    /** The heart-rate-only estimate (Uth), always worked out so it can be shown beside a workout value. */
    val fromHeartRates: Double?,
    /** The workouts used, newest first, and those left out with why. */
    val used: List<WorkoutVo2>, val skipped: List<Pair<WorkoutEffort, String>>,
)

/**
 * VO2max from one outdoor run or walk: the oxygen cost of the pace (ACSM walking and running equations, flat ground)
 * divided by how hard the heart was working (percent of heart rate reserve, which tracks percent of VO2 reserve;
 * Swain 1997). Returns null with a reason when the workout can't give a fair estimate.
 */
fun vo2maxFromWorkout(w: WorkoutEffort, hrRest: Double?, hrMax: Int?): Pair<WorkoutVo2?, String?> {
    val outdoor = w.type == "Outdoor running" || w.type == "Walking" || w.type == "Hiking"
    if (!outdoor) return null to when (w.type) {
        "Treadmill" -> "treadmill distance comes from wrist steps and the incline isn't known"
        else -> "not a run or walk with GPS distance"
    }
    if (hrRest == null || hrMax == null || hrMax <= hrRest) return null to "needs your max and awake resting heart rate"
    val avgHr = w.avgHr ?: return null to "no average heart rate"
    val distance = w.distanceM?.takeIf { it > 0 } ?: return null to "no distance"
    if (w.activeS < 600) return null to "shorter than 10 minutes"
    if (distance < 1000) return null to "under 1 km"
    val speed = distance / (w.activeS / 60.0) // metres a minute
    val reserve = (avgHr - hrRest) / (hrMax - hrRest)
    // Easy efforts amplify every error in heart rate and pace; all-out ones aren't steady.
    if (reserve < 0.4) return null to "too easy (${(reserve * 100).roundToInt()}% of heart rate reserve, needs 40%)"
    if (reserve > 0.9) return null to "too close to max to be steady"
    // ACSM: walking 0.1 ml/kg/min per m/min (up to about 100 m/min), running 0.2 above about 134 m/min; between the two
    // the workout type decides.
    val running = speed > 134 || (speed > 100 && w.type == "Outdoor running")
    if (speed < 50) return null to "too slow for the walking equation"
    val vo2 = (if (running) 0.2 else 0.1) * speed + 3.5
    val vo2max = 3.5 + (vo2 - 3.5) / reserve
    if (vo2max !in 20.0..90.0) return null to "estimate outside a believable range"
    return WorkoutVo2(w.start, w.type, rounded(vo2max, 1), rounded(speed * 0.06, 1), avgHr, (reserve * 100).roundToInt()) to null
}

/**
 * VO2max for the Trends page: the median of up to the five newest qualifying runs and walks in the last 90 days,
 * else the heart-rate estimate (Uth: 15.3 x max / resting heart rate) as a stand-in.
 */
fun vo2max(workouts: List<WorkoutEffort>, nowS: Long, hrRest: Double?, hrMax: Int?, sex: String?): Vo2maxResult {
    val stand = vo2maxUth(hrMax, hrRest, sex)
    val used = ArrayList<WorkoutVo2>()
    val skipped = ArrayList<Pair<WorkoutEffort, String>>()
    for (w in workouts.filter { it.start >= nowS - 90 * 86_400L }.sortedByDescending { it.start }) {
        val (est, why) = vo2maxFromWorkout(w, hrRest, hrMax)
        if (est != null) { if (used.size < 5) used.add(est) } else skipped.add(w to why!!)
    }
    val value = if (used.isNotEmpty()) rounded(median(used.map { it.vo2max }.toDoubleArray()), 1) else stand
    return Vo2maxResult(value, fitnessAge(value, sex), used.isNotEmpty(), stand, used, skipped)
}

/** Max heart rate from the profile, else 208 - 0.7 x age (Tanaka). */
fun maxHeartRate(profile: Profile): Int? = hrMaxOf(profile)
