# GTR 2e dashboard backend

FastAPI server that owns the single Bluetooth LE connection to the watch and serves the dashboard in `web/`.

## Run

```
C:\Users\abhir\Desktop\projects\amazfit-gtr2e\.venv\Scripts\python.exe -m uvicorn server.app:app --port 8765
```

Open http://localhost:8765. The watch config (MAC, auth key) is read from `watch.json` next to `server/`, else
`C:\Users\abhir\Desktop\projects\amazfit-gtr2e\watch.json`, or from the path in `WATCH_CONFIG`. Heart-rate samples
and the event log are stored in `data/watch.db`.

The watch accepts one connection at a time: turn off the phone's Bluetooth (or close Zepp) before connecting.

## Tests

```
C:\Users\abhir\Desktop\projects\amazfit-gtr2e\.venv\Scripts\python.exe -m pytest
```

No Bluetooth needed; the API tests use a fake watch.

## API

| Method | Path | Returns |
|---|---|---|
| GET | `/api/status` | connection state, battery, HR state, rssi |
| POST | `/api/connect` | starts connecting in the background (3 attempts), returns status |
| POST | `/api/disconnect` | stops HR, disconnects |
| GET | `/api/battery` | re-reads battery: level, charging, two last-charge timestamps, raw hex |
| GET | `/api/device-info` | serial, hardware, software, system id, PnP id |
| POST | `/api/hr/start`, `/api/hr/stop` | `{"streaming": bool}` |
| GET | `/api/hr/history?minutes=60` | stored samples (bpm > 0) and min/max/avg/count |
| GET | `/api/gatt` | live GATT table (cached copy when disconnected) |
| POST | `/api/raw/probe` | experimental raw-sensor probe, ~5 s |
| GET | `/api/events?limit=100` | event log, newest first |

`409 {"error"}` when the watch is not connected, `500 {"error"}` for Bluetooth failures.

WebSocket `/ws` sends `{"type": "status", ...}` on connect and on every change, `{"type": "hr", "ts", "bpm"}` for
every heart-rate notification (including 0 while the sensor locks on), and `{"type": "event", "ts", "level", "msg"}`.

If the link drops while connected, the server keeps reconnecting every 10 s and resumes live heart rate if it was on.
