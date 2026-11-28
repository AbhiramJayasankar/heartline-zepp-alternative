# Heart checks and a moving wrist

November 2026. Can a heart check (the on-demand or automatic HRV / rhythm measurement from the watch's raw optical
pulse) still find beats while the hand moves? And does the watch's SpO2 / sleep-breathing mode give more raw optical
data? Short answers: **no** and **no**. What helps is honest gating: a motion level that sees the wrist turning, a
beat finder that takes its threshold and template from quiet time only, and an acceptance rule that counts quiet-time
quality and 45 s of clean beats. By day that makes RMSSD cleaner rather than checks more often clean; more clean
daytime checks need checks timed to when the wrist is still (follow-up below).

## Data

- 374 raw recordings from the phone, 21 to 27 Nov 2026 (the app keeps 7 days): **191 daytime checks** (2 min,
  automatic, sitting) and **183 overnight checks** (3 min, asleep: the still control). PPG at 50 Hz, accelerometer at
  26 Hz; checks do not stream the gyroscope. Copies stayed in a scratch folder, never in git.
- Reference heart rate: the watch's own minute heart rate (activity records). On the 185 checks that were clean it
  agreed with Heartline's beat-derived rate to 2.3 bpm on average (94% within 6 bpm), so it is a fair rate reference.
  There is no ECG, so beat *timing* is judged indirectly (below).
- Before: the app's own log shows **475 of 552 daytime samples set aside (86%)**. Replaying the 191 recordings through
  a numpy mirror of the Kotlin pipeline gives the same picture: **27 of 191 clean (14%)**, nights 158 of 183 (86%).

## What was tried

| # | Idea | Result |
|---|------|--------|
| 1 | **Adaptive noise cancellation**: RLS (λ 0.995) and NLMS with the 3 accelerometer axes and the magnitude as references, lags −4…+7 samples, a-priori error | During movement the PPG band (0.5–4 Hz) carries a median **8×** the power of still time (IQR 4–21×). RLS removes a median **8%** of it (IQR −3% to 21%); NLMS makes it worse. The artifact is mostly not a linear function of the watch's acceleration. |
| 2 | **Spectral subtraction** of the motion spectrum | Removes ~65% of the signal in still time too (it removes pulse, not artifact). Dominant frequency within 6 bpm of the watch in 12% of moving windows vs 7% raw. |
| 3 | **Beats during movement**, plain detector | 8 s windows moving more than half the time: **3%** give a heart rate within 6 bpm of the watch (2% after RLS); still windows of the same checks 34%. |
| 4 | **Template matching**: matched filter with the check's own quiet-time beat | Rate within 6 bpm in **36%** of moving windows (chance, against a shifted reference, 12–17%), so some rate comes back. But where the rate matched, beat-to-beat differences are **344 ms RMS** (IQR 236–497) against a typical daytime RMSSD of 55 ms: the beats are not timed well enough for HRV. |
| 5 | **Multi-channel** | There is one optical channel (see SpO2 below), so nothing to combine. |
| 6 | **Motion gate**: the old gate was the spread of the acceleration's *size* (std of \|a\| over ±1 s > 0.05 g) | Blind to turning the wrist (the size stays 1 g). In the time it called still, failing daytime checks moved a median **0.033 g** summed over the axes, against 0.011 g in passing daytime checks and 0.005 g asleep. Within the same checks, beats accepted while the axes moved > 0.02 g had **21% larger** beat-to-beat differences than quiet beats (median ratio 1.21, IQR 0.96–1.55, 39 checks) and twice the jumps over 100 ms (9.5% vs 5.0%): they inflate RMSSD. |
| 7 | **Per-axis gate** (√ of the summed per-axis variances), swept 0.01–0.025 g | At 0.025 g the wrist moves a median **71%** of a daytime check (33% by the old gate; 92% at 0.01 g); asleep 2–4%. On checks clean under both gates, RMSSD drops a median 1.5% by day and ≥ 10% in a quarter of them; nights unchanged. |
| 8 | **Template poisoning**: polarity, peak threshold, typical beat size and beat template taken from quiet time only | Movement set the old threshold and template; real beats failed both. Quiet-only: daytime clean 27 → 33, nights 158 → 167 (old gate); on checks clean either way RMSSD unchanged (median ratio 1.00, 90% within 5%). |
| 9 | **How much clean time is enough**: real daytime movement masks laid over clean overnight checks (948 trials), RMSSD from the clean beats left (back-to-back pairs only, no gap filling) against the same window unmasked | No bias. Median error 8.3% at 30–45 s clean (90th percentile 24%), **6.3% at 45–60 s (19%)**, 3.9% at 60–90 s (11%), 1.7% at 90–130 s (6%). |
| 10 | One-beat guard band around rejected beats | Fewer checks pass, no measurable gain (the ±1 s motion window already pads each movement by about a beat). Not used. |
| 11 | **Where contamination starts** (Gemini's suggestion): successive differences of beats in each per-axis motion band, against the same check's beats below 0.010 g | 0.010–0.015 g: ratio **1.01** (41 checks); 0.015–0.020 g: **1.27** (23); 0.020–0.025 g: 1.29 (9); 0.025–0.035 g: 1.34 (11); 0.035–0.050 g: 1.66 (7). Beats are as good as quiet ones up to 0.015 g and worse above it. |

## Verdict on movement

Beats cannot be recovered for HRV while the hand moves, at this sensor's quality: one green channel, ~0.7% pulse
depth, and artifacts from the strap and tendons that the watch's acceleration does not describe (1). Rate partly comes
back with template matching (4), timing does not. The literature agrees (below).

The best achievable is to gate honestly and use every quiet second:

- **Motion level summed over the axes, limit 0.015 g** (`MOVING_G`), where beats stop behaving like quiet ones (11).
  Stricter than the old gate, so fewer beats get through, but the ones that do are clean (6, 7, 11).
- **Threshold, template and beat size from quiet time** (8).
- **Accept an automatic check with 45 s of clean back-to-back beats and 70% of the beats found in quiet time passing**,
  instead of half the check's length and 70% of *all* beats (beats "found" during movement are noise and are set aside
  anyway, so counting them failed checks for moving, not for a poor signal) (9).

