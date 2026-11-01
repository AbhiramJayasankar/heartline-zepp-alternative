# Heartline analytics

Pure Kotlin/JVM port of `server/analytics/{ppg,hrv,rhythm,respiration,session,daily}.py`.
No runtime dependencies beyond Kotlin/JDK, no Android API, no reflection. The reference's
thresholds, missing-data behavior, notes and recommendations are retained.

## Public API

Package: `dev.heartline.analytics`. Input and result data classes are declared in
[`Models.kt`](src/main/kotlin/dev/heartline/analytics/Models.kt).

```kotlin
data class Recording(
    val start: Double, val ppgT: DoubleArray, val ppg: DoubleArray,
    val accT: DoubleArray, val acc: Array<FloatArray>,
)
// Recording also exposes durationS and estimated fs as computed properties.

fun readRecordingCsv(reader: java.io.Reader): Recording
fun makeRecording(
    ppgT: DoubleArray, ppg: DoubleArray,
    accT: DoubleArray = doubleArrayOf(), acc: Array<FloatArray> = emptyArray(),
): Recording
fun retime(t: DoubleArray, window: Int = 125): DoubleArray
fun analyzeSession(rec: Recording, name: String): SessionResult

data class MinuteSample(
    val ts: Long, val kind: Int, val intensity: Int, val steps: Int, val hr: Int?,
    val sleep: Int, val deep: Int, val rem: Int, val state: String,
)
data class HealthValue(val type: String, val ts: Long, val value: Double)
data class CheckSummary(
    val start: Double, val rmssdMs: Double?, val hrBpm: Double?,
    val signalQuality: Double = 1.0, val usableS: Double = 45.0,
    val rhythmStatus: String? = null,
)
data class Profile(
    val age: Int? = null, val sex: String? = null, val weightKg: Double? = null,
    val heightCm: Double? = null, val maxHr: Int? = null, val restingHr: Int? = null,
)
fun computeDaily(
    minutes: List<MinuteSample>, health: List<HealthValue>, checks: List<CheckSummary>,
    profile: Profile, days: Int, now: java.time.ZonedDateTime,
): DailyResult
fun healthByDay(
    health: List<HealthValue>, since: Long = Long.MIN_VALUE,
    zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
): Map<String, Map<java.time.LocalDate, Double>>
fun hrvByDay(
    checks: List<CheckSummary>, zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
): Map<java.time.LocalDate, Double>
```

Arrival stamps passed to `makeRecording` are Unix seconds. The resulting `ppgT`/`accT`
are seconds relative to `start`; accelerometer values are **g**, as already stored in
the Python CSV. CSV gyro/magnetometer/other rows are ignored. The reader is owned by
the caller. Recording arrays are copied by `makeRecording`; treat arrays supplied to
the direct constructor as immutable while analyzing.

`SessionResult` mirrors the session JSON with camelCase names: `file`, `start`,
`durationS`, `signalQuality`, `usableS`, `hrBpm`, `beats`, `hrv`, `rhythm`,
`respiratoryRate`, `respiration`, `rr`, `ppgPreview`, `previewFs`, `beatTimes`,
`beatGood`, `motion`. HRV includes all ten reference metrics; rhythm includes notes
and optional features. **pnn50 is a percentage (0–100)**. Intervals/HRV are milliseconds
in the result. Heart rate is null for fewer than ten NN intervals or outside 35–200 bpm.

`DailyResult` contains typed `days`, `today`, `trends`, and `alerts`. All fields of the
Python dictionaries are represented, including readiness factors, last readiness,
sleep start/end (null without a finished night), recommendations and alerts.

As in `insights.py`, minute samples must be chronological, with states already mapped
by the data layer, and invalid HR (0 or 255) replaced by null. Supply the lookback
window from midnight `max(days,35)+1` days before today. Health aggregation applies
that cutoff internally. Calendar grouping uses **now.zone**, replacing Python's
implicit process-local timezone; the fixed 86,400-second night window is preserved.

Three-argument `CheckSummary` inputs mean already-qualified checks. When feeding
raw session-cache rows, pass quality and usable duration: daily HRV requires SQI ≥ 0.7
and usableS ≥ 45, then takes the rounded daily median. Latest rhythm uses the newest
check with SQI ≥ 0.5 independently of HRV qualification. Health values use the daily
minimum for `resting_hr`, daily means for every other series; only the same series
names consumed by Python affect scores. For example, `sleep_spo2` is not silently
renamed to `spo2`.

## Files

- `Models.kt`: inputs and typed results.
- `Numeric.kt`: Butterworth design, SOS forward/backward filtering with odd padding,
  interpolation, detrending, radix-2 FFT, statistics and Python-style rounding.
- `Ppg.kt`: CSV, burst retiming, peak selection, template SQI, amplitude and motion rejection.
- `Hrv.kt`, `Rhythm.kt`, `Respiration.kt`, `Session.kt`: corresponding Python modules.
- `Daily.kt`, `DailySleep.kt`, `DailyLoad.kt`: daily aggregation, scores, trends and alerts.
- `src/test/kotlin`: two parameterized golden suites, numeric/edge tests, fixture-only
  JSON reader and explicit result mappings. No JSON dependency or reflective serialization.
- `tools/make_goldens.py`: deterministic synthetic CSV generation, byte-for-byte real
  CSV copies, SQLite read-only snapshot export, Python outputs and source hashes.
- `tools/summarize_goldens.py`: comparison report from passing JUnit XML.

Numeric work uses primitive arrays and a small FFT. The three production filter
designs and steady-state initial conditions are cached as exact double coefficients
from the reference's `butter`/`sosfilt_zi`; the general order-2 design is also tested
against additional SciPy filter designs. Filtering on the constant diagnostic is
bit-for-bit equal to the reference.

## Regenerate and verify

From the worktree root, using the reference venv (numpy/scipy; no installs):

```powershell
& 'C:\Users\abhir\Desktop\projects\amazfit-gtr2e\.venv\Scripts\python.exe' -B `
  android/core/analytics/tools/make_goldens.py
```

The generator uses `sys.dont_write_bytecode`, SQLite URI `mode=ro`, `query_only`, and
a read transaction. It never instantiates the reference's writable `Store` or creates
reference caches. It requires host timezone Asia/Kolkata and records versions, source
hashes and fixed now in `manifest.json`. The exported real snapshot has 10,698 minute
rows and 29,024 health rows, is 1,452,225 bytes, and starts 2026-10-12. Synthetic daily
inputs use a compact reproducible recipe with explicit health/check values.

Run from `android/`:

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
./gradlew --no-daemon :core:analytics:test
```

After the complete test run:

```powershell
& 'C:\Users\abhir\Desktop\projects\amazfit-gtr2e\.venv\Scripts\python.exe' -B `
  android/core/analytics/tools/summarize_goldens.py
```

See [`COMPARISON.md`](COMPARISON.md) for the per-golden results and test counts.
Session tests compare the entire response tree. Daily tests compare every numeric
field within 1% (one unit for integer fields), plus exact strings, booleans and nulls;
the report additionally measures absolute numeric drift.

## Documented numerical limit

On perfectly constant **nonzero** PPG, SciPy's filter roundoff (~1e-10 counts) creates
many exactly equal-height local maxima. NumPy's default unstable argsort orders ties
differently from Kotlin's deterministic rightmost-first priority. This can change
the resulting spurious beat count, HR, HRV and rhythm. The flat recording is retained
as an explicit diagnostic rather than a passing session-parity claim. Its filtered
samples are compared directly. No physiological threshold or notes text was changed
to hide this issue. All ten session parity cases, including the six requested cases,
are tested separately.
