# Alexa button on the GTR 2e

Heartline answers the watch's Alexa button itself: the watch streams the voice to the phone, a speech model turns it
into text, an answer model decides the answer (both chosen under AI models), and the phone sends a reply the watch shows. Found on the owner's watch (firmware V1.7.3.1) on 2026-11-03, with
the command meanings completed from the Zepp app 10.9.0. Code: `core/protocol/.../Chunked2021.kt`,
`app/.../service/AlexaBridge.kt`, `VoiceToText.kt`, `GeminiSpeech.kt`, `AssistantAi.kt`, `WatchAssistant.kt`, `service/ai/`.

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
| Listen again | phone | `0b`, 600 ms after a reply (Zepp's ContinueCommand, Alexa's ExpectSpeech) | Watch reopens the mic; above it, the last sentence with a "?" from the latest reply that had one |
| End | watch | `02` | Watch closed the session |

Without the stop, the watch listens to its 30 s limit; `02` from the phone does not stop it and lifts that limit.
After the stop it waits about 30 s for a reply before "I didn't hear you clearly": an answer 28 s after the stop
showed, "Still thinking" 34 s after came too late (2026-11-09). The small Alexa button under a reply streams voice
again in the same session (no new start).

While listening again the watch shows a question from the replies, not the newest reply: the last sentence with a
"?" from the latest reply that had one, even from an earlier session ("Knock knock!" left "What can I do for you?"
on screen, 2026-11-09). So a reply that reopens the mic ends with "?" on the watch (`WatchAssistant.endAsQuestion`).

The watch's status notifications on `0016` (`04 n 01 x`) follow each reply; `x` grows with the reply's length (1 for
"Knock knock!", 11 for a long reply), so it looks like the number of lines or screens shown.

## Replies (`08 | u16 subtype | u32 length | body`)

| Subtype | Body | On the GTR 2e |
|---|---|---|
| 1 weather | u32 field mask `0x3fd`, then time, location, date line, description, icon (name, url), current, high, low, forecast days (time, high, low, icon) | Shown |
| 2 alerts | `op kind token\0 details`: op 1 add, 2 delete; kind 1 alarm, 2 reminder, 3 timer; timer details `h m s`; alarm/reminder `label\0 label\0 u32 wall-clock time, u32 0, u32 repeat days, u8 enabled` | Timer shown and rings. An alarm set only lands inside a session (slot 10 and up, at most 5). Deletes (`02 kind token`, or version 3's `02 kind count tokens`) are acked and ignored in and out of a session, as are phone writes to slots 10 and up; the owner deletes them with a long press in the watch's Alarm app (2026-11-09) |
| 3 list | `title\0 count (left\0 right\0 \0)* subtitle\0` | Shown and scrolls (25 rows tried); the left column shows about 10 characters ("3. Kangch") and cuts the rest, so `show_list` moves long left text into the right column; the right one wraps and takes new lines; an empty title, subtitle or left column is fine; the third field of a row showed nothing |
| 4 local actions | `action [args]` | Not tried |
| 6 card | `title\0 subtitle\0 text\0` | Zepp sends it only to assistant version 3+; this watch reports 2 (`21 02 01`) and ignores it |

Other commands: `07 flag text` shows up to 50 characters, `0a flag u32 length audio` is spoken audio, `0f code text`
an error, `14` / `15` set and clear the notification indicator, `10`/`11` languages (current `en-US`, 14 English
variants and others), `20`/`21` capabilities.

## What the watch draws (tested 2026-11-06)

Reply text (09) and notifications use the same font. New lines and blank lines are kept; markdown is shown as typed
(no bold, headings or tables). Every script tried shows: Devanagari, Malayalam, Tamil, Telugu, Kannada, Bengali, Arabic,
Hebrew, Cyrillic, Greek, Chinese, Japanese, Korean, Thai, Vietnamese. So do the usual symbols (° ± ½ × ÷ ≈ ≤ ² ‰ € £ ¥
¢ © ® ™ § ¶ µ, arrows, ▲ ▼ ◆ ○ □ ■ ● ◦ ▪ ① Ⅱ, box drawing) and, of the emoji-style symbols, ✓ ★ ☆ ♪ ♫ ♥ ☂ ☎ ☀ ☁ ⚠ ⚽.
Missing (a box or nothing): • ‣ ▸ ► ₹ ✗ ✘ ✔ ☑ ➜ ❤ ⚡ ⌚ ⏰ ❄. Emoji were tried one by one through the face block
U+1F600 to U+1F64F, the hands, body and people U+1F440 to U+1F46F and the sports U+1F3C0 to U+1F3CA, plus about 60
others: the watch has U+1F600 to U+1F637 except 😊 😩, then 🙆 🙇 🙌 🙏, 👀 👂 👃 👄 👅 👇 👈 👉 👊 👋 👌 👍 👏 👐 👦 👧 👩 👪 👭 👮 👯,
💪 🏃 🚶, and none of the hearts, weather, food, objects, ✅ ❌ ⭐ 🔥 🎉 or flags. Combined emoji show their parts
(👨‍👩‍👧 as 👩👧), skin tones and keycaps nothing. `WatchAssistant.watchText` drops what's missing (• and ▸ become "-", ₹
"Rs") and turns markdown into plain text; the instructions tell the model what it can use and list the emoji.

