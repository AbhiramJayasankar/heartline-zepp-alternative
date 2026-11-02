package dev.heartline.analytics

data class Recording(
    val start: Double,
    val ppgT: DoubleArray,
    val ppg: DoubleArray,
    val accT: DoubleArray,
    val acc: Array<FloatArray>,
    val gyroT: DoubleArray = doubleArrayOf(),
    val gyro: Array<FloatArray> = emptyArray(),
    val magT: DoubleArray = doubleArrayOf(),
    val mag: Array<FloatArray> = emptyArray(),
    // Preserve arrival clocks for capture gaps; the legacy PPG/check clock stays burst-retimed.
    val accArrivalT: DoubleArray = accT,
    val captureDurationS: Double? = null,
    // Capture regularity needs packet arrival gaps; keep the legacy check-session PPG clock intact.
    val ppgArrivalT: DoubleArray = ppgT,
) {
    val durationS: Double get() = if (ppgT.size > 1) ppgT.last() else 0.0
    val fs: Double get() = if (ppgT.size > 2) median(DoubleArray(ppgT.size - 1) {
        1.0 / (ppgT[it + 1] - ppgT[it])
    }) else 25.0
}

data class MinuteSample(
    val ts: Long, val kind: Int, val intensity: Int, val steps: Int, val hr: Int?,
    val sleep: Int, val deep: Int, val rem: Int, val state: String,
)
data class HealthValue(val type: String, val ts: Long, val value: Double)

/** Three-argument summaries are assumed already qualified, as in InsightsDB.hrv_by_day.
 * Supply the extra fields when passing unfiltered cached session rows. */
data class CheckSummary(
    val start: Double, val rmssdMs: Double?, val hrBpm: Double?,
    val signalQuality: Double = 1.0, val usableS: Double = 45.0, val rhythmStatus: String? = null,
)
data class Profile(
    val age: Int? = null, val sex: String? = null, val weightKg: Double? = null,
    val heightCm: Double? = null, val maxHr: Int? = null, val restingHr: Int? = null,
)

data class HrvResult(
    val meanRrMs: Double?, val sdnnMs: Double?, val rmssdMs: Double?, val pnn50: Double?,
    val sd1Ms: Double?, val sd2Ms: Double?, val lf: Double?, val hf: Double?,
    val lfHf: Double?, val stressIndex: Double?,
)
data class RhythmFeatures(
    val nrmssd: Double, val shannonEntropy: Double, val tpr: Double,
    val randomLike: Boolean, val intervals: Int,
)
data class RhythmResult(
    val status: String, val ectopicBeats: Int, val irregularity: Double?,
    val notes: List<String>, val features: RhythmFeatures?,
)
data class RespirationComponents(val riiv: Double?, val riav: Double?, val rifv: Double?)
data class RrSample(val t: Double, val rrMs: Double, val ectopic: Boolean)
data class MotionSample(val t: Double, val g: Double)
data class SessionResult(
    val file: String, val start: Double, val durationS: Double, val signalQuality: Double,
    val usableS: Double, val hrBpm: Double?, val beats: Int, val hrv: HrvResult,
    val rhythm: RhythmResult, val respiratoryRate: Double?, val respiration: RespirationComponents,
    val rr: List<RrSample>, val ppgPreview: List<Double>, val previewFs: Double?,
    val beatTimes: List<Double>, val beatGood: List<Boolean>, val motion: List<MotionSample>,
)

data class HrZones(val z1: Int, val z2: Int, val z3: Int, val z4: Int, val z5: Int)
data class DayResult(
    val date: String, val restingHr: Double?, val hrvRmssd: Double?,
    val sleepMinutes: Int?, val sleepEfficiency: Double?, val deepMin: Int?, val lightMin: Int?,
    val remMin: Int?, val awakeMin: Int?, val sleepScore: Int?, val steps: Int?, val activeMinutes: Int?,
    val wornMinutes: Int?, val strain: Double?, val trimp: Double?, val hrZones: HrZones?,
    val spo2Avg: Double?, val stressAvg: Double?, val readiness: Int?, val caloriesActive: Int?,
    val sleepStart: Long? = null, val sleepEnd: Long? = null,
)
data class ReadinessFactor(val name: String, val impact: String, val detail: String)
data class LastReadiness(val date: String, val value: Int)
data class TodayResult(
    val readiness: Int?, val readinessFactors: List<ReadinessFactor>, val strain: Double?,
    val sleepScore: Int?, val bodyBattery: Int?, val recommendation: String?, val note: String?,
    val lastReadiness: LastReadiness?,
)
data class Trends(
    val restingHr7d: Double?, val restingHr28d: Double?, val hrv7d: Double?, val hrv28d: Double?,
    val vo2maxEstimate: Double?, val fitnessAge: Int?, val sleepRegularity: Double?,
    val cardioLoad7d: Double?, val acuteChronicRatio: Double?, val hrMaxUsed: Int?, val hrRestUsed: Double?,
)
data class Alert(val level: String, val title: String, val detail: String)
data class DailyResult(val days: List<DayResult>, val today: TodayResult, val trends: Trends, val alerts: List<Alert>)
