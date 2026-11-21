# Can Zepp 6.3.1 start a workout on the GTR 2e from the phone?

Researched 2026-11-21 (IST) by decompiling Zepp 6.3.1. Nothing was sent to the watch or the phone.

## Verdict

**No.** Zepp 6.3.1 cannot start the GTR 2e's own workout from the phone, and it never sends any sport command to a GTR 2e when the phone starts a run.

- Zepp has no phone-to-watch command for any Huami 2019/2020 device that starts the watch's own workout (the one the watch records and later syncs). This matches Gadgetbridge.
- What Zepp does have is the **"sport secondary screen"** (运动副屏, `SportSupplementPanel`). In this mode the *phone* runs the workout with phone GPS and the band only *shows* it: `CONFIG`, `START`, `PAUSE`, `RESUME`, `STOP`, plus live `SHOW` data on characteristic `0x000f` (or the legacy chunked transfer on `0x0020`, type 6). The watch can press pause, resume or stop and the phone hears about it. The workout record belongs to the phone.
- Zepp turns this mode on only for devices whose config has `Feature.SPORT_SOURCE_S`. That means Mi Bands 3/4/5/6, Bip-family and Kongming-family devices, and similar. **GTR 2e (`MILI_HAMBURG` → `MILI_OSPREY`) does not have that feature**, so `DeviceCenter.s()` drops `sendSportConfig` and `sendSportCommand` before any write.
- The GTR 2e does have the needed characteristic (`0000000f-…` in FEE0, see `research/gatt_table.txt`). If Heartline sends the bytes itself, the firmware *might* take them. That is untested. Zepp never does it for this watch, so there is no evidence the firmware supports it.
- The watch→phone direction (watch opens a workout, phone sends GPS) does exist and is not gated by device. Zepp's flag for it, `DEVICE_SPORT_SOURCE_S` ("devices that need the app to send GPS points"), lists only GPS-less bands and the GTR 2e is not on that list. That probably explains why event `0x14` has never arrived from this watch: it has its own GPS.

## Source

| | |
|---|---|
| App | Zepp 6.3.1-play, `com.huami.watch.hmwatchmanager`, versionCode 100463, minAPI 21, arm64-v8a + armeabi-v7a, nodpi |
| File | `com.huami.watch.hmwatchmanager_6.3.1-play-100463_minAPI21(arm64-v8a,armeabi-v7a)(nodpi)_apkmirror.com.apk` (saved as `zepp-6.3.1-play.apk`) |
| Source page | https://www.apkmirror.com/apk/zepp-inc/amazfit-watch/amazfit-watch-6-3-1-play-release/zepp-formerly-amazfit-6-3-1-play-android-apk-download/ (APKMirror, uploaded 2021-10) |
| Size | 137,322,591 bytes (130.96 MB) |
| SHA-256 | `49cb2857d800804011d62b004aa5b971edf8e1d1cd4e3d1dcdc79b03856162a5` (same as the hash on the APKMirror page) |
| Downloaded | 2026-11-21 22:47 IST |
| Decompiler | jadx 1.5.6 (official GitHub release), `-r --show-bad-code`; 34,656 Java files, 281 classes failed to decompile (none of them used here) |

The APK was never installed or run. The decompiled tree, the APK and jadx were deleted afterwards. Paths below are relative to the jadx `sources/` root.

## 1. Phone → watch: the "sport secondary screen"

### Classes

