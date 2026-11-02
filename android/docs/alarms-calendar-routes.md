Task B adds persisted alarms, calendar-to-watch reminders, and stored workout GPS routes. No commit was made. No phone or watch was accessed. UI files, Controller.kt, DemoController.kt, AppSettings, the database schema, and existing fetch/notification formats are unchanged.

**Files**

All paths below are relative to `android/`.

| Created | Purpose |
| --- | --- |
| `core/protocol/src/main/kotlin/dev/heartline/protocol/Alarms.kt` | Alarm encoding, device limits, success reply validation |
| `core/protocol/src/main/kotlin/dev/heartline/protocol/Reminders.kt` | Calendar event/clear encoding and title limit |
| `core/protocol/src/main/kotlin/dev/heartline/protocol/WorkoutRoutes.kt` | GPS base coordinates, deltas, timestamps and altitude |
| `app/src/main/java/dev/heartline/app/AgendaController.kt` | Persisted UI flows and edits |
| `app/src/main/java/dev/heartline/app/AgendaPreferences.kt` | Feature-prefixed meta keys and JSON storage |
| `app/src/main/java/dev/heartline/app/AgendaPolicy.kt` | Calendar selection, all-day times and per-slot change detection |
| `app/src/main/java/dev/heartline/app/AgendaSchedule.kt` | Connection, refresh and retry scheduling |
| `app/src/main/java/dev/heartline/app/CalendarProvider.kt` | Visible calendars and recurring event instances |
| `app/src/main/java/dev/heartline/app/WorkoutRouteMapping.kt` | Match raw summary/detail blobs to workouts |
| `app/src/main/java/dev/heartline/service/WatchAgendaSync.kt` | Service-owned BLE sync and debounced ContentObserver |
| `core/protocol/src/test/kotlin/dev/heartline/protocol/{AlarmsTest,RemindersTest,WorkoutRoutesTest}.kt` | Byte vectors, synthetic GPS, captured regressions |
| `app/src/test/java/dev/heartline/app/{AgendaPolicyTest,AgendaScheduleTest,WorkoutRouteMappingTest}.kt` | Mapping, scheduling, offline edits and change detection |
| `core/protocol/src/test/resources/workout_routes_captured.json` | Seven real read-only summary/detail pairs |
| `TASK_B_REPORT.md` | This handoff |

| Changed | Scope |
| --- | --- |
| `app/src/main/java/dev/heartline/app/RealController.kt` | Task B stub replacement; raw-route flow and workout mapping only |
| `app/src/main/java/dev/heartline/service/WatchService.kt` | Three lifecycle wiring lines |
| `core/ble/src/main/java/dev/heartline/ble/WatchConnection.kt` | Two additional commands using the existing action mutex, notification routing and GattClient queue |
| `app/src/main/AndroidManifest.xml` | READ_CALENDAR permission |

**Behavior and storage**

Alarms use `alarms_list` and `alarms_written`. The desired sorted JSON list is the source of truth. A difference between desired bytes and the last completed write represents a pending edit or deletion, including across process restarts. Every ready connection replays all ten slots, clearing unused slots. Edits during a connection trigger an immediate refresh; saved alarms retain their time/day mask when disabled. Smart wake-up requests are normalized to false because this coordinator does not support it. Snoozing keeps its enabled default.

Calendar preferences use `reminders_settings` (enabled, selected calendar IDs, refresh revision), `reminders_written` and `reminders_last_sync`. Repeating setReminderSync after a permission grant wakes the service even with an unchanged selection. ReminderSyncUi exposes 22 slots and current permission/calendar state. Queries exclude canceled instances, intersect selected IDs with visible calendars, retain recurring instances, sort upcoming starts, and take the next 14 days capped at 22. All-day UTC dates become 09:00 in the phone's local zone, with the local exclusive end date retained. Titles use at most 16 UTF-16 characters without splitting a surrogate pair; embedded NULs are removed from the text.

The foreground service syncs on ready connection, settings changes, a 1.5-second debounced calendar provider notification, and every 30 minutes while ready. Failed refreshes retry after 30 seconds; disconnect cancels in-flight work. Only changed reminder slots are sent, with each completed slot persisted separately. Disabling clears managed reminders. Never-enabled sync leaves existing watch reminders alone. Losing calendar permission preserves watch slots instead of interpreting an unavailable query as an empty calendar. `lastSync` advances on successful slot writes; unchanged refreshes leave it intact.

