# Methods: what each number is, where it comes from, and how much to trust it

Everything here is a wellness estimate from a wrist optical sensor (PPG) and accelerometer on an
Amazfit GTR 2e, not a medical device. Each section ends with a trust label:
**solid** (standard method, used as published), **adapted** (standard method, our parameters),
**heuristic** (our own scoring in the spirit of commercial wearables; no validation).

## Input signal

- Raw PPG, uint16, about 25 Hz, from the watch's raw-sensor stream; accelerometer int16 x/y/z (4100 = 1 g).
- Samples arrive in BLE bursts, so per-sample stamps overlap; and the PPG rate is not constant
  (a 5-minute session ran ~50 Hz for its first ~50 s, then 25 Hz). `ppg.retime` estimates the local rate
  over a 125-sample window from the running maximum of arrival times and integrates it. **adapted**
- Gain/baseline differ between sessions (seen ~2,300 vs ~40,000 counts), so all thresholds are relative to
  the signal's own spread. Polarity is detected from the skew of the first derivative (systolic upstroke is
  steeper than the fall). **adapted**

## Beats and signal quality (`ppg.py`)

- Band-pass 0.5–4 Hz (Butterworth, zero-phase) after linear resampling to 100 Hz, peak detection with
  a 0.33 s refractory period and prominence of 25% of the 5–95% spread, parabolic peak interpolation.
- Signal quality index: correlation of each beat with the median beat template; usable if r ≥ 0.80
  (template-matching SQI, as in Li & Clifford 2012), amplitude within 0.3–3× the median, and the wrist
  still (accel-magnitude SD over 2 s ≤ 0.05 g). `signal_quality` is the fraction of usable beats. **adapted**
- Intervals are only taken between two consecutive usable beats.

## HRV (`hrv.py`)

- Time domain per the Task Force of ESC/NASPE (1996): mean RR, SDNN, RMSSD, pNN50; Poincaré SD1 = √½·SD(ΔRR),
  SD2 = √(2·SDNN² − SD1²). **solid** (the formulas); PPG pulse intervals approximate ECG RR intervals at rest
  and diverge with movement and at high HRV, so treat ±5–10 ms RMSSD as noise.
- Ectopic/artifact cleaning: an interval < 80% of the local median followed by one > 110% is a premature beat
  plus compensatory pause; any other > 30% deviation is a detection artifact. Both are removed for NN-based
  metrics. **adapted**
- Frequency domain: Lomb-Scargle periodogram of the unevenly sampled NN series, LF 0.04–0.15 Hz,
  HF 0.15–0.40 Hz. HF only with ≥ 60 s, LF only with ≥ 120 s of intervals (Task Force recommends 5 min).
  **adapted**
- Baevsky stress index SI = AMo / (2 · Mo · MxDMn), 50 ms histogram bins. Bands often quoted (< 50 low,
  50–150 normal, 150–500 raised) are tool conventions, not validated norms; a 2024 study found automated SI
  implementations disagree. **adapted**

## Irregular rhythm screening (`rhythm.py`)

- Industry approach: Apple's Irregular Rhythm Notification classifies ~1-minute PPG *tachograms* (beat-interval
  series) recorded while the user is still, and only notifies after 5 of 6 irregular tachograms (FDA
  DEN180042); Fitbit requires 11 consecutive irregular windows (~30 min). Reported sensitivity vs ECG patch
  ~88%, PPV of a positive tachogram ~71%.
- Ours: one tachogram per measurement, scored with Dash et al. 2009's three RR statistics:
  normalized RMSSD (RMSSD / mean RR), Shannon entropy of a 16-bin RR histogram (8 longest and 8 shortest
  removed, normalized by ln 16), and the turning point ratio tested against a random series
  (μ = 2(N−2)/3, σ = √((16N−29)/90), 95% band). Dash reported 94.4% sensitivity / 95.1% specificity on
  MIT-BIH AF (ECG, 128-beat segments).