- `com/xiaomi/hm/health/bt/profile/sport/SportCommand.java`: `CONFIG 1, START 2, PAUSE 3, RESUME 4, STOP 5, SHOW 6, NOTIFY 7, HEART 8`.
- `com/xiaomi/hm/health/bt/profile/sport/HMSportProfile.java`: the GATT side. `UUID_CHARACTERISTIC_SPORT = GattUtils.uuid128(15)` = `0000000f-0000-3512-2118-0009af100700` in service FEE0. Methods: `doSportConfig`, `doSportCommand`, `doSportCommandPiece`, `notify`, `isSupportPiece` (piece profile version ≥ 11).
- `com/xiaomi/hm/health/bt/profile/sport/HMSportController.java`: the state machine (`sendConfig`, `sendCommand`, `h()` = piece send, 5 s `HEART` timer while paused).
- `com/xiaomi/hm/health/bt/profile/sport/SportSupplementPanelApiFactory.java`: picks the transport. If FEE0/`0x000f` exists it uses `HMSportController`. Otherwise it uses the 2021-channel `SportSupplementPanelModule`, if the device supports module 25. **The GTR 2e has `0x000f`, so it would get `HMSportController`.**
- `com/xiaomi/hm/health/bt/profile/sport/SportConfig.java`, `SportKind.java`, `SportInfo.java`, `SportGpsPoint.java`, `SportTemperature.java`: payloads.
- `com/xiaomi/hm/health/bt/device/HMMiLiProDevice.java` (`sendSportConfig` / `sendSportCommand` / `sendSportCommandPiece`, around lines 3401–3433): pass-through to the panel API.
- `com/xiaomi/hm/health/ui/sportfitness/manager/SportDeviceManager.java`, `f(DeviceConnectionStatus, HMDeviceSource)` (around line 665): the only phone-started caller. It sends `new SportConfig(kind)` (source PHONE) and then `START` with `startTime = trackId`, `elapsedTime = tickerTime`, plus `PAUSE` if the phone run is already paused.
- **Gate:** `com/xiaomi/hm/health/devicelib/DeviceCenter.java`, `sendSportConfig` / `sendSportCommand`, which both need `s(this.d)`. `s()` is `o(src, "hasFeatureSport")`, a reflective call to `HMDeviceConfig.hasFeatureSport(src)` = `hasFeature(Feature.SPORT_SOURCE_S, src)` (`com/xiaomi/hm/health/device/HMDeviceConfig.java:373`).
- Device config: `com/xiaomi/hm/health/device/deviceconfig/configs/MILI_HAMBURG.java` (bt names `"hamburg"`, `"Amazfit GTR 2e"`) extends `MILI_OSPREY.java`, which extends `HMBaseDeviceConfig`. Neither sets `SPORT_SOURCE_S` or `DEVICE_SPORT_SOURCE_S`. `MILI_HAMBURG_W` only turns NFC, schedule, AFib and RR off.
- Devices that do have `SPORT_SOURCE_S`: ATHENS, BEATS(\_P/\_W), CHONGQING, CINCO(\_L), COMO(\_L/\_W/\_WL), CORSICA, FALCON\_L/\_WL, HAWK\_L/\_WL, KONGMING(\_L/\_PRO), MADRID(\_W), NESS(\_W), ONYX, OSAKA(\_W), PEYTO\_L/\_WL, RIO, TEMPO, TOKYO, TONLESAP\_OL, WUHAN, YORK.

The GTR 2e *is* offered as a linked device in Zepp's phone run, but only for live heart rate. `SportOpenDeviceUtil.isDeviceSupportRunningAnalysis` is true for every `HR_REALTIME_SOURCE_S` device. The panel START is the part that the gate blocks.

### Bytes (as Zepp would send them; little-endian throughout)

Transport A, used when the piece profile version is below 11: write-without-response to `0x000f`, then wait up to 5 s for a notification on `0x000f`.

