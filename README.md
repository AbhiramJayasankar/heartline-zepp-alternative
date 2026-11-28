<p align="center">
  <img src="docs/images/screens.png" alt="Heartline app screens: a gym session's heart-rate chart with exercise lanes, the AI coach's note, calories by exercise, the sleep page and trends" width="100%">
</p>

<h1 align="center">Heartline: an open-source Zepp app alternative for the Amazfit GTR 2e</h1>

<p align="center">
  <b>Your watch, minus the Zepp app, plus a gym coach and an AI assistant.</b><br>
  Heartline talks to the <b>Amazfit GTR 2e</b> directly over Bluetooth LE and keeps every reading on your phone:
  heart rate, sleep stages and naps, SpO2, stress, HRV, skin temperature, notifications, alarms and weather. It
  records your gym sessions from the watch's raw sensors, merges them with your workout log and analyses every set,
  rest and calorie. Press the watch's Alexa button and ask it anything. No account, no cloud.
</p>

<p align="center">
  <a href="https://github.com/AbhiramJayasankar/heartline-zepp-alternative/releases/latest"><img alt="Download APK" src="https://img.shields.io/github/v/release/AbhiramJayasankar/heartline-zepp-alternative?include_prereleases&label=download%20APK&color=e5484d"></a>
  <img alt="Android 12+" src="https://img.shields.io/badge/Android-12%2B-3ddc84?logo=android&logoColor=white">
  <img alt="Kotlin and Jetpack Compose" src="https://img.shields.io/badge/Kotlin-Jetpack%20Compose-7f52ff?logo=kotlin&logoColor=white">
  <a href="LICENSE"><img alt="MIT license" src="https://img.shields.io/badge/license-MIT-blue"></a>
</p>

> **Status: 0.12.3.** Built and used daily on one Amazfit GTR 2e (firmware V1.7.3.1) with a Samsung Galaxy S24 Ultra.
> Other Huami/Zepp watches of the same generation (GTR 2, GTS 2, GTS 2e) speak a similar protocol but are untested.

## Highlights

| | |
|---|---|
| 🏋️ **A gym session analysed like a coach would** | Your log's sets and loads plus the watch's raw sensors: new bests, volume against last time, what each exercise cost your heart, recovery between sets, calories per exercise, a treadmill comparison with your last walk, and an AI coach's note with the numbers to aim for next time. |
| 🎙️ **An Alexa button that knows you** | Ask about your sleep, stress or readiness and get an analysis of *your* data. Ask for the news and it searches the web. Set timers, alarms and reminders by voice. Free AI, your own keys. |
| 📊 **Health scores Zepp never showed** | Readiness, strain, sleep score and body battery every day, trends against your own 28-day baseline, VO2max and fitness age. |
| 🌙 **A page for every part of you** | Sleep with a stage-by-stage hypnogram and overnight SpO2, heart rate with resting and HRV against your usual, activity against your goal, and a Today overview that ties them together, each tile with a small graph of its day. |
| ✨ **AI insights** | The model you choose reads two weeks of your data and writes what stands out, with the numbers and one thing to try. |
| 🧠 **Your AI, your models** | Gemini, OpenRouter or any OpenAI-compatible API, as many keys as you like. Every AI feature gets a main model and two fallbacks, and an out-of-quota key hands over to the next. |
| 🎨 **Seven themes** | Paper, Night, Sage, Dusk, Lilac, Forest and a true-black OLED theme, or follow the phone. |
| 🫀 **Heart checks from the raw pulse** | Records the optical pulse waveform and measures HRV, rhythm regularity and breathing rate beat by beat. |
| 🔬 **Every sensor, raw** | Live pulse, accelerometer, gyroscope, compass, GPS and microphone streams, saved to CSV. |
| ⌚ **Everything Zepp did** | Notifications with app icons, calls, music, find phone, alarms, calendar reminders, weather, watch settings and the app list. |
| 🔒 **No account, no cloud** | One local database on the phone. Export it, back it up, or share it through Health Connect. |