## Speech-to-text

Android's own recognizer accepts recorded audio (`EXTRA_AUDIO_SOURCE`) but returned nothing for these clips on the
S24 Ultra. Vosk's small offline model worked but misheard too much and added 60 MB to the app, so it was removed
(2026-11-06). Speech-to-text is **Gemini 3.5 Transcribe Live** over the Live API WebSocket with the owner's AI Studio
key (free tier, no request limit in 2026-11), `GeminiSpeech.kt`. One session per question: setup with SMART
formatting (numbers and times as digits) and automatic activity detection off, then `activityStart`, 100 ms base64
16 kHz PCM chunks, `activityEnd`; the text arrives as `serverContent.inputTranscription`. A 29 s question took it
about 10 s. Any other model that takes audio can do it too (`VoiceToText.kt` sends the recording as a WAV with
instructions). The models, their fallbacks and the time limit (30 s) are the Voice to text section under AI models;
the languages (en-IN, en-US) and expected words are Alexa settings.

Listening ends 1.5 s after the speaker stops (`SpeechEndpointer`): a frame counts as speech when it is 15 dB over the
room's floor and, once they've started, within 18 dB of their loudest. Without that second rule, room sounds around
-45 dBFS kept short questions listening to the cap. Replayed on 30 saved questions and transcribed again, it ended 15
sooner and lost no words. 7 s with nothing said gives up; 29 s is the most, just under the watch's own 30 s.

## Answers: the chosen model with tool calling

`AssistantAi.kt` sends what was heard to the Watch assistant section's main model (any model with tool calling from
OpenRouter, Gemini or an OpenAI-compatible API), through `AiRouter`: out of quota or refused moves to the provider's
next key, then the next model, and the conversation carries on there, tool results and all. Never the
`openrouter/free` router, which picked a coding model that ignored the format. The instructions carry the time, the
latest heart rate, today's steps, the weather where the phone is, the alarms, timers and reminders set by voice (with
ids), the workout capture running, a short glance at today (readiness, scores, last night, heart rate, stress,
activity) and the last six turns of the conversation (a 2-minute pause starts a new one).

The model then calls tools (up to 25 rounds, 180 s per request, 15 minutes per answer), and its final words are the
watch's reply:

- **Health data** (`HealthTools.kt`, read-only, from the same data the app's screens show): `get_heart_rate`,
  `get_stress`, `get_sleep` (with a stage timeline), `get_activity`, `get_spo2_and_temperature`, `get_readiness`,
  `get_trends`, `get_workout_captures`; each takes a date where it makes sense and returns compact text.
- **Web** (`WebSearch.kt`): `web_search` through Tavily (1,000 free searches a month, its short answer plus the top
  pages' snippets), falling back to Exa ($10 of free credit a month, the pages' text); both keys are optional.
- **Actions** (`WatchAssistant.kt`): `set_timer`, `set_alarm` (a time, or seconds from now rounded up to the minute),
  `set_reminder`, `cancel_alerts` (by id), `get_weather`, `show_weather_card`, `show_help`, `show_list` (a table
  on the list card: title, subtitle and up to 30 rows of left and right text, kept within 2,000 bytes), `start_workout`,
  `stop_workout`, `sync_watch`. Timers and alarms go to the watch as Alexa alerts. Reminders go into the watch's own
  reminder slots (shared with calendar events, merged by time, 16-character titles), because the Alexa reminder alert
  rang without showing its text. The voice alerts are kept in `meta["alexa_alerts"]` until they ring, so they can be
  cancelled by voice; the agenda sync rewrites the reminder slots whenever that list changes.

Long answers (a story, a detailed explanation) are written in full: the reply screen shows as much as the watch's
`ttsTextLimit` allows (sent in the start JSON, logged once; the GTR 2e sends 0, so 2,000 bytes: the reply screen showed
about 2,045 bytes and dropped the rest), cut at a sentence and ending "(full reply in notifications)", and the whole
reply follows as numbered watch notifications of up to 960 bytes of text each (a whole notification may be 1,024 bytes; 1,025 restarts the watch, tested 2026-11-06). A Gemini
timeout is reported on the watch; before, it surfaced as a cancellation and the turn ended in silence until the watch
said "I didn't hear you clearly". Weather icons are Alexa's: a description and the image URL whose file name names the icon (the Zepp app passes
Alexa's values on; a name with no URL showed a question mark). Alexa's image server has eight
(`images-na.ssl-images-amazon.com/images/G/01/alexa/avs/gui/small/weatherForecast/<name>.png`): sunny, partly_cloudy,
partly_cloudy_night, cloudy, rainy, snow, windy and lightning, so every condition maps to one of those. When no speech is
heard at all, the watch is told so at once instead of waiting on speech-to-text. The mic reopens only when the model
ends its reply with `[listen]` (a reply ending in "?" caught rhetorical questions and offers); Heartline removes the
tag, and a mention of it mid-sentence doesn't count. A long reply that asks keeps its question on the reply screen.
After a card (weather, a list) no text is sent, since it would replace the card. Reasoning is a setting (auto leaves it to
the model); a model that requires it (`Reasoning is mandatory for this endpoint`) is asked again with low effort, and one
that refuses the token budget is asked again with 8,192 tokens, both remembered for that model. Replies are cleaned
for the watch's font (see What the watch draws). Log lines never pass text through a format string: a "%" in an
answer (humidity 66%) crashed the app on the weather question (2026-11-06). A reply that says an alarm, timer,
reminder, workout or sync was set, cancelled, started or stopped while no action tool ran is sent back once, telling
the model that nothing happened: Nemotron answered "Cancel my water reminder" with "Cancelled." and no tool call, and
the reminder rang (2026-11-06).

There are no built-in answers. Whatever fails is shown on the watch instead: no model with a key, the provider's
error (e.g. a rate limit), no words heard, or no weather. The watch waits about 30 s after the stop; speech and answer
usually take 3 to 6 s. If there is no answer 25 s after the stop, the watch is told "Still thinking" and the answer
arrives later as watch notifications, however long the model takes (up to the 15-minute limit).

Each turn is saved under `files/alexa/` (the voice messages `.bin`, 48 kHz `.wav` and the 16 kHz `-16k.wav` sent to
Gemini); the newest 30 turns are kept.

## Settings and the conversation log

Everything above that is a choice is an Alexa setting (`AlexaSettings.kt`, `meta["alexa_settings"]`, read fresh for
each question), shown on the app's Alexa page (Watch, Alexa): the instructions (a template whose placeholders `{NOW}`,
`{HEART_RATE}`, `{STEPS}`, `{SYNCED_UP_TO}`, `{ALERTS}`, `{WORKOUT}` and `{HEALTH}` are filled each time; blank means
Heartline's own; a saved prompt with the follow-up wording used until 2026-11-09 gets the new paragraph), each tool on
or off and its description, reasoning (auto, off, low, medium, high), temperature, token limits, requests per
question, the answer's time limit, how many earlier turns are sent and after how long a new conversation starts, the
reply and notification sizes, the wait before "Still thinking", listening again, the languages and words to expect,
web results per search, and how many turns are kept. The models, keys and retry rules are under AI models
(`meta["ai_setup"]`). Values are clamped to what works (a reply of at most 2,000 bytes, a wait of at most 28 s), and
saved settings still on an old default move to the new one.

Every question goes into the conversation log (`meta["alexa_turns"]`, newest first, as many as are kept): what was
heard and how long it took, each model request (provider, model, key, reasoning, time, tokens), each tool call with its
arguments and result, the reply, how it reached the watch, any error, and the instructions as sent. The app shows it
with the recording to play. Questions typed into the app's "Try a question" run the same pipeline without sending
anything to the watch and are logged too.