| Step | Write | Expected reply |
|---|---|---|
| CONFIG (phone source only) | `01 01 KK`: cmd 1, version 1, `SportKind` | any notification (Zepp only checks it isn't null); normally `10 01 01` |
| START | `02` + SportInfo | `10 02 01` (byte 2 = 1 means success) |
| PAUSE | `03` + SportInfo | `10 03 01`; Zepp then sends `HEART` (`08 00 00 00 00`) every 5 s, with no reply expected |
| RESUME | `04` + SportInfo | `10 04 01` |
| STOP | `05 00 00 00 00` | `10 05 01`; Zepp then unregisters the notification |
| SHOW / NOTIFY / HEART | `06`/`07`/`08` + SportInfo | none (no-response write) |

`SportKind`: 0 none, 1 outdoor (run), 2 indoor (treadmill), 3 riding, 4 walk, 5 exercise, 6 pool swim, 7 open-water swim. Zepp maps phone sport type 1→1, 8→2, 9→3, 6→4.

Reply format (`HMNotifyResponse.parse`): `10 <cmd> <code>`, where code 1 = success, 2 = invalid state, 3 = unknown command, 4 = operation failed.

Watch→phone on `0x000f`: a 2-byte `11 <cmd>` means the user pressed something on the watch, e.g. `11 03` pause, `11 04` resume, `11 05` stop.

**SportInfo** (`SportInfo.getBytes`) = `u32 flags` followed by the fields that are present, in this order:

| flag | field | size |
|---|---|---|
| 0x00001 | gpsState (1 located, 2 locating, 3 no permission, 4 location off) | u8 |
| 0x00002 | startTime (Zepp passes the track id, i.e. Unix seconds) | u32 |
| 0x00004 | elapsedTime | u32 |
| 0x00008 | distance | u32 |
| 0x00010 | pace | u32 |
| 0x00020 | hr (255 = invalid) | u8 |
| 0x00040 | step | u32 |
| 0x00080 | speed | u32 |
| 0x00100 | cal | u32 |
| 0x00200 | stepFreq | u16 |
| 0x00400 | stepLen | u16 |
| 0x00800 | ascent | u32 |
| 0x01000 | descent | u32 |
| 0x02000 | maxSpeed | u32 |
| 0x04000 | rideTime | u32 |
| 0x08000 | climbDistance | u32 |
| 0x10000 | avePace | u32 |
| 0x20000 | aveSpeed | u32 |
| 0x40000 | gpsPoint (`SportGpsPoint.a()`: lon×3e6, lat×3e6, alt×100, speed×10, timestamp ms, course×10, barometer, …) | 27 |
| 0x80000 | temperature | var |

An empty SportInfo is `00 00 00 00`.

Example: Zepp's START for an outdoor run beginning at Unix time `T` (`tt` = T as u32 LE):

```
0x000f  <- 01 01 01                              (CONFIG, v1, outdoor)
0x000f  <- 02 06 00 00 00 tt tt tt tt 00 00 00 00   (START, flags = startTime|elapsedTime, elapsed 0)
```

Transport B, used when the piece version is 11 or higher (`HMSportController.c` takes this path first): the same payload (`02 06 00 00 00 …`) goes out through `HMPieceProfile.patchWrite(payload, 6)` on `0x0020`. That is Gadgetbridge's `writeToChunked` and Heartline's `chunkedWrites(payload, type = 6)`. Each frame is `00 | (6 | flags) | counter | data`: flags 0x00 first, 0x40 middle, 0x80 last, 0xC0 single. The single-frame START above would be `00 C6 00 02 06 00 00 00 tt tt tt tt 00 00 00 00`. The reply comes on `0x0020` as `10 00 <frame flags> <pieceState> <profileState> [timeout u16]`, and Zepp treats `pieceState == 1` as success. The piece version is the single byte read from `0x0020` at connect (`HMPieceProfile.init`). This is the same channel and type that Gadgetbridge and Heartline already use for phone GPS (`SHOW` = 0x06 + SportInfo with the gpsPoint flag), which is where the "type 6, prefix 0x06" comes from.

### The 2021-channel version (Zepp OS and other newer devices; not used for the GTR 2e)

`com/huami/bluetooth/profile/channel/module/sport/SportSupplementPanelModule.java`: module **25 (0x0019)**. Version query `01` → `01 <ver>` (must be 1). Config `02 <kind>` → `01`. Control `03 <c> + SportInfo` with c = 1 start, 2 pause, 3 resume, 4 stop → `01`. Data `04 01 + SportInfo` (NOTIFY) or `04 00 + SportInfo` (others). Alive check `05`. Watch→phone goes to `CallbackHolder.onCommand` (same 1–4 codes) and `onInitCommand` (`type, needGps, kind, autoRecognize`). The factory only falls back to this module when `0x000f` is missing, so a GTR 2e would never get here.

## 2. Watch → phone: watch-started workout asks for phone GPS

- `com/xiaomi/hm/health/bt/profile/slre/HMLFProfile.java`: device events on `0x0010` (`uuid128(16)`). Byte 0 is `LfType`, and the rest goes into `HMLFEvent.extra`.
- `com/xiaomi/hm/health/bt/profile/slre/LfType.java`: `SPORT(20)` = **0x14**.
- `com/xiaomi/hm/health/device/service/HMCoreService.java`, `T(HMLFEvent)` (around line 547): when `extra[0] == 0` it calls `DeviceSportManager.get().init(...)`. A 1-byte extra means outdoor with GPS needed. A 3-byte extra `00 needGps kind` builds `SportConfig(SportKind.from(kind), SOURCE_DEVICE, needGps == 1)`. This matches Heartline's `watchWorkoutStart` (`14 00 needsGps type`). No device-feature check on this path.
- `com/xiaomi/hm/health/device/sport/DeviceSportManager.java`: `sendSportConfig` with source DEVICE. That registers the `0x000f` notify and sends **no** CONFIG write. If GPS is needed it starts phone location and the barometer, then streams `SHOW` through the piece channel (type 6): gpsState first (`06 01 00 00 00 02`, "locating"), then gpsPoint updates. It also sends a `NOTIFY` with temperature once. When the watch sends `STOP` (`11 05` on `0x000f`), it stops location and sends `STOP` back.
- `MyGpsDataSyncCallback.java:846` uses `hasFeatureDeviceSport` during GPS data sync. That flag (`DEVICE_SPORT_SOURCE_S`, "devices that need the app to send GPS points") is set only for ATHENS, BLED(\_W), CINCO(\_L), COMO\_L/\_WL, CORSICA, FALCON\_L/\_WL, HAWK\_L/\_WL, KONGMING(\_L/\_PRO), ONYX, PEYTO\_L/\_WL, TONLESAP\_L/\_OL, YORK. Those are bands without GPS. The GTR 2e is not on the list, which fits the watch never sending `0x14`.

## 3. Other leads checked (dead ends)

- `channel/module/openapp/OpenAppModule`: opens health pages on the watch (Activity, ECG, HR, Menstruation, PAI, Pressure, Sleep, SpO2, Temperature). It has no workout page.
- `ecf/data/AppState` (StartSport 1 / StopSport 2 / SportOngoing 3): `DeviceCenter` sends it only to the HEADSET device (earbuds).
- `bt/classic/HMSportController`, `HMFTMSProfile`, `com/huami/treadmill/...`: the Bolt treadmill over classic Bluetooth.
- `SubGPSSportService`, `com/huami/mifit/sportlib`: the phone-only GPS workout.
- A search for `startSport|startWorkout|sportControl|remoteSport|sportLinkage|launchSport|startTraining` across the BLE and device packages found nothing else.

## 4. Applies to the GTR 2e?

| Path | Exists in Zepp | Zepp uses it for GTR 2e | Could work on GTR 2e |
|---|---|---|---|
| Phone starts the watch's own recorded workout | no (no such command for any device) | n/a | n/a |
| Secondary-screen panel (`0x000f` / piece type 6) | yes | **no**, blocked by the `SPORT_SOURCE_S` gate | unknown: the characteristic exists, but there is no evidence the firmware implements the panel |
| Watch-started `0x14` → phone GPS | yes | yes (not gated), but the watch doesn't send `0x14` | already handled in Heartline |

Even if the panel works, it does **not** give "watch workout and phone GPS start together" in the watch's own records. The watch would show a phone-driven session, and the track would be the phone's. Heartline would have to save the workout itself, since the watch probably won't add it to its workout history.

## 5. How sure

- **High:** Zepp 6.3.1 has no phone→watch "start your own workout" command for Huami 2019/2020 devices, and it never sends panel commands to the GTR 2e. The gate (`DeviceCenter.s()` → `hasFeatureSport`) and the GTR 2e config were read directly.
- **High:** the byte formats above. They were read straight from `SportInfo.getBytes`, `HMSportProfile`, `HMNotifyResponse` and `HMPieceProfile`. `GattUtils.numberToBytes` is little-endian.
- **Low:** whether the GTR 2e firmware (V1.7.3.1) reacts to `01 01 01` / `02 …` on `0x000f`. That needs an on-watch test.

## 6. Test worth proposing (not run; ask the user first)

Low risk. It doesn't touch the activity fetch, does no `0x03` ack, deletes no data and doesn't unpair. Do it with no workout running on the watch.

1. Read `0x0020` (piece version, 1 byte) to pick transport A or B.
2. Enable notifications on `0x000f`.
3. Write `01 01 01` (CONFIG outdoor) and log any `0x000f` notification. `10 01 01` = understood; `10 01 03` = unknown command; silence = not implemented.
4. If step 3 succeeded, write START (`02 06 00 00 00 <now u32 LE> 00 00 00 00`), either raw on `0x000f` or as piece type 6 on `0x0020` (`00 C6 00 …`) if the version is ≥ 11. Watch the screen and photograph it. Log `10 02 xx`.
5. Optionally send one `SHOW` with hr/distance (`06 28 00 00 00 <dist u32> <hr u8>`, flags 0x08|0x20) to see whether the screen updates.
6. Write `05 00 00 00 00` (STOP) and expect `10 05 01`. Afterwards, check that the watch's workout list and history transfer still behave normally (no `10 01 07` "busy").

## 7. Step goal (UserInfo module 0x17, FLAG_STEP_GOAL 0x10)

Zepp's encoding is identical to Heartline's `stepGoal2021`:

- `com/xiaomi/hm/health/bt/device/HMMiLiProDevice.java`, `J6()` (line 625): uses the legacy `HMUserProfile` (characteristic `0x0008`, `profile/user/HMUserProfile.setGoal`) only if that characteristic exists. Otherwise it uses `getChannelModule(UserInfoModule.class)`. The GTR 2e has no `0x0008` (see `gatt_table.txt`), so Zepp uses the module.
- `com/xiaomi/hm/health/bt/profile/usernew/UserInfoModule.java`: `getModule() = 23` (0x0017). `setGoal(goal)` builds a `UserInfoDto` with only `stepGoal` set and calls `setUserInfo`.
- `com/xiaomi/hm/health/bt/profile/usernew/UserInfoParserKt.toBleBytes`: `01`, `u32 flags`, then fields in flag order. The step goal is a `u32` LE. So a goal of 8000 gives `01 10 00 00 00 40 1F 00 00`.
- Reply: `setUserInfo` returns true only for exactly `02 01`, which matches what the watch sends Heartline. A second wait of up to 15 s then accepts any 2-byte message on channel 2.
- Read: `UserInfoRequest.toBleByte` = `03` + `u32 flags`, and the answer comes back on opcode `04` (`UserInfoParserKt` reads `stepGoal` as `getUInt32`).
- Flags: birth 0x01, gender 0x02, height 0x04, weight 0x08, step goal 0x10, sensor location 0x20, uid 0x40, blood pressure 0x80, register region 0x100, idc 0x200, nickname 0x400.

Nothing in Heartline needs to change for the step goal.
