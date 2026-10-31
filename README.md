# Amazfit GTR 2e, without the Zepp app

Talks to the watch directly over Bluetooth LE from Windows, with a local web dashboard.

## Run

1. Turn off Bluetooth on your phone (the watch accepts one connection at a time, and Zepp grabs it).
2. Double-click `run-dashboard.bat`, or run `.venv\Scripts\python.exe -m uvicorn server.app:app --port 8765`.
3. Open http://localhost:8765 and press **Connect**.

`http://localhost:8765/?demo` shows the page with fake data, for layout work.

## What works (verified on firmware V1.7.3.1)

- Auth with the key in `watch.json` (fetched from the Zepp account via Watchface Maker; keep it private, and never unpair the watch in Zepp or the key changes).
- Battery level, charging state, charge timestamps.
- Device info: serial, hardware and software revision.
- Live heart rate (continuous mode, a reading every 1–5 s once locked), stored in `data/watch.db`.
- Raw sensors: optical PPG (u16, ~25 Hz) and accelerometer (int16 x/y/z, 4100 = 1 g, ~25 Hz) on `0x0002`,
  started with `01 03 19`, `01 03 00 00 00 19`, `02` on `0x0001` (stop `03`). Every sample is recorded to `data/raw/*.csv`.
- Activity history: one 8-byte record per minute (kind, intensity, steps, HR, ?, sleep, deep, rem) via `0x0004`/`0x0005`.
  The `03` ack is never sent, because it deletes the fetched data from the watch; Zepp still gets everything.
- Vibrate (find device): `03` to Alert Level `0x2A06`.
- Full GATT table.

- Health data (`POST /api/health/sync`): manual HR, workouts with per-second HR tracks, PAI, automatic stress,
  sleep SpO2, skin temperature (one sample a minute), and a statistics blob (stored raw, not decoded).
  The watch accepts but returns nothing for normal SpO2, manual stress, sleep sessions, resting/max HR, sleep breathing and HRV.
- Live steps (`0x0007`), device events (`0x0010`), watch settings (write-only: the page shows the last value written),
  text notifications (chunked at 20 bytes; bigger writes drop the link), clock sync.
- Insights (`server/analytics/`, methods and their limits in `server/analytics/METHODS.md`): beat detection from the raw PPG,
  HRV, rhythm screening (not a diagnosis), daily readiness, strain, sleep score, body battery, VO2max and fitness age.

## Careful

- Temperature, automatic stress, sleep SpO2 and statistics are handed over once: after a fetch the watch moves past them
  even without the `03` ack, so Zepp no longer gets those readings. The first fetch (2026-11-01) pulled 40 days of
  temperature; the copy lives in `data/watch.db` and `data/discovery/`. Back up `data/`.
- Activity, manual HR, PAI and workouts honour the start time and stay on the watch.

## Not verified yet

- Activity kind to state mapping (REM and "active" minutes are approximations); AF screening thresholds;
  PPG breathing rate (reads 7-9/min, probably Mayer waves rather than breathing).
- Device event `0x13`, the statistics protobuf.

## Layout

- `server/` FastAPI + bleak backend (`server/README.md` has the API).
- `web/` the dashboard (plain HTML/CSS/JS, no build step).
- `*.py` at the top level: the original exploration scripts.
- `tests/` run with `.venv\Scripts\python.exe -m pytest`.
