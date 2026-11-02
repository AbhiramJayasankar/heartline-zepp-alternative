# Task D: workout detail decoding

Implemented the fields actually decoded by Gadgetbridge for summary version 515 and populated `WorkoutUi.detail`. All required Gradle tasks pass. Some requested fields cannot be decoded from the specified reference: its speed/cadence detail handlers are stubs, and its v515 summary parser contains no training-effect, recovery, load, or HR-zone fields. These remain unavailable rather than assigning meanings to unknown bytes.

## Files

Created:

- `android/core/protocol/src/main/kotlin/dev/heartline/protocol/WorkoutSummaryExt.kt`: independent v515 summary model/parser, terrain and swimming extensions.
- `android/core/protocol/src/main/kotlin/dev/heartline/protocol/WorkoutDetailsExt.kt`: compatible HR decoding, altitude deltas, pause/resume timestamps, opaque payloads and truncation reporting.
- `android/app/src/main/java/dev/heartline/app/WorkoutDetailMapping.kt`: latest-fetch joins, detail mapping, zones, Edwards TRIMP, readable extras and bounded series.
- `android/core/protocol/src/test/kotlin/dev/heartline/protocol/WorkoutExtFixturesTest.kt`: 56 parameterized checks over all 14 captured summary/detail pairs.
- `android/core/protocol/src/test/kotlin/dev/heartline/protocol/WorkoutExtTest.kt`: 10 synthetic format/edge-case tests, including fields absent from the captures.
- `android/app/src/test/java/dev/heartline/app/WorkoutDetailMappingTest.kt`: 18 mapping/zone/TRIMP/downsampling tests.
- `android/core/protocol/src/test/resources/workout_ext_real.json`: real blobs and independent expected values; only workout records are included.
- `android/core/protocol/tools/workout_ext_fixtures.py`: reproducible read-only fixture extraction and independent oracle.
- `TASK_D_REPORT.md`: this report.

Changed only the raw-fetch mapping and profile wiring in `RealController.activityDay` (three added lines, two replaced). No Task D stub existed in this checkout. `StoredJson.kt` and `WorkoutRouteMapping.kt` were read and reused unchanged. Existing `Workout`, summary/HR outputs and route parser are unchanged. No UI contract, UI screen, service, BLE, schema, settings or dependency edits. No commits or phone/watch operations.

## Reference and exact formats

Codeberg revision `b4f1eb5a8a6bcb85e51895f083dbb98f36d2bb9d` was inspected because the supplied sparse-checkout directory was absent.

