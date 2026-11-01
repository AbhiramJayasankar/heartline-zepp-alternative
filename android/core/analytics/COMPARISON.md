# Python/Kotlin golden comparison

29 JUnit tests passed; no failures or skips.

The paired values below are **Python / Kotlin**. pNN50 is in percentage points; HR is bpm; RMSSD and SDNN are milliseconds; respiration is breaths/minute.

| Recording | HR | RMSSD | SDNN | pNN50 | Beats | SQI | Rhythm | Ectopic | Respiration |
|---|---:|---:|---:|---:|---:|---:|---|---:|---:|
| 20261101-023830.csv | 78.4/78.4 | 44.8/44.8 | 53.5/53.5 | 23.1/23.1 | 84.0/84 | 0.952/0.952 | regular/regular | 0.0/0 | 8.7/8.7 |
| 20261101-024059.csv | 81.7/81.7 | 69.1/69.1 | 153.8/153.8 | 17.1/17.1 | 284.0/284 | 0.701/0.701 | regular/regular | 0.0/0 | 7.3/7.3 |
| regular.csv | 60.0/60.0 | 29.3/29.3 | 20.9/20.9 | 0.0/0.0 | 91.0/91 | 1.0/1.0 | regular/regular | 0.0/0 | 15.0/15.0 |
| af_like.csv | 75.8/75.8 | 171.7/171.7 | 144.7/144.7 | 75.9/75.9 | 121.0/121 | 0.975/0.975 | possible_af/possible_af | 9.0/9 | 8.0/8.0 |
| one_ectopic.csv | 75.0/75.0 | 1.9/1.9 | 1.2/1.2 | 0.0/0.0 | 101.0/101 | 1.0/1.0 | regular/regular | 1.0/1 | 16.9/16.9 |
| respiration.csv | 70.6/70.6 | 35.3/35.3 | 28.5/28.5 | 6.9/6.9 | 161.0/161 | 1.0/1.0 | regular/regular | 0.0/0 | 15.0/15.0 |
| short.csv | null/null | null/null | null/null | null/null | 0.0/0 | 0.0/0.0 | insufficient_data/insufficient_data | 0.0/0 | null/null |
| too_few_intervals.csv | null/null | null/null | null/null | null/null | 10.0/10 | 1.0/1.0 | insufficient_data/insufficient_data | 0.0/0 | null/null |
| ten_intervals.csv | 75.0/75.0 | 2.6/2.6 | 1.6/1.6 | 0.0/0.0 | 11.0/11 | 1.0/1.0 | insufficient_data/insufficient_data | 0.0/0 | null/null |
| slow.csv | null/null | 3.3/3.3 | 1.7/1.7 | 0.0/0.0 | 51.0/51 | 1.0/1.0 | regular/regular | 0.0/0 | 15.8/15.8 |

Session tests also compare the complete response tree, including notes, feature flags, RR values, preview samples, beat usability, motion, frequency-domain HRV and respiration components.

| Daily golden | Numeric fields | Maximum absolute difference | Strings/nulls |
|---|---:|---:|---|
| real | 171 | 0.0 | Equal |
| synthetic | 716 | 0.0 | Equal |
| no_profile | 486 | 0.0 | Equal |
| in_progress | 607 | 0.0 | Equal |
| empty | 0 | 0.0 | Equal |

## Constant-input diagnostic

The additional flat, nonzero input has **bit-for-bit equal filtered samples**. NumPy's default unstable argsort chooses a different order for exactly equal-height peaks. Kotlin chooses rightmost first. This changes spurious detections in filter roundoff (~1e-10 raw counts). It is retained as a diagnostic, outside the ten session parity goldens, and is not claimed to meet their session-level tolerances.

Python constant input: beats=76, HR=121.3, RMSSD=82.2, rhythm=irregular.

constant nonzero input: exact filtered samples; NumPy's unstable equal-height peak order differs. Kotlin beats=81, HR=125.0, RMSSD=0.1, rhythm=regular.

## Build scope

