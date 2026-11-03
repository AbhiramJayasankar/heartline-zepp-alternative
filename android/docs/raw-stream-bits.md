# Raw stream: unmapped mask bits and packet type 0x07

Probed on 2026-11-03 (IST) from the Watch screen ("Probe unknown sensors"), GTR 2e, wrist still, 20 s per mask.
Every packet was logged to `files/blelog/2026-11-03.log` as `probe 0xMM pkt <hex>`.

| Mask | Result |
|---|---|
| 0x04 | nothing |
| 0x80 | nothing |
| 0x06 (0x04 + PPG) | nothing: setting 0x04 makes the watch ignore the whole request |
| 0x02 (PPG) | type 0x01 PPG at **25.0 Hz** (sensor clock), level ~27,000 |
| 0x82 (0x80 + PPG) | type 0x01 PPG at **~49 Hz**, same level and same green signal |
| 0x83 (0x80 + PPG + accel) | PPG ~49 Hz plus accel type 0x00 at **26.16 Hz** |
| 0x81 (0x80 + accel) | accel only, 26.16 Hz |

Findings:

- **0x80 doubles the PPG rate to about 50 Hz.** It is the same green channel (same level, same pulse); there is no red
  or infrared channel behind 0x04 or 0x80. Content above 12.5 Hz and smooth sample-to-sample steps suggest genuine
  50 Hz sampling rather than interpolation; a longer still recording would confirm it.
- **The first ~10 PPG samples after a start sit near 3,800** while the LED settles. Drop them.
- **Packet type 0x07 is a sensor timestamp**, one per data packet: `07 | seq | sensor (1 = PPG, 0 = accel) |
  u32 LE milliseconds on the watch clock | 00 00 00 00`. Pairing it with the following data packet gives each sample
  the watch's own time, instead of reconstructing a clock from jittery BLE arrival times.
- **The accelerometer runs at 26.16 Hz, not 25 Hz** (`RAW_RATE_HZ`). The capture engine resamples accel on arrival
  clocks, so set/rep timing is unaffected, but anything assuming 25 accel samples per second is 4.6 % off.

Implications: motion-artifact cancellation with a second wavelength is not possible through these bits. 50 Hz PPG
halves beat-timing quantisation (40 ms to 20 ms) for still-moment rhythm checks; it does not help during movement.