- Thresholds used: NRMSSD > 0.10 and ShE > 0.70 and TPR within the random band → `possible_af`.
  These are the values commonly used in reimplementations; we could not extract the paper's table, so they
  are **not verified against the original**. Ectopic beats (≥ 3, or > 5% of beats) or NRMSSD > 0.10 without
  randomness → `irregular`. Fewer than 30 clean intervals → `insufficient_data`. **heuristic** overall:
  single short PPG windows, no confirmation cycle, not validated on this watch. Always shown with a
  not-a-diagnosis note.

## Breathing rate (`respiration.py`)

- Three respiratory modulations of the PPG (Karlen et al. 2013; Charlton et al. reviews): RIIV (baseline
  intensity at each beat), RIAV (beat amplitude), RIFV (beat interval, i.e. respiratory sinus arrhythmia).
  Each is resampled at 4 Hz, band-passed 0.1–0.5 Hz, and its spectral peak taken; estimates are fused weighted
  by peak sharpness, keeping the agreeing pair when one disagrees by > 4 br/min (Karlen's "smart fusion" idea;
  newer work fuses with Kalman smoothers, Khreis et al. 2019, or synchrosqueezing, Dehkordi et al. 2018).
  **adapted**; needs ≥ 30 s of still signal. Real sessions gave 7–9 br/min (plausible for seated rest).

## Daily scores (`daily.py`)

- Sleep: per-minute states from the watch's history (Gadgetbridge's kind mapping; REM is approximate).
  Blocks bridge ≤ 30 awake minutes; the night for date D is the longest block ending between D−1 18:00 and
  D 18:00; < 60 min asleep is not a night; a night still in progress is not scored. Efficiency = asleep /
  (block end − start). **adapted**
- Sleep score 0–100: duration 50 (full at 8 h), efficiency 20 (70→95%), deep share 15 (full at 13%), REM share
  15 (full at 20%). **heuristic**, in the spirit of Fitbit/Oura scores.
- Resting HR: lowest 30-minute rolling median during the main sleep (Oura-style "lowest"), or the watch's own
  resting-HR series when available. **adapted**
- HRmax: profile, else Tanaka 208 − 0.7·age; without either, zones/strain/VO2max stay null.
  Zones: 50–60–70–80–90% HRmax. **solid** (Tanaka); zone edges are a convention.
- TRIMP: Banister, HRr · a · e^(b·HRr) per minute (men a=0.64, b=1.92; women 0.86, 1.67), counted from
  50% HRmax. **solid** (formula), applied to 1-minute watch HR.
- Strain 0–21 = 21 · (1 − e^(−TRIMP/120)). WHOOP describes strain as non-linear/logarithmic and TRIMP-like but
  does not publish it; our constant is a guess calibrated so a typical active day is ~10. **heuristic**
- Active calories: Keytel et al. 2005 HR equation (needs age, sex, weight), only minutes ≥ 60% HRmax because
  it is an exercise equation and overestimates at everyday heart rates. **adapted**
- Readiness 0–100 = 70 + 15 · weighted z: HRV vs 28-day baseline (0.35), resting HR vs baseline (0.30,
  inverted), sleep score (0.25), yesterday's strain above 14 (0.10); weights renormalized over what is
  available; needs resting HR or sleep. Mirrors how Oura/WHOOP/Garmin combine HRV, RHR, sleep and load, but
  the weights are ours. **heuristic**
- Body battery: starts at 20 + 0.8 · sleep score at wake-up, drains 0.06/min awake plus 0.05–0.5/min by HR
  zone, recharges 0.15/min in naps. Garmin's is Firstbeat's HRV-based model (and has been criticized as a
  "made-up score"); ours is a simple energy-budget imitation. **heuristic**
- Sleep Regularity Index (Phillips et al. 2017): −100 + 200 · P(same sleep/wake state 24 h apart) over minute
  pairs with data; needs ≥ 6 h of pairs. **solid**
