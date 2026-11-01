"""Microphone stream (raw sensor mask 0x20, packet type 0x05).

Packet payloads, joined in order, form a run of length-prefixed Opus frames: [len][TOC 0x08 ...]. TOC 0x08 is
SILK-only narrowband, 20 ms, mono, so 50 frames a second at roughly 11 kbit/s. Frames can span packets.
Decoded with PyAV's Opus decoder to 48 kHz int16 PCM and written to a WAV file as it arrives.
A lost packet would leave every later length byte misaligned, so the parser checks each frame's TOC byte and
resynchronises on the next plausible [len][0x08][..][len][0x08] boundary.
"""
import math
import pathlib
import wave

import av
import numpy as np

SAMPLE_RATE = 48000
TOC = 0x08
MIN_LEN, MAX_LEN = 8, 120  # observed frames are 20-35 bytes


def _boundary(buf: bytes, i: int) -> bool:
    """Does a frame plausibly start at i (and, if visible, is the next one plausible too)?"""
    if i + 1 >= len(buf) or not MIN_LEN <= buf[i] <= MAX_LEN or buf[i + 1] != TOC:
        return False
    j = i + 1 + buf[i]
    return j + 1 >= len(buf) or (MIN_LEN <= buf[j] <= MAX_LEN and buf[j + 1] == TOC)


def split_frames(buffer: bytes) -> tuple[list[bytes], bytes, int]:
    """Complete frames from `buffer`, the unfinished tail to keep, and how many bytes were skipped to resync."""
    frames, i, skipped = [], 0, 0
    while i < len(buffer):
        if i + 1 < len(buffer) and buffer[i + 1] != TOC or not MIN_LEN <= buffer[i] <= MAX_LEN:
            start = i
            while i < len(buffer) - 1 and not _boundary(buffer, i):
                i += 1
            skipped += i - start
            if i >= len(buffer) - 1:
                return frames, buffer[i:], skipped
        n = buffer[i]
        if i + 1 + n > len(buffer):
            break
        frames.append(buffer[i + 1:i + 1 + n])
        i += 1 + n
    return frames, buffer[i:], skipped


def level_dbfs(pcm: np.ndarray) -> float:
    """RMS level relative to full scale. Uncalibrated: compare sounds with each other, not with a sound meter."""
    if not len(pcm):
        return -120.0
    rms = math.sqrt(float(np.mean(pcm.astype(np.float64) ** 2)))
    return round(20 * math.log10(max(rms, 1) / 32768), 1)


class MicStream:
    """Feed raw 0x05 packets; writes a WAV and returns one level reading per decoded frame."""

    def __init__(self, path: pathlib.Path):
        self.path = path
        self._tail = b""
        self._last_counter: int | None = None
        self._ctx = av.CodecContext.create("opus", "r")
        self._ctx.sample_rate, self._ctx.layout = SAMPLE_RATE, "mono"
        self._wav = wave.open(str(path), "wb")
        self._wav.setnchannels(1)
        self._wav.setsampwidth(2)
        self._wav.setframerate(SAMPLE_RATE)
        self.stats = {"packets": 0, "gaps": 0, "frames": 0, "errors": 0, "skipped_bytes": 0}

    def feed_packet(self, packet: bytes) -> list[float]:
        """A whole 0x05 packet: [0x05][counter][payload]. Counter gaps drop the partial frame and resync."""
        counter = packet[1]
        self.stats["packets"] += 1
        if self._last_counter is not None and counter != (self._last_counter + 1) & 0xFF:
            self.stats["gaps"] += 1
            self._tail = b""
        self._last_counter = counter
        return self.feed(packet[2:])

    def feed(self, payload: bytes) -> list[float]:
        frames, self._tail, skipped = split_frames(self._tail + payload)
        self.stats["skipped_bytes"] += skipped
        levels = []
        for f in frames:
            try:
                for decoded in self._ctx.decode(av.Packet(f)):
                    a = decoded.to_ndarray().reshape(-1)
                    pcm = np.clip(a * 32767, -32768, 32767).astype(np.int16) if a.dtype.kind == "f" else a.astype(np.int16)
                    self._wav.writeframes(pcm.tobytes())
                    levels.append(level_dbfs(pcm))
                self.stats["frames"] += 1
            except av.error.FFmpegError:
                self.stats["errors"] += 1
        return levels

    @property
    def seconds(self) -> float:
        return self.stats["frames"] * 0.02

    def close(self) -> None:
        self._wav.close()
