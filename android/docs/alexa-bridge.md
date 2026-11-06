# Alexa button on the GTR 2e

Heartline answers the watch's Alexa button itself: the watch streams the voice to the phone, Gemini turns it into
text, an OpenRouter model decides the answer, and the phone sends a reply the watch shows. Found on the owner's watch (firmware V1.7.3.1) on 2026-11-03, with
the command meanings completed from the Zepp app 10.9.0. Code: `core/protocol/.../Chunked2021.kt`,
`app/.../service/AlexaBridge.kt`, `GeminiSpeech.kt`, `AssistantAi.kt`, `WatchAssistant.kt`.

## Transport

The 2021 chunked channel: the phone writes on characteristic `0016`, the watch notifies on `0017`. Frames are
`03 | flags | handle | count`, and the first frame adds `u32 length | u16 endpoint`; Alexa is endpoint `0x0011`.
Flags: `01` first, `02` last, `04` wants an ack, `08` encrypted (not used by this watch).

Acks are `04 handle 01 count`, written to `0017`. Gadgetbridge sends `04 00 handle 01 count`, which is the Zepp OS
(extended header) layout; this watch reads that as handle 00, never sees its start acknowledged, buffers its voice
and gives up with "please make sure the watch is connected".

## Session

| Step | Direction | Bytes | What happens |
|---|---|---|---|
| Start | watch | `01 02 06 01` + JSON (`countdown`, `maxAlarmCount`, `ttsTextLimit`, ...) | Watch shows "Connecting" |
| Start ack | phone | `03 00`, ~700 ms later | Watch shows "Listening" |
| Voice | watch | `05 | u32 length | (u8 size, Opus packet)*` | Ten 20 ms SILK narrowband packets (TOC `08`) every 200 ms, live |
| Stop listening | phone | `06` (Alexa's StopCapture) | Watch shows "Thinking" and stops the voice |
| Reply | phone | `09 text 00` | Watch shows the text; long text scrolls |
| Listen again | phone | `0b`, ~600 ms after a reply (Alexa's ExpectSpeech) | Watch reopens the mic by itself, reply kept above |
| End | watch | `02` | Watch closed the session |

Without the stop, the watch listens to its 30 s limit; `02` from the phone does not stop it and lifts that limit.
After "Thinking" it waits about 40 s for a reply before "I didn't hear you clearly". The small Alexa button under a
reply streams voice again in the same session (no new start).

## Replies (`08 | u16 subtype | u32 length | body`)

| Subtype | Body | On the GTR 2e |
|---|---|---|
| 1 weather | u32 field mask `0x3fd`, then time, location, date line, description, icon (name, url), current, high, low, forecast days (time, high, low, icon) | Shown |
| 2 alerts | `op kind token\0 details`: op 1 add, 2 delete; kind 1 alarm, 2 reminder, 3 timer; timer details `h m s`; alarm/reminder `label\0 label\0 u32 wall-clock time, u32 0, u32 repeat days` | Timer shown and rings |
| 3 list | `title\0 count (left\0 right\0 \0)* subtitle\0` | Shown (a narrow left column) |
| 4 local actions | `action [args]` | Not tried |
| 6 card | `title\0 subtitle\0 text\0` | Zepp sends it only to assistant version 3+; this watch reports 2 (`21 02 01`) and ignores it |

Other commands: `07 flag text` shows up to 50 characters, `0a flag u32 length audio` is spoken audio, `0f code text`
an error, `14` / `15` set and clear the notification indicator, `10`/`11` languages (current `en-US`, 14 English
variants and others), `20`/`21` capabilities.

## Speech-to-text: Gemini

Android's own recognizer accepts recorded audio (`EXTRA_AUDIO_SOURCE`) but returned nothing for these clips on the
S24 Ultra. Vosk's small offline model worked but misheard too much and added 60 MB to the app, so it was removed
(2026-11-06). Speech-to-text is **Gemini 3.5 Transcribe Live** over the Live API WebSocket with the owner's AI Studio
key (free tier, no request limit in 2026-11), `GeminiSpeech.kt`. One session per question: setup with SMART
formatting (numbers and times as digits) and automatic activity detection off, then `activityStart`, 100 ms base64
16 kHz PCM chunks, `activityEnd`; the text arrives as `serverContent.inputTranscription`. 12 s timeout.

## Answers: OpenRouter with tool calling

`AssistantAi.kt` sends what Gemini heard to the owner's OpenRouter model (default `nvidia/nemotron-3.5-super-vl:free`,
falling back to `nvidia/nemotron-3.5-lightning:free` and `google/gemma-4-31b-it:free`, all with tool calling; never the
`openrouter/free` router, which picked a coding model that ignored the format). The instructions carry the time, the
latest heart rate, today's steps, the weather where the phone is, the alarms, timers and reminders set by voice (with
ids), the workout capture running, a short glance at today (readiness, scores, last night, heart rate, stress,
activity) and the last six turns of the conversation (a 2-minute pause starts a new one).

