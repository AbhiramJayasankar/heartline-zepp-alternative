# The watch's event log ("statistics", fetch type 0x2C)

The watch keeps a log of its own events and hands it over as the statistics fetch. Its buffer empties on transfer.
Code: `core/protocol/.../WatchStatistics.kt`.

## Format

Frames of `fe ef | u16 length | protobuf`, back to back. The protobuf is what the Zepp app calls
`Hmstatistics.EventsStatistics`: 1 serial, 2 device source, 3 device id, 4 version, 5 repeated events. Each event
(`EventHole`) has 1 id, 2 a wall-clock time, 3 a session time, 4 reserved, 5 repeated `{1 key, 2 value}` params. Both
times are the watch's local time counted as seconds since 1970: read as epochs they are 5:30 h late in IST, so
Heartline converts them with the phone's time zone. (Until 2026-11-04 field 3 was taken as a real epoch and the
`watch_workout` rows were stored 5:30 h late; the backfill re-reads them.) Zepp uploads the log without
reading it, so the ids have no names in the app.

## Identified ids

Matched against the saved workouts of 2026-11-02 (strength training 22:13–23:22, treadmill 23:22–23:40 IST):

| Id | Params | Meaning |
|---|---|---|
| `0x0801` | 80 = workout type code | Workout opened or started (also logged for a workout opened and not saved) |
| `0x0901` | 32 = workout type code | Workout ended |

Heartline stores these as `watch_workout` rows (start/end, type) and, with auto capture on, starts and stops a
capture from them. The statistics fetch runs in the 5-minute quick sync, so the capture starts a few minutes late.

## Not identified yet (two days of the owner's log, 7,950 events)

| Id | Count | Typical gap | Params seen |
|---|---|---|---|
| `0x1800` | 4,680 | 7 s | 320 = 0 (mostly), 3, 2 |
| `0x1801` | 2,516 | 37 s | 321 = 11, 10, 2, ...; 322 = 0 / 1 |
| `0x1500` | 222 | 51 s | 272 = 2 |
| `0x1a01` / `0x1a02` | 171 / 124 | | 353 = 1; 354 = 1 / 2 |
| `0x1401` | 161 | 6 s | 257 = 14 / 1; all during the Alexa tests of 2026-11-03 |
| `0x1001` | 10 | 30 min | 177 = 1; on the hour and half hour |
| `0x1300`, `0x0300`, `0x0100`, `0x1700`, `0x1b00`, `0x0805`, `0x080b` | few | | |

Telling these apart needs a session of known actions on the watch (open apps, raise the wrist, get notifications,
use the menu) followed by a fetch.