Replayed through the Kotlin pipeline (`LocalRecordingsReport`, below) the 191 daytime checks go from
**27 clean (14%) to 28 (15%)**, the 183 overnight ones from **158 (86%) to 163 (89%)**. By day the gain is in what the
clean checks contain, not how many there are: beats taken while the wrist turned no longer reach RMSSD. At 30 s instead
of 45 s, 41 daytime checks would pass, but a single check's RMSSD would then be off by 24% or more one time in ten (9).
Most daytime checks still fail, and honestly: the wrist moved most of the time.

Not settled: by day the stricter gate made the rate agreement with the watch's minute heart rate a little worse among
accepted checks (within 6 bpm about 80% against 91%), whatever their clean time (small numbers: 4 to 34 checks per
group). Asleep it is the same under every gate (94–95%). The likely reason is that the quiet beats are a subset of a
minute whose heart rate the movement raised, while the watch averages the whole minute; the watch's figure is no ECG.
The beat-to-beat evidence (6, 11) is direct, and RMSSD is what the check is for.

The bigger lever for daytime checks is **when** they run, not the signal processing: the wrist moves most of a
daytime check (median 83% of the time above 0.015 g). That follow-up was designed with Gemini, checked on these
recordings, and built in 0.13.0 (`DayCheckGate`, from the live result the app already works out each second):

- **Short scouts, spaced out.** Open the stream for 20 s; if the wrist was still (≤ 0.015 g) for less than 6 s of it,
  stop and try again 8–10 minutes later (built: 10 minutes, and a scout is not one of the three tries), instead of three
  2-minute tries back to back inside the same bout of typing.
  On the 191 daytime checks this would have ended **131 after 20 s and lost 3 of the 29 that passed**.
- **Stop when done.** Run until 50 s of clean beats (5 s spare over the 45 s rule), up to the check's length (2 minutes
  by default), and give up as soon as 45 s can no longer be reached. The checks that passed reached 50 s at a median **74 s** (IQR 64–88), not
  120 s.
- Untested here, and needing the watch: whether the accelerometer can stream with the LED off (mask 0x01 alone), what
  continuous scouting costs the watch's battery, and whether the bout patterns of a whole desk day match these
  2-minute clips. A few hours of continuous accelerometer recording at a desk would settle the spacing. Streaming the gyroscope during
checks would catch turns about the gravity axis that the accelerometer cannot see; it needs an on-watch test.

## Verdict on the SpO2 / sleep-breathing mode

It gives no extra or better raw optical data.

- The raw-stream sensor mask (`01 mask …`) was probed on 2–3 Nov (Sensors lab): bit 0x04 alone sends nothing; 0x80
  only raises the green PPG from 25 to 50 Hz; every combination gives the same single PPG packet type (0x01). The
  rate byte is ignored.
- Sleep breathing quality (`06 3D 00 01`) has been **on since 7 Nov**, and the watch logged an SpO2 reading every
  minute asleep. 98 of the 183 overnight checks overlapped 273 of those readings: the green PPG ran on at 50 Hz with
  no gaps (largest gap median 38 ms), its level moved a median 1.6% around the readings, and the BLE log of the last
  7 days has **no unknown raw packet type at all**. The red/IR measurement stays inside the watch; only its result
  arrives, through the SpO2 history fetch.