Routes join the latest raw summary (by its encoded workout start) to the latest workout_details fetch with that start. Decoding runs off the main thread, updates when raw fetches change, and leaves HR parsing untouched. A missing summary/detail blob or GPS base yields an empty route. No schema or workout JSON changes are needed.

**Byte formats and source**

The read-only Gadgetbridge checkout is commit `b4f1eb5a8a6bcb85e51895f083dbb98f36d2bb9d`. Source comments and test comments name the relevant methods.

| Format | Bytes / interpretation | Gadgetbridge source |
| --- | --- | --- |
| Alarm | `02 (slot OR enabled*80) hour minute days`; Monday bit 0 through Sunday bit 6; `80` for a one-shot alarm. Slot 0..9. Bit 40 is omitted to retain snoozing. | HuamiSupport.queueAlarm; model.Alarm day constants |
| Delete alarm | `02 slot 00 00 00`: disabled, unused day mask, midnight | HuamiSupport.queueAlarm unused branch |
| Alarm reply | Require CONFIG notification prefix `10 02 01`, including success status; reject `10 02 00`, truncated or unrelated replies | HuamiSupport.queueAlarm success-reply comment; existing WatchConnection.applySetting routing pattern |
| Alarm capability | Ten slots; no smart wake-up field | AmazfitGTR2eCoordinator inherits HuamiCoordinator.getAlarmSlotCount and AbstractDeviceCoordinator.supportsSmartWakeup |
| Calendar event | `0B slot LE32(0000000D) start[6] end[6] 00 UTF8(title) 00`, where each date is `LE16(year),month,day,hour,minute`. Flags: enabled 01, end present 04, text 08. | HuamiSupport.sendCalendarEventsAsReminders; BLETypeConversions.shortCalendarToRawBytes |
| Clear calendar slot | `0B slot LE32(00000008)` followed by fourteen zero bytes (20 total) | HuamiSupport.sendCalendarEventsAsReminders clearing loop |
| Reminder capacity/title | 22 slots and 16 UTF-16 characters | AmazfitGTR2eCoordinator inherits HuamiCoordinator.getReminderSlotCount/getMaximumReminderMessageLength; StringUtils.truncate |
| Calendar transport | CHUNKED type 2 at MTU 23: 17 payload bytes per write, prefix `00 flags counter`; first non-final flags 02, continuation 42, last 82. Calendar payloads use their direct type-2 header; the existing notification four-zero header remains untouched. No device-level calendar ACK is specified by this method. | HuamiSupport.writeToChunkedOld and sendCalendarEventsAsReminders |
| GPS base | Summary offsets 12,16,20: signed LE32 longitude, latitude, altitude | HuamiActivitySummaryParser.parseBinaryData |
| GPS records | Eight bytes: `00 offset dLon:LE16 dLat:LE16 dAlt:LE16`, signed deltas accumulated on the base. Divide lon/lat by 3,000,000 with six-decimal HALF_UP rounding. Altitude is metres; base -20000 means unavailable and altitude deltas are ignored. | HuamiActivityDetailsParser.consumeGPSAndUpdateBaseLocation; AbstractHuamiActivityDetailsParser.convertHuamiValueToDecimalDegrees |
| GPS time | All record offsets advance the cumulative seconds: subtract previous offset while increasing, otherwise add the new offset. Preserve distinct positions at equal timestamps, merge equal rounded positions, and spread the initial equal-time GPS burst using Gadgetbridge's timestamp repair. Returned t is seconds relative to the workout start. | HuamiActivityDetailsParser.parse/getActivityPointFor/fixupMissingTimestamps; GPSCoordinate.equals |

Examples: an enabled one-shot alarm in slot 9 at 06:45 is `02 89 06 2D 80`; deleting slot 9 is `02 09 00 00 00`. Calendar encoding, timezone conversion, Unicode limits and MTU chunk boundaries have independent expected-byte tests.

