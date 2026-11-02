Task A implements weather, find phone and music controls for the native GTR 2e (Huami 2020) app. No commit, install, phone access or watch access was performed. No UI, controller contract, DemoController, AppSettings, database schema or dependency changes were made.

Files created:

| Area | Files (relative to `android/`) |
| --- | --- |
| Protocol | `core/protocol/src/main/kotlin/dev/heartline/protocol/Weather.kt`, `Music.kt`, `PhoneEvents.kt` |
| App integration and pure policies | `app/src/main/java/dev/heartline/app/TaskAController.kt`, `OpenMeteo.kt`, `WeatherPolicy.kt`, `MusicPolicy.kt`, `FindPhoneTimer.kt` |
| Service features | `app/src/main/java/dev/heartline/service/PhoneFeatures.kt`, `WatchWeatherSync.kt`, `MusicControls.kt`, `FindPhone.kt` |
| JVM tests | `core/protocol/src/test/kotlin/dev/heartline/protocol/WeatherTest.kt`, `MusicPhoneTest.kt`; `app/src/test/java/dev/heartline/app/PhoneFeaturesPolicyTest.kt` |

Files changed: only the Task A block in `RealController.kt`; localized additions in `WatchService.kt`, `WatchConnection.kt` and `AndroidManifest.xml` (INTERNET and VIBRATE). This report is also new. The ignored `android/local.properties` SDK drive colon was escaped to satisfy lint. Build-only signing files are ignored under `android/build/`.

Weather settings are one JSON value in `db.meta["weather_settings"]` (enabled and chosen place); music uses `db.meta["music_enabled"]`. The foreground service owns feature lifetimes and watches these settings. Weather sends when `watch.ready` becomes true, after startup reads, and every 60 minutes while ready. Reconnects resend the cached snapshot, fetching again once its age exceeds 30 minutes. Explicit refresh forces a fetch and sends if ready; offline refresh updates the UI and the next connection sends it. Place changes invalidate the cache. Fetch time and successful send time are separate UI fields. Disabled weather performs no network requests. HTTPS uses HttpURLConnection on IO with 15-second connect/20-second read timeouts, bounded responses and no redirects. Geocoding sends only typed search text; weather/AQ requests send only the selected coordinates. No device location is read.

The forecast request follows the brief. Gadgetbridge sends AQI for GTR 2e, so the additional Open-Meteo air-quality request asks for current PM2.5, PM10, NO2 and ozone concentrations. Gadgetbridge's WeatherSpec/WeatherMapper expect the Plume index: `plumeAqi` interpolates the 2023 specification's table 3 and takes the maximum pollutant index. Missing AQ data sends -1/Unknown and leaves the forecast usable, with a UI error explaining that AQ is unavailable. WMO conditions map to equivalent HuamiWeatherConditions icons and plain English text. `is_day`, wind direction and UV have no fields in this device's `onSendWeather` format. No Zepp OS weather protocol is used.

Find phone starts the default alarm sound with USAGE_ALARM, looping vibration and a high-importance notification with a Stop service action. Watch stop, app stop, service destruction and a monotonic 60-second deadline release the player/vibrator/notification. Repeated start events do not extend the deadline. Notification denial does not prevent the alarm/vibration. Alarm volume and Android DND policy remain under the owner's control.

Music uses MediaSessionManager with NotificationForwarder's ComponentName and notification-listener access, independently of notification-forwarding enablement. It prefers a playing session, otherwise Android's priority order; observes all active sessions so a different app starting playback can become selected; and updates nowPlaying. Metadata/playback changes debounce for 250 ms; connect pushes a snapshot; music-open waits 100 ms as Gadgetbridge requires; music-close cancels pending debounce. A conflated sender finishes any current GATT sequence before sending the next snapshot. Access and phone volume changes are rechecked every two seconds. Media transport controls implement play/pause/next/previous; AudioManager adjusts music-stream volume. The current playback position is extrapolated from elapsedRealtime and playback speed. Service cleanup unregisters media callbacks/listeners.

Wire formats below use little endian integers, signed temperature bytes, and UTF-8 strings terminated with `00`. The UI/cache stays Celsius; watch temperatures convert to Fahrenheit with an integer cast when the existing app units preference is imperial, matching `HuamiSupport.onSendWeather`. Changing units triggers a resend. `T` is a four-byte epoch-seconds timestamp; `Z` is `(phone offset seconds / 3600) * 4` as a signed byte, preserving Gadgetbridge's truncation of fractional-hour zones. Solar times from Open-Meteo are converted into the phone time zone, matching Gadgetbridge's solar-day calculation context.