- Gadgetbridge's Huami code knows the same raw types (accelerometer, one PPG type it ignores, timestamps) and nothing
  red or infrared. Gemini found no published or reverse-engineered evidence either (below).

## Gemini (Neon, reasoning High) as a second reader

Gemini's Neon model (web app, reasoning set to High) was used through the work: given the sensor setup and each set
of measurements (never personal data), asked to research and critique. What it said, and how it held up here:

- **Recovery during movement is a dead end on this sensor.** No published method recovers RMSSD-grade (10–20 ms) beat
  timing from one green wrist channel during non-periodic hand movement. Desk movements are tendon and tissue
  deformation and strap pressure, which the case's acceleration barely sees; it cited Zhang et al. 2019 (Sensors 19:673)
  on micromotion artifacts that even a second wavelength can't cancel linearly. Matches (1)–(4) here. Deep-learning
  denoisers report ~12–17 ms RMSSD error on PPG-DaLiA (Empatica, 64 Hz), unvalidated on other hardware: it was
  explicit that it was unsure there.
- **The magnitude gate is blind to rotation; use per-axis motion.** Confirmed here (6). It pointed to Charlton et al.
  2023 (CinC): accelerometer gating at 12.9 mg mean amplitude deviation of the magnitude, AUROC 0.78. It then worked
  out that this sits near 0.028 g on this app's per-axis measure, and suggested binning beats by motion to find where
  contamination starts. That test (11) moved the limit from the planned 0.025 g to **0.015 g**.
- **Template poisoning**: build the template from quiet beats only (Orphanidou et al. 2014). Confirmed, small (8).
- **Non-contiguous RMSSD over truly adjacent beats, no gap filling, is valid**: Cajal et al. 2022 (Sensors 22:5774),
  Rossi et al. 2020 (Sensors 20:7122); interpolating across gaps hurts RMSSD (Benchekroun et al. 2023). Confirmed (9).
  Minimum length: literature lower bounds of 10–30 s (Baek et al. 2015; Shaffer et al. 2020) are cohort-level
  agreement; for a single displayed check it agreed with 45 s from this data's 90th-percentile errors.
- **One-beat guard band**: it suggested one, then agreed the ±1 s motion window already pads each movement by about a
  beat, which explains why it gained nothing here (10).
- **Disagreement on heart rate agreement**: it read the daytime dip in agreement with the watch's minute heart rate as a
  sampling mismatch (quiet subset vs a whole, partly moving minute), not a reason to keep the magnitude gate. Nights,
  where the gates agree, support that.
- **Scheduling is the bigger lever** than more filtering: start a check after a few seconds of stillness and run it
  until 45 s of clean beats are in, up to a cap. Built in 0.13.0; see the follow-up.
- **SpO2 / red-IR**: no published or reverse-engineered evidence (Gadgetbridge, huami-token tooling) that Huami/Zepp
  watches stream red or IR samples; SpO2 arrives only as computed records. It suggested one more probe: raw streaming
  *while* an on-watch SpO2 spot measurement runs, in case the red/IR LEDs only feed the stream when lit. The overnight
  data here already overlaps 273 of the watch's own SpO2 readings without any change in the stream, so that probe is
  unlikely to differ, but it has not been run with a manual spot check.

- **Scheduling numbers**: it proposed the scout, early-stop and cap figures in the follow-up above, and the offline
  checks of them; those checks are the ones reported there.

Where it was wrong or loose: it said that resampling to 100 Hz by straight lines before refining each peak with a
parabola halves the sub-sample correction. Not here, because the 0.5–4 Hz filter runs after the resampling and smooths
the straight-line joins away: on synthetic beats with known times the interval error is **0.2 ms RMS** at 25 and at
50 Hz (4 ms without the refinement). Its first answer also guessed the app might lack sub-sample timing, and
read the 34%-of-still-windows figure as a detector fault; it came from the old gate letting turning wrists count as
still.

## Live chart

The heart check screen and the daytime HRV card now draw what the check is finding, about once a second, by the
same steps as the finished check (`liveCheck`): the filtered pulse as an ECG-paper strip with every beat marked
(filled used, hollow set aside), moving stretches shaded, the motion level in its own lane with the 0.015 g line,
axis values (light counts, seconds, g), "Last 10 s" or "Whole check", and heart rate (last 15 s), beats used, clean
time, RMSSD so far and movement now.

## Reproducing

- Python experiments: a scratch mirror of the pipeline (not in git); the reference implementation is
  `server/analytics/ppg.py`, updated alongside Kotlin, and the goldens were regenerated from it
  (`android/core/analytics/tools/make_goldens.py`).
- `HEARTLINE_RECORDINGS=<folder of raw CSVs> ./gradlew :core:analytics:test --tests '*LocalRecordingsReport*'`
  prints each recording's movement, quality, clean time and verdict under the current rule. It is skipped without the
  variable.
