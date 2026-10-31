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
- Full GATT table.

## What does not (yet)

- Raw accelerometer / PPG stream: control char `0x0001` answers `10 01 03 04` to every start command and sends no data.

## Layout

- `server/` FastAPI + bleak backend (`server/README.md` has the API).
- `web/` the dashboard (plain HTML/CSS/JS, no build step).
- `*.py` at the top level: the original exploration scripts.
- `tests/` run with `.venv\Scripts\python.exe -m pytest`.