The model then calls tools (OpenRouter's `tools`, up to 6 rounds), and its final words are the watch's reply:

- **Health data** (`HealthTools.kt`, read-only, from the same data the app's screens show): `get_heart_rate`,
  `get_stress`, `get_sleep` (with a stage timeline), `get_activity`, `get_spo2_and_temperature`, `get_readiness`,
  `get_trends`, `get_workout_captures`; each takes a date where it makes sense and returns compact text.
- **Actions** (`WatchAssistant.kt`): `set_timer`, `set_alarm` (a time, or seconds from now rounded up to the minute),
  `set_reminder`, `cancel_alerts` (by id), `get_weather`, `show_weather_card`, `show_help`, `start_workout`,
  `stop_workout`, `sync_watch`. Timers and alarms go to the watch as Alexa alerts. Reminders go into the watch's own
  reminder slots (shared with calendar events, merged by time, 16-character titles), because the Alexa reminder alert
  rang without showing its text. The voice alerts are kept in `meta["alexa_alerts"]` until they ring, so they can be
  cancelled by voice; the agenda sync rewrites the reminder slots whenever that list changes.

Long answers (a story, a detailed explanation) are written in full: the reply screen shows as much as the watch's
`ttsTextLimit` allows (sent in the start JSON, logged once; the GTR 2e sends 0, so 2,000 bytes: the reply screen showed
about 2,045 bytes and dropped the rest), cut at a sentence, and the rest follows as numbered watch notifications of
up to 960 bytes of text each (a whole notification may be 1,024 bytes; 1,025 restarts the watch, tested 2026-11-06). A Gemini
timeout is reported on the watch; before, it surfaced as a cancellation and the turn ended in silence until the watch
said "I didn't hear you clearly". Weather icons are Alexa's: a description and the image URL whose file name names the icon (the Zepp app passes
Alexa's values on; a name with no URL showed a question mark). Alexa's image server has eight
(`images-na.ssl-images-amazon.com/images/G/01/alexa/avs/gui/small/weatherForecast/<name>.png`): sunny, partly_cloudy,
partly_cloudy_night, cloudy, rainy, snow, windy and lightning, so every condition maps to one of those. When no speech is
heard at all, the watch is told so at once instead of waiting on Gemini. A reply that ends with a question reopens the
watch's mic. After a card (weather, the help list) no text is sent, since
it would replace the card. Reasoning is turned off for speed; a model that requires it (`Reasoning is mandatory for this
endpoint`) is asked again with low-effort reasoning and 4000 tokens for the rest of the conversation. Replies are cleaned
for the watch's font (plain punctuation, no emoji). Log lines never pass text through a format string: a "%" in an
answer (humidity 66%) crashed the app on the weather question (2026-11-06).

There are no built-in answers. Whatever fails is shown on the watch instead: no Gemini or OpenRouter key, Gemini or
OpenRouter errors (OpenRouter's own message, e.g. a rate limit), no words heard, or no weather. The watch waits about
40 s on "Thinking"; Gemini plus OpenRouter usually take 3 to 6 s. If there is no answer 34 s after the stop, the watch
is told "Still thinking" and the answer arrives later as a watch notification (OpenRouter may take up to 90 s).

Each turn is saved under `files/alexa/` (the voice messages `.bin`, 48 kHz `.wav` and the 16 kHz `-16k.wav` sent to
Gemini); the newest 30 turns are kept. The Alexa answers screen can run the last recording or a typed question through
the same pipeline without sending anything to the watch.