| Payload / route | Exact layout | Gadgetbridge source |
| --- | --- | --- |
| Weather current, chunked type 1 | `02 T Z condition temperature conditionText\0` | `HuamiSupport.onSendWeather` |
| AQI, chunked type 1 | `04 T Z int16(aqi) levelText\0` | `HuamiSupport.onSendWeather`; `WeatherMapper.getAqiLevelString` |
| Forecast, chunked type 1 | `01 T Z count`, then for each day `condition condition high low text\0`; at most today + six days; today's condition is the current condition | `HuamiSupport.onSendWeather` |
| Location, chunked type 1 | `08 placeName\0` | `HuamiSupport.onSendWeather` |
| Wind/humidity, chunked type 1 | `40 T Z beaufortText\0 humidityPercentText\0` | `HuamiSupport.onSendWeather`, `windSpeedString`; `WeatherSpec.toBeaufort` |
| Solar, chunked type 1 | `10 T Z sunriseHour sunriseMinute sunsetHour sunsetMinute`; omitted if solar times are unavailable | `HuamiSupport.onSendWeather`; `AmazfitGTR2Support.supportsSunriseSunsetWindHumidity`, inherited by `AmazfitGTR2eSupport` |
| Music, chunked type 3 | Flags (`01` state, `02` artist, `04` album, `08` track, `10` duration), then `playing(0/1) 00 uint16(positionSeconds)`, nonempty artist/album/track strings, nonzero `uint16(durationSeconds)` | `HuamiSupport.encodeMusicState(..., false)` and `sendMusicStateToDevice`; `HuamiService.MUSIC_FLAG_*` |
| Volume, chunked type 3 | `40 roundedVolumePercent` | `HuamiSupport.onSetPhoneVolume` |
| Find acknowledgement, CONFIG | `06 14 00 00` | `HuamiSupport.acknowledgeFindPhone`; `AmazfitBipService.COMMAND_ACK_FIND_PHONE_IN_PROGRESS` |
| Incoming events, DEVICE_EVENT | `08` start find; `0f` stop find; `fe command`, where `00/01/03/04/05/06/e0/e1` = play/pause/next/previous/volume-up/volume-down/open/close | `HuamiSupport.handleDeviceEvent` |

Every old chunk is `00 (flags | type) counter data`, with 17 data bytes at MTU 23. Flags are `00` first nonfinal, `40` intermediate, `80` last, `c0` single. Weather/music have no notification extra header. `AmazfitGTSSupport.notificationHasExtraHeader` applies to the existing notification payloads; their four zero bytes and type-0 write behavior are unchanged. All writes still go through GattClient and the existing action mutex. Incoming events retain existing logging before publication to feature handlers. No activity fetch acknowledgement was added.

Validation uses the required protocol/BLE/app unit-test, assembleDebug and lintDebug tasks. Lint reports 0 errors, 6 existing warnings and 1 existing hint. The restricted environment needs GRADLE_USER_HOME set to the shared cache, an in-worktree Android/user home, in-process Kotlin, and an ignored init script pointing debug signing to a local copy of the existing debug key. No project signing configuration was changed. Reproduction from `android/`:

```bash
export JAVA_HOME='/c/Program Files/Android/Android Studio/jbr'
export GRADLE_USER_HOME='/c/Users/abhir/.gradle'
export ANDROID_USER_HOME="$PWD/build/android-home"
./gradlew --no-daemon \
  -Duser.home=C:/Users/abhir/Desktop/projects/hl-a/android/build/user-home \
  -Pkotlin.compiler.execution.strategy=in-process -I build/task-a-signing.init.gradle \
  :core:protocol:test :core:ble:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

JVM counts: protocol 104 (22 new), BLE 23, app 27 (11 new): 154 total, no failures/errors/skips. New tests derive literal packet bytes from the named Gadgetbridge methods and cover all WMO groups, negative temperatures, Fahrenheit conversion, forecast limits, UTF-8, fractional-hour timezone behavior, type-1/type-3 chunk headers, music fields/flags, all incoming phone/music commands, cache age/clock rollback, AQ interpolation/missing values, place validation, timezone conversion, session preference, volume, position and duplicate find-start timeout behavior. Android media/audio/notification execution and live network response parsing are not instrumented on a device.

Owner checks on firmware V1.7.3.1:

1. Choose a place, enable weather, connect and confirm current temperature/text, today's high/low, seven days, location, wind level, humidity, AQI and solar times in the weather app/widgets. Repeat with a second place and a Unicode place name; verify UI fetch/send timestamps. Toggle metric/imperial and confirm watch temperatures change while UI tempC values stay Celsius. Disconnect/reconnect within 30 minutes (cached send), then after 30 minutes (fresh fetch); leave connected for an hourly update. Test offline refresh and recovery.
2. Check displayed weather age and solar times in Asia/Kolkata. The reference deliberately truncates the header's +05:30 offset to +05:00; confirm whether this firmware displays any 30-minute shift. Also check a place in a different timezone. Polar locations omit sunrise/sunset; confirm the firmware's display rather than assuming it clears old solar values.
3. Start find-phone with the UI closed and phone in silent mode. Confirm alarm-stream audibility at the owner's alarm volume and DND settings, vibration, high-importance notification, and each stop path (watch, app, notification and 60 seconds). Repeat start events must not prolong the alarm. Test while an activity/health sync is running.
4. Grant notification-listener access, enable music, and test playing and paused sessions, missing metadata, long/Unicode tracks, switching between two players, all six controls, external volume buttons and repeated music-screen open/close. Confirm metadata appears after opening, progress/duration and volume are correct, and no reconnect is caused by rapid track changes. Revoke/regrant listener access and verify musicAccess and recovery within two seconds. An empty session sends paused state without metadata fields, as Gadgetbridge does; confirm whether this firmware retains old track text.
5. Verify existing notifications/call alerts and an activity/health sync alongside weather/music, including startup/reconnect and disabled-feature behavior. No real-device compatibility claims are made before these owner checks.

Reference docs: [Open-Meteo forecast](https://open-meteo.com/en/docs), [air quality](https://open-meteo.com/en/docs/air-quality-api), [Android MediaSessionManager](https://developer.android.com/reference/android/media/session/MediaSessionManager), and [Plume 2023 specification](https://plumelabs.files.wordpress.com/2023/06/plume_aqi_2023.pdf). Gadgetbridge methods above were read from the supplied read-only sparse checkout; it was not modified.
