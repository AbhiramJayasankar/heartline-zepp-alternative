<p align="center">
  <img src="docs/images/screens.png" alt="Heartline app screens: readiness, live heart rate, activity and sleep, trends, watch controls" width="100%">
</p>

<h1 align="center">Heartline: an open-source Zepp app alternative for the Amazfit GTR 2e</h1>

<p align="center">
  Use your <b>Amazfit GTR 2e without the Zepp app</b>. Heartline is an Android app that talks to the watch directly
  over Bluetooth LE and keeps every reading on your phone: heart rate, sleep stages, SpO2, stress, skin temperature,
  workouts, notifications, alarms, weather and the watch's Alexa button. No account, no cloud.
</p>

<p align="center">
  <a href="https://github.com/AbhiramJayasankar/heartline-zepp-alternative/releases/latest"><img alt="Download APK" src="https://img.shields.io/github/v/release/AbhiramJayasankar/heartline-zepp-alternative?include_prereleases&label=download%20APK&color=e5484d"></a>
  <img alt="Android 12+" src="https://img.shields.io/badge/Android-12%2B-3ddc84?logo=android&logoColor=white">
  <img alt="Kotlin and Jetpack Compose" src="https://img.shields.io/badge/Kotlin-Jetpack%20Compose-7f52ff?logo=kotlin&logoColor=white">
  <a href="LICENSE"><img alt="MIT license" src="https://img.shields.io/badge/license-MIT-blue"></a>
</p>

> **Status: alpha.** Built and used daily on one Amazfit GTR 2e (firmware V1.7.3.1) with a Samsung Galaxy S24 Ultra.
> Other Huami/Zepp watches of the same generation (GTR 2, GTS 2, GTS 2e) speak a similar protocol but are untested.

## Why

The Zepp app wants an account, uploads your health data, and shows you a fraction of what the watch measures.
The watch itself records far more: per-minute skin temperature, the raw optical pulse waveform, accelerometer,
gyroscope, compass, GPS and microphone streams. Heartline reads all of it straight from the watch and turns it into
numbers you can use, on the phone, offline.

## Features

**Health and sleep**
- **Readiness, strain, sleep score and body battery** each day, with a short plain-language suggestion.
- **Heart rate** all day (per-minute history plus live readings), resting heart rate and zones.
- **Sleep stages** (deep, light, REM, awake) and sleep regularity.
- **SpO2, stress, PAI and skin temperature.** Temperature is a reading every minute, synced every 5 minutes.
- **Trends**: two weeks of readiness, resting HR, HRV, sleep, steps, strain and active time against your 28-day
  baseline, plus **VO2max, fitness age** and acute:chronic training load.

**Heart checks from the raw pulse**
- Records the watch's raw PPG waveform, finds every beat and measures **HRV (RMSSD), rhythm regularity and
  breathing rate**. Rhythm screening is a hint, not a diagnosis.

**Workouts**
- Imports the watch's own workouts with per-second heart rate and GPS routes.
- **Gym workout capture**: streams raw accelerometer, gyroscope and pulse during a session and finds your
  **sets, reps, rest times, heart-rate recovery, zones, load and calories**, tuned on real logged gym sessions.

**Watch control, like Zepp**
- **Phone notifications** on your wrist (per-app icons), incoming calls, find phone, music control.
- **Watch settings**: all-day heart rate and stress monitoring, lift-to-wake (scheduled), do not disturb
  (scheduled), move reminders, high heart rate alerts, disconnect alert, language, wrist, step goal and more.
- **Watch app list**: choose and order the apps, and what goes in the More screen.
- **Alarms, calendar reminders, weather** (Open-Meteo, no API key), vibration counts, set clock, find watch.
- **Alexa button**: the watch's Alexa button works again. Replies cover time, heart rate, steps, timers, alarms and
  reminders that ring on the watch. Speech is recognised **offline with Vosk**, or with **Gemini 3.5 Transcribe Live**
  (free tier, paste your Google AI Studio key).
  Optionally, add a free **OpenRouter** key and an AI model answers anything and fixes misheard words.

**Your data stays yours**
- Everything is stored in a local database on the phone. Export it, back it up, or share it with other apps through
  **Health Connect** (heart rate, steps, sleep, SpO2, HRV, resting HR, skin temperature, workouts).
- **Sensors lab**: live raw pulse, motion, compass, GPS and microphone, saved to CSV.

## Download