## Gym sessions, analysed like a coach would

<p align="center">
  <img src="docs/images/workouts.png" alt="A gym session in Heartline: summary, exercise cards with estimated one-rep max and rep strips, heart cost per exercise, heart rate between sets against the last session, and the treadmill comparison" width="100%">
</p>

Start a capture on the phone (or ask the watch's Alexa button), train, and import your workout log afterwards.
Heartline lines the log up with what the watch's accelerometer, gyroscope and heart-rate sensor recorded:

- **One chart for the whole session**: heart rate with every set shaded by exercise, your rising heart-rate floor,
  zones, and lanes underneath for each exercise, wrist motion, what you were doing and the watch's own workouts, all
  on one time axis. Touch any moment for the set, the heart rate and the **calories so far**.
- **What stood out**: new bests by estimated one-rep max, volume against the last session with the same exercises,
  the exercise that cost your heart most, recovery against last time, sets taken to failure, skipped sets.
- **Every exercise and set**: rest before, reps in reserve, failure and difficulty from your log; reps counted by the
  watch, each rep's duration and wrist arc; a link to the same exercise last session.
- **Calories every 5 seconds**: heart-rate reserve converted to oxygen use with your VO2max and weight, the ACSM
  walking equation for a logged treadmill stretch, and the watch's own estimate met halfway. Per exercise, per part
  of the session, and a running total.
- **The treadmill against your last walk**: speed, incline, time, steps, distance, calories, steady heart rate,
  rise time and recovery side by side, with a verdict ("same settings, your heart settled 5 bpm lower") and a link.
- **An AI coach's note** (the model you choose, your own key) that reads the page's numbers and says what to aim for next
  session, saved with the session.
- **Around the session**: readiness and sleep going in, the day's load, the heart check after, and the watch's
  workout beside what the sensors measured.

<p align="center">
  <img src="docs/images/around.png" alt="Workout captures list, the gym log, a treadmill block with steps and calories, the activity page and the 24-hour heart rate trace" width="100%">
</p>

## The Alexa button, rebuilt

On the GTR 2e, Alexa only works through the Zepp app and an Amazon account. Heartline catches the button press instead, listens through the watch's
microphone, and answers on the watch's own Alexa screens: text, cards, tables, alarms and timers.

| Say | What happens on your wrist |
|---|---|
| "How did I sleep last night?" | It reads your sleep stages and gives a short verdict on how you slept |
| "Analyse my heart rate today" | It pulls the day's heart rate, resting rate and zones and tells you what stands out |
| "What's the weather like?" | The watch's weather card for where your phone is: now, today and three days |
| "Who won the last F1 race?" | It searches the web (Tavily or Exa) and answers from what it finds |
| "List the ten tallest mountains" | A scrolling two-column table on the watch |
| "Remind me to drink water in 20 minutes" | The reminder rings on the watch with its title; "cancel my water reminder" cancels it |
| "Wake me at 6:30" / "Timer for 5 minutes" | A real watch alarm or timer, set on the watch itself |
| "Start a gym workout" | Heartline's workout capture starts recording sets and reps |
| "Tell me a 5,000-word story" | The reply screen shows the start; the whole story arrives as watch notifications |
| "Do deep research on this week's AI news" | It searches as long as it needs; if the watch can't wait, it says so and the report arrives as notifications |
| "Tell me a knock-knock joke" | It plays along: the watch listens again after each line |

- **Listens until you stop talking**, up to 29 seconds for a detailed question, and ignores the room's background
  sounds when deciding you've finished.
- **Hears you with Gemini 3.5 Transcribe Live** by default (free tier), or any model that takes audio, tuned for
  Indian and US English.
- **Thinks with the model you choose** (free OpenRouter models, Gemini, or any OpenAI-compatible API) and 20 tools:
  eight health-data lookups, web search, and actions for timers, alarms, reminders, workouts, the weather card,
  tables and syncing. It can take up to 25 requests and 15 minutes for a thorough answer.
- **Follow-ups when they're needed**: the model ends a reply with a tag when it needs your answer, and the watch
  reopens its mic with the question on screen.
- **Built around the watch's real limits**, measured on the device: 2,000-byte replies, 1,024-byte notifications, a
  10-character left column in tables, and a map of the emoji its font actually draws, so you never see a box.
- **Nothing hidden.** The app's Alexa page logs every question: what was heard, each model request with its
  timing and tokens, every tool call with its arguments and result, the reply, and the recording to play back. You
  can edit the instructions, switch tools on and off, rewrite their descriptions, and pick the models, reasoning
  and limits. "Try a question" runs the whole pipeline without touching the watch.

## Everything else

**Health and sleep**
- **Readiness, strain, sleep score and body battery** each day, with a short plain-language suggestion, and a
  Readiness page that shows exactly how the number was worked out.
- **Naps** are kept apart from nights, shown on the Sleep page and Today, and recharge body battery. The night is
  the sleep that falls in your usual sleeping hours (learnt from your own last weeks), so a long evening nap before a
  short, broken night is still a nap.
- **Automatic HRV**: samples overnight while you sleep and during still moments in the day. A check that is recording
  can be stopped from the banner at any time; one gives way to a workout capture, and daytime checks pause during a
  watch workout and for an hour after three that aren't clean.
- **Heart rate** all day (per-minute history plus live readings), sleeping and awake resting heart rate, and zones.
- **Sleep page**: the night's stages as rounded blocks on four rows (awake, REM, light, deep) with each one's total,
  touch for the stretch, heart rate and SpO2 at that minute; each stage against its typical range, heart rate and SpO2 while asleep,
  two weeks of nights, sleep score and how regular your timing is.
- **SpO2, stress, PAI and skin temperature.** Temperature is a reading every minute, synced every 5 minutes.
- **Trends**: two weeks of readiness, resting HR, HRV, sleep, steps, strain and active time against your 28-day
  baseline, plus **VO2max, fitness age** and acute:chronic training load. VO2max from a run-walk costs its runs as
  running and its walks as walking.

**Heart checks from the raw pulse**
- Records the watch's raw PPG waveform, finds every beat and measures **HRV (RMSSD), rhythm regularity and
  breathing rate**. Beat-to-beat measures use only beats that are back to back, never a difference across a beat that
  was set aside. Rhythm screening is a hint, not a diagnosis.
- **A live chart while it records**: the pulse on ECG paper with every beat marked (used or set aside), moving
  stretches shaded and the wrist's movement underneath, with heart rate, beats used, clean time and HRV so far.
- **Movement is caught, turning included**: beats taken while the wrist moves more than 0.015 g are set aside, the
  level where, on 374 recorded checks, beats stopped behaving like still ones. Why moving beats can't be rescued with
  this sensor is in [research/heartcheck-motion.md](research/heartcheck-motion.md).

**Workouts**
- Imports the watch's own workouts with per-second heart rate, pace, cadence, stride, kilometre splits and GPS routes,
  drawn on an **OpenStreetMap map** (or on plain paper, if you'd rather no map tiles were fetched).
- **Runs and walks read as runs and walks**: every stretch of running and walking is found from your cadence, with
  each interval's time, pace, cadence and heart rate, how far heart rate fell in the first minute of each walk, and
  cardiac drift across the run. The route is coloured by running and walking, on the watch's workout and on a
  capture labelled Run.
- **Heart rate checked against the raw pulse while you run**: wrist sensors can lock onto your step rhythm and report
  it as heart rate. In a capture, Heartline takes the movement out of the raw optical pulse and, where the watch sat
  on your step rate and the pulse clearly says otherwise, uses the pulse instead (and says so).
- **A wandering GPS track is caught**: when the watch's distance is 20% more than your steps can cover, at your step
  lengths learnt from workouts with clean GPS, the distance, pace and each run and walk come from your steps instead,
  and the workout says why. Checked against laps of a 745 m lake loop: within 1 to 4%, where the watch was 30 to 46% long.
- **One Run / walk switch**: turn it on when you start a capture and the whole session is read on foot: the walk
  there, runs and walks, laps of a loop and the walk back, however often you change the workout on the watch. Laps
  are only looked for in a Run / walk session.
- **Rides inside a session**: cycle part of a Run / walk session and the ride is found by itself, from a Cycling
  workout on the watch or a minute or more at bike speed on the phone's GPS. It gets its own section: distance, moving
  time, average speed, best 5 minutes and 30 seconds, stops, climbing, heart rate while riding, how far each heartbeat
  carried you, speed through the ride with the stops shaded, and each kilometre's speed and heart rate. The route
  colours it blue, its calories are counted apart, and it's left out of the runs, walks and laps. A Cycling workout on
  the watch gets the same ride section, and its distance is never "corrected" from steps.
- **Runs and walks with the phone's GPS**: under Run / walk, turn on the phone's GPS, and the
  phone records the route once a second alongside the watch. It keeps to the path where a watch on the wrist can wander,
  so the capture, and the watch's own workout over the same time, take their route, distance and pace from it. While you
  go, the capture shows the distance, the pace over the last 30 seconds and the laps so far.
- **Laps of a loop**: Heartline finds the point on your route you pass most often and splits the session into laps:
  each lap's time, distance, pace, fastest 30 seconds, running and walking with the pace of each, cadence, step length
  and heart rate, and a tap opens its runs and walks one by one. Above them: the fastest and slowest lap, how even they
  were, whether you slowed, and how far each heartbeat carried you. On watch workouts without the phone, laps come from
  the watch's track; where it wandered, their distances come from your steps (on a 745 m lake loop, 677 to 791 m).
  A run capture without the phone's GPS takes its route, distance and laps from the watch workouts inside it, and
  links to each; each of those links back to the capture.
- **A full-screen map**: tap Full screen (or double-tap the map) for a map you can pinch, pan and zoom, with a chip per
  lap that fades the rest of the route and fits the view to that lap, with its figures underneath.
- **Scrub through the route**: drag through the session's time, or press play, and a marker follows the route with the
  time, distance, speed, heart rate and altitude at that moment. Colour the route by activity, heart rate, speed or
  altitude; kilometre marks are numbered along it. When the phone recorded alongside the watch, switch between the
  phone's and the watch's GPS track, or overlay them and see how far apart they were.
- **GPS assist (A-GPS)**: sends the week's satellite almanacs and orbit predictions to the watch, so its GPS gets a
  fix in seconds. Needs a Zepp sign-in (the data is only served to Zepp accounts), done in your phone's browser, so Google
  and Apple accounts work in a tap; updates every 3 days.
- **Gym workout capture**: streams raw accelerometer, gyroscope and pulse during a session and finds your
  **sets, reps, rest times, heart-rate recovery, zones, load and calories**, tuned on real logged gym sessions.
- **Gym log import** (CSV or JSON, [format](docs/GYM_LOG_FORMAT.md)): sets, loads, reps in reserve and treadmill
  settings merge into the capture; treadmill walks give a VO2max estimate.

**Watch control, like Zepp**
- **Phone notifications** on your wrist (per-app icons), find phone, music control.
- **Incoming calls** with the caller's name, ringing on the wrist until you answer or the call ends; reject or
  silence them from the watch.
- **Watch settings**: all-day heart rate and stress monitoring, lift-to-wake (scheduled), do not disturb
  (scheduled), move reminders, high heart rate alerts, disconnect alert, language, wrist, step goal and more.
- **Let Zepp connect for a while**: on the Watch page, Heartline steps aside for 15 minutes so the Zepp app can sync
  or change a setting, then takes the watch back by itself (or tap Reconnect now).
- **Watch app list**: choose and order the apps, and what goes in the More screen.
- **Alarms, calendar reminders, weather** for where your phone is (Open-Meteo, no API key), vibration counts, set
  clock, find watch.

**Lent the watch to a friend?**
- Start a heart check or a gym capture **for a friend** (in the app, or by asking the watch's assistant), or mark one
  afterwards, along with any watch workout. It stays in Heartline with a Friend label, and its time on the wrist is
  left out of your days, trends, readiness, Health Connect and the assistant's answers.
- **Delete** heart checks, captures, sensor recordings and watch workouts you don't want.

**AI insights**
- The model you choose under AI models. Two weeks of readiness and trends, the last two nights' sleep, HRV (overnight,
  daytime and heart checks), heart rate, activity, stress, SpO2, temperature and workout captures go in; ranked
  findings with the numbers and one thing to try come out. Nothing is sent until you ask: daily insights are off unless
  you switch them on in the page's settings.
- **When you tap for insights**, one request goes to the main model, with no retries and no fallbacks, so it answers
  or fails quickly; if it fails, pick another model right there. The daily run keeps the whole chain and its retries.
- **See exactly what's sent**: "What's sent to the AI" shows the instructions, the message and every section of your
  data as the model will get them, gathered fresh, with a button to copy it all.

**AI models**
- **Providers and keys**: OpenRouter, Gemini and any OpenAI-compatible endpoint (Groq, Together, a local server),
  each with as many keys as you like. A key that runs out of quota or is refused rests for 30 minutes and the next
  one takes over.
- **Models to choose from**: fetch every provider's model list and tick the few you want offered.
- **Every AI feature** (watch assistant, voice to text, insights, coach's note, workout names) gets a main model and
  two fallbacks. Out of quota means the next key, then the next model; busy or timed out means the same model again.
  Retries, waits and time limits are yours to set per feature.
- **Every request is logged** with its model, key, time and any error.

**Your data stays yours**
- Everything is stored in a local database on the phone. Export it (your API keys stay out of the export), back it
  up, or share it with other apps through **Health Connect** (heart rate, steps, sleep, SpO2, HRV, resting HR, skin
  temperature, workouts).
- A workout's map loads OpenStreetMap tiles, which tells the tile server the area being looked at (never the track
  itself). Switch the map to Paper and no tiles are fetched.
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
5. **For the AI features**, open **Watch → AI models**, add a [Gemini](https://aistudio.google.com) key and an
   [OpenRouter](https://openrouter.ai) key (both have free tiers), fetch their model lists and pick the models each
   feature uses. Under **Watch → Alexa**, a [Tavily](https://tavily.com) or [Exa](https://exa.ai) key adds web search
   (1,000 free searches a month on Tavily).

## Requirements

| | |
|---|---|
| Watch | Amazfit GTR 2e (tested on firmware V1.7.3.1). GTR 2 / GTS 2 family: untested |
| Phone | Android 12 or newer (arm64). Tested on a Galaxy S24 Ultra, Android 17 |
| Build | JDK 17+, Android SDK platform 37 |

## Build from source

```bash
git clone https://github.com/AbhiramJayasankar/heartline-zepp-alternative.git
cd heartline-zepp-alternative/android
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew verify               # every module's unit tests (including real recorded sessions) and lint
```

`./gradlew assembleRelease` signs with your own key when `android/keystore.properties` names one (`storeFile`,
`storePassword`, `keyAlias`, `keyPassword`; keep it out of git), and with the debug key otherwise.

## How it works

Heartline speaks the watch's Bluetooth LE protocol directly: the Huami authentication handshake, the activity and
health history fetches (without the acknowledgement that would delete data from the watch), the 2021 chunked
protocol used by the Alexa button, and the raw sensor stream. The protocol notes are in
[`android/docs/`](android/docs) (the Alexa bridge in [`alexa-bridge.md`](android/docs/alexa-bridge.md)) and the
analysis methods in [`android/core/analytics/`](android/core/analytics) and
[`server/analytics/METHODS.md`](server/analytics/METHODS.md).

| Folder | What it is |
|---|---|
| `android/app` | The Heartline Android app (Kotlin, Jetpack Compose), including the Alexa assistant |
| `android/core/protocol` | Watch protocol: commands, history parsing, settings, Alexa, event log |
| `android/core/ble` | Bluetooth LE connection, sync and raw streaming |
| `android/core/analytics` | Readiness, sleep, HRV, rhythm, workout capture (sets and reps) |
| `android/core/data` | Local SQLite storage, export, encrypted key store |
| `server/`, `web/` | A Python (FastAPI + bleak) desktop dashboard that talks to the watch from a PC |
| `research/` | The first protocol exploration scripts (bleak, Windows) and research write-ups |
| `tools/android/` | Helpers for testing on a phone over adb |

Changing the code? [`AGENTS.md`](AGENTS.md) is the guide to the codebase, and [`CHANGELOG.md`](CHANGELOG.md) lists every release.

## Good to know

- **Some data is handed over once.** After Heartline syncs skin temperature, stress, SpO2 and the event log, the watch
  moves past those records, so Zepp won't get them. Activity, heart rate, PAI and workouts stay on the watch.
- **Not supported:** watch faces, firmware updates and world clocks. Vibration *patterns* don't change on the
  GTR 2e; the count does.
- **Automatic workout capture** can start up to about 30 minutes late, because the watch reports workout starts
  in batches. For a whole gym session, start the capture in the app.
- **The Alexa button needs the internet** and your own keys; free models are sometimes slow or rate-limited. The
  watch waits about 30 s after you stop talking, so after 25 s it's told the answer is coming, and the answer
  arrives as notifications.
- **Alexa alarms and reminders** made by voice can't be deleted from the phone (the watch ignores it); long-press
  them in the watch's Alarm app.
- **Deleting a watch workout** removes it from Heartline only; the watch keeps its own copy in its workout history.
- **GPS assist needs a Zepp account.** You sign in on Zepp's page in your browser (Google, Apple, Xiaomi or email),
  which hands the sign-in back to Heartline; only that token is kept, encrypted. Email and password also work in the app.
- **Housekeeping:** raw sync data is kept for 14 days (workouts and resting heart rate for good), the Bluetooth log
  for 7 days and the event log for 60. Your daily figures, checks and captures are kept.
- Heart rhythm screening and all health scores are estimates for personal use, not medical advice.

## FAQ

**Can I use an Amazfit watch without the Zepp app?**
Yes. Heartline replaces Zepp for the Amazfit GTR 2e: syncing, settings, notifications and the Alexa button all
work without a Zepp account or cloud connection once you have the watch's auth key. The one exception is the
optional GPS assist, whose satellite data Zepp only serves to signed-in accounts.

**Does the Alexa button work without Amazon?**
Yes. Heartline answers it instead of Alexa, with the speech and answer models you choose (Gemini, OpenRouter or any
OpenAI-compatible API). No Amazon account is involved.

**How is this different from Gadgetbridge?**
[Gadgetbridge](https://gadgetbridge.org) supports hundreds of watches and was a key reference for the protocol.
Heartline does one watch in depth: it decodes the watch's skin temperature, event log and raw sensor streams, adds
health scores, heart checks from the raw pulse, gym set and rep detection, and an AI assistant on the Alexa button.

**Does it need the internet?**
No. Only optional features go online: weather (Open-Meteo), GPS assist (Zepp) and the AI features (the models you choose, through your
own keys, and Tavily or Exa to search).

**Will it work on my GTR 2, GTS 2 or GTS 2e?**
Probably in large part, since they share the protocol, but only the GTR 2e has been tested. Reports are welcome
in the [issues](https://github.com/AbhiramJayasankar/heartline-zepp-alternative/issues).

## Credits

Protocol knowledge from [Gadgetbridge](https://codeberg.org/Freeyourgadget/Gadgetbridge); auth keys and the A-GPS file format via
[huami-token](https://github.com/argrento/huami-token); weather from [Open-Meteo](https://open-meteo.com); speech
and answers from [Gemini](https://ai.google.dev), [OpenRouter](https://openrouter.ai) or any OpenAI-compatible API; search from
[Tavily](https://tavily.com) and [Exa](https://exa.ai). Not affiliated with Zepp Health, Amazfit or Amazon.

## License

[MIT](LICENSE)
