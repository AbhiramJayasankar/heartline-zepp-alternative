# Working on Heartline

Guide for anyone (person or coding agent) changing this repository. Read it before the first change; the README
describes the app for its users, this file describes the code for its builders.

Heartline is an Android app that replaces the Zepp app for the Amazfit GTR 2e: it talks to the watch over Bluetooth LE
itself, keeps everything on the phone, and analyses the data (sleep, HRV, readiness, workouts, gym sets, runs, rides).

## Layout

| Path | What lives there |
|---|---|
| `android/app` | The app: Compose UI, controllers, background service, AI features |
| `android/app/.../app/` | Controllers and mapping from stored data to UI models (`*Ui` classes in `Controller.kt`) |
| `android/app/.../service/` | The foreground `WatchService`, captures, heart checks, AI (`ai/`, `HealthTools`, `HealthInsights`) |
| `android/app/.../ui/` | `AppRoot.kt` (navigation, `Route`), `screens/` (pages), `components/` (charts, map, kit), `theme/` |
| `ui/screens/` workouts | `WorkoutScreens.kt` (starting a capture), `CaptureResultScreen.kt` (a capture's page), `CaptureSections.kt` (its gym, calories and pulse sections), `GaitSections.kt` / `LapSections.kt` / `RideSections.kt` (on foot and riding), `WatchWorkoutScreen.kt` (a watch workout's page) |
| `android/core/protocol` | Pure Kotlin: the watch's commands and the parsers for what it sends |
| `android/core/ble` | Android: the Bluetooth connection (`WatchConnection`), history transfer, raw sensor recording |
| `android/core/analytics` | Pure Kotlin: every calculation (sleep, HRV, PPG beats, readiness, gait, laps, rides, captures) |
| `android/core/data` | Android: SQLite storage, settings, encrypted secrets, export |
| `android/docs/` | Protocol notes found while building (alarms, Alexa, raw stream bits, workout fields, ...) |
| `server/`, `web/`, `tests/` | The original Python desktop dashboard; `server/analytics` is the reference the Kotlin analytics port |
| `research/` | Protocol exploration scripts and research write-ups (`*.md`) |
| `tools/android/` | Phone helpers for testing on a real device (see below) |
| `CHANGELOG.md` | Every release, newest first, the same text as the GitHub release |

Keep calculations in `core/analytics` (pure functions, unit tested) and keep Android and UI code out of it. The app
layer maps stored data into `*Ui` models; screens only draw them.

## Build and check

From `android/`:

```bash
./gradlew verify                    # every unit test in every module, plus lint: run before each commit
./gradlew :app:compileDebugKotlin   # quick compile check while working
./gradlew :core:analytics:test --tests 'dev.heartline.analytics.LapsTest'   # one test class
./gradlew :app:installDebug         # install on the connected phone (ANDROID_SERIAL=<serial> to pick one)
```

`verify` is what CI runs. Note that `testDebugUnitTest` alone skips the pure Kotlin modules (`core/analytics`,
`core/protocol`): they only have `test`. Python reference tests run with `pytest` from the repository root.

## Wiring checklist: things that have been missed before

Most bugs here have been a new piece wired into some places and not others. When you add or change:

- **A controller method or flow**: add it to `HeartlineController` (`Controller.kt`), implement it in `RealController`
  and in `DemoController` (the demo mode and the UI previews use it).
- **A health topic the AI can read**: add it to `HealthTools.TOPICS` and its `run` branch. If the daily insights should
  see it, add it to `HealthInsights.DIGEST`. `InsightsTest` checks every digest section answers; a lookup that
  answers null makes the digest fail loudly instead of quietly dropping the section (that hid HRV from the insights
  for weeks in Nov 2026).
- **Something a capture is read as** (on foot, a ride, gym): decide it in one function in `CaptureGait.kt` and call
  it from both the list (`CaptureMapping.captureItem`) and the page (`WorkoutScreens`), so the two agree. Tell the
  AI too (`HealthTools.captures`).
- **The capture analysis itself** (`analyzeCapture` and what it stores): bump `CAPTURE_ENGINE_VERSION` so stored
  sessions are analysed again. Things computed when a page is opened (gait, laps, rides) need no bump.
- **A watch setting**: its default in `core/protocol/Settings.kt` (`settingDefault`), its UI in `WatchScreens.kt`.
- **A new page**: a `Route` in `AppRoot.kt` and its branch in the route switch.
- **A map or chart feature**: `RouteMap` / `InteractiveChart` are shared by captures and watch workouts; check both.
- **PPG / HRV analysis**: `server/analytics` is the reference. Change both and regenerate the goldens
  (`core/analytics/src/test/resources/goldens`), or explain in the commit why they now differ.

## Testing on the phone

Use the real phone, never an emulator: it slows the owner's PC. Find it with `adb devices`.

- `tools/android/tap.sh "<text>"` taps the element whose text or description starts with it. Layouts shift (the
  heart-check banner comes and goes), so locate an element right before each tap.
- `tools/android/screens.sh <name> <n>` takes `n` screenshots scrolling down and stitches them.
- Screenshot UI work in a dark and a light theme, and at phone width.
- The phone may be shared by several sessions at once: agree on who has it before installing or tapping.
- Copies of the app's database (`adb exec-out run-as dev.heartline cat databases/heartline.db`) are personal health
  data. Keep them outside the repository and delete them when done. Never copy `heartline_auth.xml` or
  `heartline_secret_*.xml`.

## Rules for the watch

- Never send the activity-fetch acknowledgement (`0x03` after a history fetch): it deletes the data from the watch.
- Never unpair or reset the watch, and don't change the phone's system settings.
- The watch takes one connection at a time; the app's "Let Zepp connect" hands it over for 15 minutes.

## Releasing

Each change that ships:

1. Bump `versionCode` and `versionName` in `android/app/build.gradle.kts` (patch for fixes, minor for features).
2. Add the release's entry at the top of `CHANGELOG.md` (Added / Changed / Fixed, written for the person using the app)
   and update `README.md` where a feature is described.
3. `./gradlew verify`, then `./gradlew assembleRelease`.
4. Commit, push to `main`, wait for CI, then
   `gh release create v<version> android/app/build/outputs/apk/release/app-release.apk --title "Heartline <version>" --notes-file <entry>`.

Commits and releases carry only the author's own name: no AI co-author lines or "generated with" notes.

## Writing

UI text and release notes are plain, specific sentences for the person wearing the watch: what they see and what it
means, with the numbers. No marketing words, no exclamation marks, sentence case. Comments in code say why, and cite
the data a threshold came from (as `Laps.kt` and `Ppg.kt` do).
