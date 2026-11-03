# GTR 2e sensor-clock fixture

`probe-1103b.txt` contains the complete four 20-second runs (0x02, 0x82, 0x83, 0x81)
from `scratchpad/probe/ble-1103b.log`, captured 2026-11-03 IST, starting 20:00:30.784.
Extracted by matching `^20:.*probe 0x(02|82|83|81) pkt`; no payload or arrival-order changes.
586 lines / 50,428 bytes, all 293 data/stamp pairs. No private watch address or key is included.

End-stamp rates: 0x02 PPG 25.1002 Hz; 0x82 PPG 39.4849 Hz overall (25->50 Hz transition);
0x83 PPG 50.1446 Hz, accel 26.2076 Hz; 0x81 accel 26.1559 Hz.
See `android/docs/raw-stream-bits.md` for the rate calculation and transition evidence.