Get the APK from the [latest release](https://github.com/AbhiramJayasankar/heartline-zepp-alternative/releases/latest)
and open it on your phone, or install it with `adb install -r heartline-*.apk`.

## Set up

1. **Get your watch's auth key.** The watch only talks to apps that know its 32-character key. Fetch it from your Zepp
   account with [huami-token](https://github.com/argrento/huami-token) (or Amazfit Watchface Maker).
   Keep it private.
2. **Never unpair the watch in Zepp.** Unpairing changes the key. Instead, force-stop Zepp (or uninstall it):
   the watch accepts one connection at a time.
3. Open Heartline, grant Bluetooth and notification access, and enter the watch's Bluetooth address and key.
   The key is stored encrypted with the Android keystore and never leaves the phone.
4. Under **Watch → Keep running**, allow background use so syncing continues with the screen off.

## Requirements

| | |
|---|---|
| Watch | Amazfit GTR 2e (tested on firmware V1.7.3.1). GTR 2 / GTS 2 family: untested |
| Phone | Android 12 or newer (arm64). Tested on a Galaxy S24 Ultra, Android 17 |
| Build | JDK 17+, Android SDK platform 37. The Vosk speech model (40 MB) is downloaded at build time |

## Build from source

```bash
git clone https://github.com/AbhiramJayasankar/heartline-zepp-alternative.git
cd heartline-zepp-alternative/android
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew test                 # unit tests, including real recorded sessions
```

## How it works

Heartline speaks the watch's Bluetooth LE protocol directly: the Huami authentication handshake, the activity and
health history fetches (without the acknowledgement that would delete data from the watch), the 2021 chunked
protocol used by the Alexa button, and the raw sensor stream. The protocol notes are in
[`android/docs/`](android/docs) and the analysis methods in
[`android/core/analytics/`](android/core/analytics) and [`server/analytics/METHODS.md`](server/analytics/METHODS.md).

| Folder | What it is |
|---|---|
| `android/app` | The Heartline Android app (Kotlin, Jetpack Compose) |
| `android/core/protocol` | Watch protocol: commands, history parsing, settings, Alexa, event log |
| `android/core/ble` | Bluetooth LE connection, sync and raw streaming |
| `android/core/analytics` | Readiness, sleep, HRV, rhythm, workout capture (sets and reps) |
| `android/core/data` | Local SQLite storage, export, encrypted key store |
| `server/`, `web/` | A Python (FastAPI + bleak) desktop dashboard that talks to the watch from a PC |
| `research/` | The first protocol exploration scripts (bleak, Windows) |

## Good to know

- **Some data is handed over once.** After Heartline syncs skin temperature, stress, SpO2 and the event log, the watch
  moves past those records, so Zepp won't get them. Activity, heart rate, PAI and workouts stay on the watch.
- **Not supported:** watch faces, firmware updates and world clocks. Vibration *patterns* don't change on the
  GTR 2e; the count does.
- **Automatic workout capture** can start up to about 30 minutes late, because the watch reports workout starts
  in batches. For a whole gym session, start the capture in the app.
- Heart rhythm screening and all health scores are estimates for personal use, not medical advice.

## FAQ

**Can I use an Amazfit watch without the Zepp app?**
Yes. Heartline replaces Zepp for the Amazfit GTR 2e: syncing, settings, notifications and the Alexa button all
work without a Zepp account or cloud connection once you have the watch's auth key.

**How is this different from Gadgetbridge?**
[Gadgetbridge](https://gadgetbridge.org) supports hundreds of watches and was a key reference for the protocol.
Heartline does one watch in depth: it decodes the watch's skin temperature, event log and raw sensor streams, adds
health scores, heart checks from the raw pulse, gym set and rep detection, and a working Alexa button.

**Does it need the internet?**
No. Only optional features go online: weather (Open-Meteo), and for the Alexa button Gemini speech recognition and AI answers (OpenRouter).

**Will it work on my GTR 2, GTS 2 or GTS 2e?**
Probably in large part, since they share the protocol, but only the GTR 2e has been tested. Reports are welcome
in the [issues](https://github.com/AbhiramJayasankar/heartline-zepp-alternative/issues).

## Credits

Protocol knowledge from [Gadgetbridge](https://codeberg.org/Freeyourgadget/Gadgetbridge); auth keys via
[huami-token](https://github.com/argrento/huami-token); offline speech recognition by
[Vosk](https://alphacephei.com/vosk/) (Apache 2.0); weather from [Open-Meteo](https://open-meteo.com).
Not affiliated with Zepp Health or Amazfit.

## License

[MIT](LICENSE)
