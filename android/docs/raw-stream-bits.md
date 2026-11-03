# Raw stream: mask bits, rate byte, packet type 0x07 and true sample rates

Probed on the GTR 2e on 2026-11-03 (IST) from the Watch screen ("Probe unknown sensors"), wrist still. Every packet was
logged to `files/blelog/2026-11-03.log` as `probe 0xMM [rRR] pkt <hex>`. Rates below come from the watch's own
timestamps (type 0x07), not from BLE arrival times.

## Mask bits

| Bit | Meaning |
|---|---|
| 0x01 | accelerometer, packet type 0x00, **26.1-26.2 Hz** (not 25) |
| 0x02 | green PPG, packet type 0x01, normally **25.1 Hz** |
| 0x04 | **switches the PPG off** (0x07, 0x7f give no PPG; 0x16 gave 4 packets then nothing); other sensors keep streaming. Alone it returns nothing. |
| 0x08 | GPS NMEA text, type 0x03 |
| 0x10 | gyroscope, type 0x04, 26.1-26.2 Hz |
| 0x20 | microphone, type 0x05 (Opus SILK) |
| 0x40 | magnetometer, type 0x06, 23.8-25.2 Hz |
| 0x80 | **no observable effect.** Alone it returns nothing; with PPG the rate stayed 25 Hz (0x83 at 20:14, 0xd3 at 20:15). |

## The PPG rate is set by the watch, not by us

During probe 2 the PPG switched from 25 Hz to ~50 Hz at **20:01:00.8**, mid-run, with no command from the phone (the
only traffic was the routine per-minute steps notification), and stayed at 50 Hz for at least 33 s (masks 0x82 and
0x83). It was 25 Hz again by 20:13. It is not every minute (probe 3 crossed three minute boundaries at 25 Hz). Most
likely one of the watch's own scheduled measurements raises the optical sensor's rate; earlier "PPG ~50 Hz with more
sensors on" notes in the README were probably the same effect. Consequences:
- 50 Hz cannot be requested with any known mask bit or the rate byte.
- **The PPG rate can change in the middle of a stream**, so sample times must come from the watch timestamps (below),
  not from an assumed fixed rate. The 2 Nov gym capture was 25 Hz throughout (checked: no 10 s window above 35 Hz).

## Rate byte

The last byte of both start commands (`01 mask 19`, `01 mask 00 00 00 19`) is 0x19 = 25 in every client. Sending 0x32,
0x64 or 0x0a changes nothing: accel stays 26.2 Hz and PPG 25.1 Hz. The watch replies `10 01 03 04` and `10 01 01 04`
to the two start commands whatever the mask or rate (also for masks that return nothing).

## Packet type 0x07: sensor timestamps

`07 | seq | sensor | u32 LE milliseconds (watch clock) | 00 00 00 00`, where `sensor` is the data packet type it
belongs to (0 accel, 1 PPG, 4 gyro, 6 mag). **It arrives right after its data packet and marks that packet's last
sample**: the step between consecutive stamps equals the packet's own sample count times the sample period
(correlation 0.99).

## Other notes

- The first ~10 PPG samples after a start sit near 3,800 while the LED settles. Drop them.
- No red or infrared channel appeared under any mask: every PPG run sits at the same green level (~27,000-28,000).

## Test fixture

`core/ble/src/test/resources/raw/probe-1103b.txt` holds the complete probe-2 runs (masks 0x02, 0x82, 0x83, 0x81;
586 packets in arrival order). Measured on the watch clock: 0x02 PPG 25.10 Hz; 0x82 PPG 25.13 Hz for the first 7.8 s
then 50.16 Hz (the watch's own switch at 20:01:00.8, counters continuous, no packet loss); 0x83 PPG 50.14 Hz and
accel 26.21 Hz; 0x81 accel 26.16 Hz.

## Recording and compatibility

`RawSampleClock` keeps one pending packet per sensor. On its stamp it writes sample `i` at
`watchEnd - (n-1-i)*period`. It estimates each sensor's period from up to 15 consecutive
stamp/count intervals, excludes outliers around the median and weights by sample count.
Three consistent changed intervals reset the estimate, allowing the 0x82 rate switch. The
initial rates are PPG 25 Hz and accel 26.16 Hz. Each authoritative end stamp also bounds
the packet period to prevent overlap with the preceding stamped packet.

A robust running affine fit maps watch seconds to phone epoch seconds using up to 120 s /
256 stamp-arrival pairs. It excludes delayed BLE bursts, bounds drift to +/-1000 ppm and
slews phase corrections at at most 1 ms/s. `ts` remains epoch seconds; trailing `wts` records
unwrapped watch seconds at full precision. Timestamps are monotonic **within each sensor**;
interleaved sensor batches are not globally sorted CSV rows.

Missing stamps fall back to the packet's original arrival time and measured/nominal period
when the next same-sensor packet arrives, after 1.5 s, or on stop. Those rows have empty `wts`.
Counter offsets reject late/duplicate stamps for an already emitted packet. Unsigned counters
and the 32-bit watch clock wrap naturally; skipped counters do not enter the rate estimator.
Stream restarts flush the pending tail and reset pairing/rate state. Reconnects get new CSVs
and a new epoch fit; capture merging retains their epoch gaps even if the watch rebooted.
A backward watch-clock reset without a new recording rejects stamps and uses arrival
fallback until a new recording; it does not reinterpret the reset as a 49-day forward jump.

The CSV reader prefers watch time, translates empty `wts` cells through nearby epoch/watch
anchors, and preserves sensor alignment. If a delayed fallback block overlaps later watch
stamps, only that block is interpolated between its neighboring anchors to preserve order.
It excludes the first 0.4 s of each PPG run and any
remaining low settling samples (<40% of the following second's median, within the first 2 s)
from analysis only. Every raw value, including the initial ~3,800-level samples, remains in CSV.
Files without `wts` use the unchanged legacy path, including retiming and warm-up behavior.

`FAST_PPG_ENABLED` in `RawPpgMode.kt` is the one switch for adding 0x80 to captures, the live raw view and
manual checks. It is **off**, because 0x80 has no effect (probe 3). If it were on, after ~5 s with 0x80 and no PPG
the connection would log a warning and restart once without it. Explicit mask probes are always exact requests.
