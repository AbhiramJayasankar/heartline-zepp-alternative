# Alexa button on the GTR 2e

Heartline answers the watch's Alexa button itself: the watch streams the voice to the phone, the phone turns it into
text offline and sends a reply the watch shows. Found on the owner's watch (firmware V1.7.3.1) on 2026-11-03, with
the command meanings completed from the Zepp app 10.9.0. Code: `core/protocol/.../Chunked2021.kt`,
`app/.../service/AlexaBridge.kt`, `VoskSpeech.kt`, `SampleAssistant.kt`.

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
| 3 list | `title\0 count (left\0 right\0 \0)* subtitle\0` | Not yet tried |
| 4 local actions | `action [args]` | Not tried |
| 6 card | `title\0 subtitle\0 text\0` | Zepp sends it only to assistant version 3+; this watch reports 2 (`21 02 01`) and ignores it |

Other commands: `07 flag text` shows up to 50 characters, `0a flag u32 length audio` is spoken audio, `0f code text`
an error, `14` / `15` set and clear the notification indicator, `10`/`11` languages (current `en-US`, 14 English
variants and others), `20`/`21` capabilities.

## Speech-to-text

Android's own recognizer accepts recorded audio (`EXTRA_AUDIO_SOURCE`) but returned nothing for these clips on the
S24 Ultra, on-device or not (and needs the microphone permission even then). Heartline uses Vosk with the small
English model instead: about 0.5–1 s per question, offline. The model is fetched into the app's assets at build time.