- VO2max (Uth et al. 2004): k · HRmax / HRrest, k = 15.3 men, 14.5 women (later cross-validation, SEE ~7%),
  15.0 unknown; HRrest = 7-day mean resting HR. Derived in trained men; known to overestimate with very low RHR.
  **adapted**. Fitness age: inverse of a linear fit to population VO2max norms. **heuristic**
- Acute:chronic workload ratio: mean daily TRIMP 7 d / 28 d, only after ≥ 21 days of data; > 1.5 flagged.
  **adapted** (the ACWR injury link itself is debated).
- Alerts: resting HR ≥ 5 bpm over baseline (+ HRV < 85% of baseline when known) like Oura's Symptom Radar /
  WHOOP's illness signals; short night; load jump; long unworn stretches; low SpO2 average (< 92%) when the
  watch provides SpO2; last measurement `possible_af`. **heuristic**

## Validation on this watch (2026-11-01)

| recording | watch HR | our HR | SQI | RMSSD | rhythm |
|---|---|---|---|---|---|
| 40 s capture, seated | 72–88 (mean 78) | 85.5 | 0.91 | 31 ms | regular |
| 65 s, still | — | 78.4 | 0.95 | 45 ms | regular |
| 5 min, with movement | — | 81.7 | 0.70 | 69 ms | regular |

The 40 s capture's spectral peak independently gave 83–89 bpm; the watch's own number lags (it is smoothed),
so a 5–7 bpm gap over a rising heart rate is expected. No ECG reference was available.

## Sources

- Task Force of ESC/NASPE (1996), Heart rate variability: standards of measurement. Circulation 93:1043.
- Li Q, Clifford GD (2012), Dynamic time warping and machine learning for signal quality assessment of PPG.
- Dash S, Chon KH, Lu S, Raeder EA (2009), Automatic real time detection of AF. Ann Biomed Eng 37:1701.
  https://biosignal.uconn.edu/wp-content/uploads/sites/2503/2018/09/10_Dash_2009_AnnalsBME.pdf
- Apple, Using Apple Watch for Arrhythmia Detection (white paper).
  https://www.apple.com/healthcare/docs/site/Apple_Watch_Arrhythmia_Detection.pdf ; FDA DEN180042.
- Karlen W et al. (2013), Multiparameter respiratory rate estimation from the PPG. IEEE TBME 60:1946.
- Dehkordi P et al. (2018), Extracting instantaneous respiratory rate from multiple PPG respiratory-induced
  variations. Front Physiol 9:948. https://www.frontiersin.org/article/10.3389/fphys.2018.00948/full
- Khreis S, Ge D, Carrault G (2019), CinC. https://www.cinc.org/2019/Program/accepted/41.html
- Baevsky RM; SI formula summary https://chomsky.uni-trier.de/android/apps/myapps/hrv/assets/html/baevsky_si.html ;
  2024 SI comparison https://www.accscience.com/journal/BH/2/4/10.36922/bh.3503
- Phillips AJK et al. (2017), Irregular sleep/wake patterns... Sci Rep 7:3216 (Sleep Regularity Index).
- Uth N et al. (2004), Estimation of VO2max from the ratio between HRmax and HRrest. Eur J Appl Physiol 91:111;
  sex-specific factor https://www.thieme-connect.de/products/ejournals/pdf/10.1055/s-2005-837443.pdf
- Tanaka H, Monahan KD, Seals DR (2001), Age-predicted maximal heart rate revisited. JACC 37:153.
- Banister EW (1991) TRIMP; Edwards S (1993) zone TRIMP.
- Keytel LR et al. (2005), Prediction of energy expenditure from heart rate monitoring. J Sports Sci 23:289.
- WHOOP strain/TRIMP discussion: https://the5krunner.com/2022/05/24/whoop-strain-everything/embed/
- Garmin Body Battery / Firstbeat and critique: https://the5krunner.com/2025/08/19/garmin-body-battery-slammed-indirectly-by-altini-made-up-scores/