Summary source: [devices.huami.HuamiActivitySummaryParser.parseBinaryData](https://codeberg.org/Freeyourgadget/Gadgetbridge/src/commit/b4f1eb5a8a6bcb85e51895f083dbb98f36d2bb9d/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/devices/huami/HuamiActivitySummaryParser.java). The `version >= 512` layout applies at byte 24 for v515. The decoder deliberately accepts only version 515. All multi-byte numbers are little-endian; offsets are absolute, zero-based.

| Offset(s) | Representation | Field/unit |
| --- | --- | --- |
| 0, 2 | u16 each | Version, activity type code |
| 4, 8 | u32 each | Start/end Unix seconds |
| 12, 16, 20 | i32 each | Base longitude/latitude in Huami units, altitude m |
| 24, 28 | i32 each | Steps, active seconds |
| 32, 36, 40, 44 | i32 each | Max/min latitude, max/min longitude; raw Huami values retained |
| 48, 52 | f32 each | kcal, distance m |
| 56, 60 | f32 each | Ascent/descent m |
| 64, 68, 72 | f32 each | Max/min/average altitude m |
| 76, 80, 84 | f32 each | Max/min/average speed m/s |
| 88, 92, 96 | f32 each | Min/max/average pace s/m; byte 92 is the fastest pace |
| 100, 104, 108 | f32 each | Max/min/average cadence Hz; Java `Math.round(float * 60)` gives spm |
| 112, 116, 120 | f32 each | Max/min/alternate average stride m; Java `Math.round(float * 100)` gives cm |
| 124 | f32 | Secondary distance m; upstream only speculates that it is GPS-track length |
| 132, 134, 136, 138 | i16 each | Average HR bpm, average pace s/km, average stride cm, max HR bpm |
| 144/148, 152/156, 160/164 | f32/i32 pairs | Uphill/downhill/flat distance m and time ms; integer division by 1000 gives seconds |
| 140, 164, 168 (swimming) | f32 each | Average stroke distance m, strokes/s, average lap pace seconds |
| 176, 178 (swimming) | i16 each | Strokes, SWOLF |
| 180, 181 (swimming) | byte each | Swim-style code, lap count |

The terrain extension is read only for running/cycling/climbing/hiking (codes 1/4/10/15); upstream explicitly excludes treadmill. Swimming applies only to codes 6/7. Swim styles: 1 breaststroke, 2 freestyle, 3 backstroke, 4 medley. A lap count does not provide individual lap splits, so `detail.laps` stays empty.

Non-finite float values and altitude sentinels `-20000`, `-100000`, `100000` are omitted from usable metrics. Non-positive HR/short pace/short stride fields are absent. Pace at byte 88 must be positive and at most 60 s/m, matching upstream's sentinel filtering. Coordinate bounds retain their raw integers; `Int.MIN_VALUE/MAX_VALUE` sentinels are hidden in extras. Base-coordinate scaling is `/3,000,000`, as documented in [AbstractHuamiActivityDetailsParser.convertHuamiValueToDecimalDegrees](https://codeberg.org/Freeyourgadget/Gadgetbridge/src/commit/b4f1eb5a8a6bcb85e51895f083dbb98f36d2bb9d/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/huami/AbstractHuamiActivityDetailsParser.java).

Detail source: [HuamiActivityDetailsParser.parse, consumeGPSAndUpdateBaseLocation, consumeHeartRate, fixupMissingTimestamps](https://codeberg.org/Freeyourgadget/Gadgetbridge/src/commit/b4f1eb5a8a6bcb85e51895f083dbb98f36d2bb9d/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/huami/HuamiActivityDetailsParser.java).

- Each record is 8 bytes: type u8, time offset u8, payload[6]. Accumulated time adds `offset - previous` when nondecreasing, otherwise `offset`; this preserves upstream's wrap behavior exactly.
- Type 0: three signed LE16 longitude/latitude/altitude deltas. Altitude accumulates from summary byte 20; base `-20000` means absent. Altitude timestamps share the existing route parser's initial GPS-burst fixup where a route is available.
- Type 1: if payload bytes 1–5 are zero, payload byte 0 is HR at the accumulated time. Otherwise the payload contains three `(u8 time, u8 HR)` pairs with the same absolute-relative-to-start semantics as the existing parser. Existing zero filtering is preserved.
- Types 2/3 identify pause/resume times. Their six payload bytes remain opaque.
- Types 4/5/6/8 and unknown types retain the timestamp, type and six raw payload bytes. No speed/cadence/steps/distance units are assigned. Incomplete final bytes are counted without reading beyond the blob.

[AmazfitGTR2eSupport](https://codeberg.org/Freeyourgadget/Gadgetbridge/src/commit/b4f1eb5a8a6bcb85e51895f083dbb98f36d2bb9d/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/huami/amazfitgtr2/AmazfitGTR2eSupport.java) and its GTR2 parent do not override these workout parsers. Their firmware-update support uses the 2020 family; no Zepp OS format was substituted.

## Mapping and calculations

Raw summaries are keyed by the embedded workout start, details by their stored start. Latest capture timestamp wins. Missing raw summaries still allow HR-based metrics from details or the existing stored track. Profile changes recompute metrics without requiring another fetch. No settings are added or persisted.

Dedicated UI slots receive active time, average/best pace, average/max speed (m/s × 3.6), cadence, stride, ascent/descent, altitude and track minimum HR. Other decoded metrics have labels and units in extras. Minimum HR is explicitly identified as estimated from the HR track, since v515 has no decoded summary minimum HR.

Zone lower bounds are `ceil(maxHR × 0.5/0.6/0.7/0.8/0.9)`, named Warm-up, Fat burn, Aerobic, Anaerobic, Max. Max HR comes from the profile, else `220 - age`, else 190. Values below 50% do not count toward these five zones. Sparse HR values are held until the next sample, clipped to the workout; no time before the first sample is backfilled. Duplicate timestamps use the last sample. Recorded pauses are excluded, including an unresumed final pause. HR 0/255 is invalid.

Edwards TRIMP is `sum(zone seconds × zone number) / 60`. These estimates do not claim to reproduce Zepp's proprietary load calculation. Series mapping converts units and retains at most 600 evenly selected original points, including endpoints. No constant series are fabricated from summary averages. Altitude is populated when GPS records exist; current reference-backed speed/cadence/steps/distance detail series remain empty.

## Real fixtures and validation

The supplied `heartline.db` was opened with SQLite `mode=ro`. It contains **14 summary fetches and 14 detail fetches: seven distinct workouts captured twice** (five strength, two treadmill). All summaries are 200 bytes/version 515. Details range from 2,640 to 10,864 bytes.

Every captured summary field is checked against an independent Python oracle derived from the Java reads. Every existing stored JSON field matches, including average/max HR, rounded calories/distance, steps, duration, active time, speed and cadence. Every stored HR sample matches. All 14 pairs are in the fixture, including duplicates. Terrain, swimming and GPS altitude are tested with synthetic byte vectors because no real capture contains them.

Treadmill examples:

| Start | Active/wall seconds | Distance | Min/avg/max km/h | Min/avg/max cadence spm | Average stride |
| --- | --- | --- | --- | --- | --- |
| 1793382094 | 635/1000 | 661.381 m | 2.628 / 3.751 / 7.729 | 82 / 86 / 94 | 73 cm |
| 1793470847 | 643/643 | 692.041 m | 2.628 / 3.872 / 7.225 | 81 / 88 / 92 | 73 cm |

Passed together: `:core:protocol:test`, `:core:ble:test`, `:app:testDebugUnitTest`, `:app:assembleDebug`, `:app:lintDebug`.

- Protocol: **196 tests**, including **66 new** (56 fixture cases + 10 synthetic).
- BLE: **29 tests**.
- App: **84 tests**, including **18 new**.
- **309 total, zero failures/errors/skips**. Lint: **zero errors**, 8 warnings and 1 hint, none located in the new files. `git diff --check` passes.

The sandbox resolves Java's home to `C:/` by default and blocks creation of the debug-keystore lock. Validation therefore used a writable scratch Java/Android home, in-process Kotlin compilation, the warm shared Gradle cache, and a temporary init script pointing debug signing at the existing `C:/Users/abhir/.android/debug.keystore`. No tracked Gradle configuration changed. The resulting APK is `android/app/build/outputs/apk/debug/app-debug.apk`.

## Unknown bytes and remaining checks

Summary byte ranges still unknown/unused:

- All v515 workouts: **128–131** (skipped int).
- Strength/treadmill/other non-terrain/non-swimming types: **140–199**. The treadmill tails contain nonzero words, but assigning them training-effect/recovery meanings would be unsupported.
- Terrain types: **140–143**, **168–199**.
- Swimming: **144–163**, **172–175**, **182–189**, plus trailing **190–199**.

Detail payloads still unknown: types **2/3** (pause/resume payload), **4/5/6** (upstream names them speed but only returns 6), **8** (swimming stub), **255** and any other unrecognized type. Each actual capture starts with `ff 00 08 20 01 00 00 00`; its meaning is not assumed. The two treadmill captures contain **531** and **539** type-6 records respectively; no other type-4/5/8 records occur. No actual capture contains GPS/type-0 records.

Consequently, watch-provided zone times, training/aerobic/anaerobic effect, recovery time, total stride, summary minimum HR and per-record speed/cadence/steps/distance remain **undecoded**. `totalStride` and `minHR` are merely initialized to zero in upstream's v515 branch, not read from bytes. Real-watch testing alone will not establish the missing byte formats; controlled captures or another verified parser are needed.

Owner checks after review:

1. For treadmill start **1793382094**, confirm 10:35 active versus 16:40 elapsed, average pace 15:59/km, peak speed about 7.73 km/h, cadence 86 spm and stride 73 cm. Inspect zones/TRIMP to ensure the pause at second **633** contributes no final 367 seconds. Active time differs from the pause timestamp by two seconds; verify the watch's own display.
2. For treadmill start **1793470847**, confirm 692 m, average pace 15:29/km, cadence 88 spm and peak speed about 7.23 km/h. Minimum HR/zones/load are HR-track estimates, not watch-native training metrics.
3. Capture an outdoor GPS workout and check altitude against the watch, signed ascent/descent, timestamp alignment and existing route display. No real GPS capture was available here.
4. Capture swimming and outdoor running/cycling if those extensions are used; validate SWOLF, style, lap count and uphill/downhill/flat times. Their offsets are source-backed but only synthetically tested.
5. Expect empty speed/cadence charts and absent training effect/recovery on these captures until the opaque formats have verified definitions. Existing summary numbers and HR charts should remain unchanged.