The two source files outside the sparse checkout, BLETypeConversions.java and StringUtils.java, were read from Codeberg raw URLs. The checkout was not changed. Reference: [HuamiSupport](https://codeberg.org/Freeyourgadget/Gadgetbridge/src/commit/b4f1eb5a8a6bcb85e51895f083dbb98f36d2bb9d/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/huami/HuamiSupport.java), [HuamiActivityDetailsParser](https://codeberg.org/Freeyourgadget/Gadgetbridge/src/commit/b4f1eb5a8a6bcb85e51895f083dbb98f36d2bb9d/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/huami/HuamiActivityDetailsParser.java).

**Captured fixtures and validation**

`C:/Users/abhir/Desktop/projects/amazfit-gtr2e/data/watch.db` was opened only with Python sqlite3 URI `file:.../watch.db?mode=ro`. All seven workout_details rows were paired with their stored summaries and copied into the test resource. None contains type-0 GPS records; all seven summaries have zero base longitude/latitude. Real-capture tests confirm empty routes and retained HR output. GPS-positive tests are synthetic vectors derived from Gadgetbridge, including signed deltas, negative coordinates, unknown altitude, timestamp resets, initial GPS bursts, HR anchors and rounded-coordinate merging.

Final validation on 2026-11-02: all required tasks passed together after the last code change.

| Task | Result |
| --- | --- |
| `:core:protocol:test` | 105 passed |
| `:core:ble:test` | 23 passed |
| `:app:testDebugUnitTest` | 32 passed |
| `:app:assembleDebug` | Passed; `app/build/outputs/apk/debug/app-debug.apk` |
| `:app:lintDebug` | Passed; 0 errors, 6 existing warnings, 1 existing hint |

Total: 160 tests, 0 failures/errors/skipped, including 39 new tests (23 protocol, 16 app). `git diff --check` also passed.

Actual sandbox invocation from `android/` used the requested task list with the following local environment adjustments:

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
export GRADLE_USER_HOME="C:/Users/abhir/.gradle"
export ANDROID_USER_HOME="C:/Users/abhir/Desktop/projects/hl-b/android/build/task-b/android-user"
./gradlew --no-daemon -Duser.home=C:/Users/abhir \
  -Pkotlin.compiler.execution.strategy=in-process \
  -I build/task-b/signing.gradle --continue \
  :core:protocol:test :core:ble:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

The ignored local.properties drive colon was escaped for lint. Android's default debug-keystore lock could not be opened in this restricted session, so an ignored init script selects a copy of the existing debug keystore at `build/task-b/task-b.keystore` through androidComponents.finalizeDsl. No tracked build configuration was changed. The sandbox helper remains at ignored `build/task-b/check.sh`; no checks or tests were disabled. Normal unrestricted builds use the brief's standard command.

**Owner checks on firmware V1.7.3.1**

1. Save a one-shot alarm and a Mon/Fri/Sun alarm, including slot 9. Confirm the requested local times, enable/disable state, repetition and snoozing. Delete one and confirm it is unused and does not alert. Edit/delete offline, reconnect, and confirm the persisted phone version wins. Check the event log for missing/rejected CONFIG `10 02 01` acknowledgements.
2. Grant READ_CALENDAR, select a calendar containing recurring and all-day events, and enable sync. Confirm 09:00 local all-day alerts, start/end times, Unicode titles, and slots through 21. The 22-slot limit is inherited from Gadgetbridge and still needs confirmation on this firmware.
3. Edit/delete an event while connected; allow roughly 1.5 seconds plus BLE writes and verify only affected slots update/clear. Reconnect with an unchanged calendar and confirm reminders remain. Disable sync and verify managed events clear. Revoke permission and verify existing reminders are preserved.
4. Leave a connection ready for thirty minutes and verify a newly eligible event appears. A refresh with identical bytes intentionally does not advance lastSync. Calendar success currently means all ATT writes completed; Gadgetbridge does not define a separate event acceptance response, so verify on-watch visibility and alerts.
5. After an outdoor GPS workout is fetched, verify a non-empty route, the correct geographic location, sensible relative timing and altitude. No real GPS-positive blob was available, so first-point placement, initial-burst timing and altitude need this real-workout check. Existing indoor workout routes should remain empty and HR tracks unchanged.
