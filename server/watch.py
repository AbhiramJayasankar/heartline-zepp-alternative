"""Owns the single BLE connection to the watch: connect, auth, HR streaming, reads."""
import asyncio
import csv
import json
import os
import pathlib
import time
from datetime import datetime, timedelta
from typing import Callable

from bleak import BleakClient, BleakScanner

from . import protocol as p
from .store import Store

DEFAULT_CONFIG = pathlib.Path(r"C:\Users\abhir\Desktop\projects\amazfit-gtr2e\watch.json")
HR_PING_EVERY = 10.0
CONNECT_ATTEMPTS = 3
RECONNECT_DELAY = 10.0
RAW_FLUSH_EVERY = 0.2  # seconds between raw WS messages (<= 5/s)
SYNC_OVERLAP = 5 * 60  # re-fetch the last minutes: the newest one may still have been filling up
SYNC_MAX_ROUNDS = 20
SYNC_FIRST_DAYS = 7
ROOT = pathlib.Path(__file__).resolve().parent.parent


class NotConnected(Exception):
    pass


def load_config() -> dict:
    env = os.environ.get("WATCH_CONFIG")
    local = pathlib.Path(__file__).resolve().parent.parent / "watch.json"
    path = pathlib.Path(env) if env else (local if local.exists() else DEFAULT_CONFIG)
    return json.loads(path.read_text())


class Watch:
    def __init__(self, cfg: dict, store: Store, emit: Callable[[dict], None]):
        self._mac = cfg["mac"]
        self._key = bytes.fromhex(cfg["auth_key"])
        self._model = cfg.get("model", "Amazfit")
        self._firmware = cfg.get("firmware", "")
        self._store = store
        self._emit = emit
        self._client: BleakClient | None = None
        self._lock = asyncio.Lock()
        self._connect_task: asyncio.Task | None = None
        self._ping_task: asyncio.Task | None = None
        self._want_connected = False
        self._want_hr = False
        self.connection = "disconnected"
        self.error: str | None = None
        self.battery: dict | None = None
        self.streaming = False
        self.last_bpm: int | None = None
        self.last_ts: float | None = None
        self.connected_since: float | None = None
        self.rssi: int | None = None
        self._gatt_cache: list[dict] | None = None
        self._want_raw = False
        self.raw_streaming = False
        self._raw_file: pathlib.Path | None = None
        self._raw_fh = None
        self._raw_csv = None
        self._raw_ppg: list[int] = []
        self._raw_accel: list[list[float]] = []
        self._raw_flush_task: asyncio.Task | None = None
        self._syncing = False

    # ---- state ---------------------------------------------------------------

    def status(self) -> dict:
        return {
            "connection": self.connection,
            "error": self.error,
            "model": self._model,
            "mac": self._mac,
            "firmware": self._firmware,
            "battery": self.battery,
            "hr": {"streaming": self.streaming, "last_bpm": self.last_bpm, "last_ts": self.last_ts},
            "connected_since": self.connected_since,
            "rssi": self.rssi,
            "raw": {
                "streaming": self.raw_streaming,
                "file": self._raw_file.relative_to(ROOT).as_posix() if self._raw_file else None,
            },
        }

    def _set(self, connection: str, error: str | None = None) -> None:
        self.connection, self.error = connection, error
        self._push_status()

    def _push_status(self) -> None:
        self._emit({"type": "status", **self.status()})

    def _event(self, level: str, msg: str) -> None:
        ts = time.time()
        self._store.add_event(ts, level, msg)
        self._emit({"type": "event", "ts": ts, "level": level, "msg": msg})

    def _require(self) -> BleakClient:
        if self._client is None or self.connection != "connected":
            raise NotConnected("The watch is not connected")
        return self._client

    # ---- connection ----------------------------------------------------------

    async def connect(self) -> None:
        self._want_connected = True
        if self._connect_task and not self._connect_task.done():
            return
        if self.connection == "connected":
            return
        self._connect_task = asyncio.create_task(self._connect_loop(CONNECT_ATTEMPTS))

    async def _connect_loop(self, attempts: int | None) -> None:
        n = 0
        while self._want_connected and self.connection != "connected":
            n += 1
            try:
                await self._connect_once()
                return
            except Exception as e:
                msg = str(e) or type(e).__name__
                await self._drop_client()
                self._set("error", msg)
                self._event("error", f"Connect attempt {n} failed: {msg}")
                if attempts is not None and n >= attempts:
                    self._want_connected = False
                    return
                await asyncio.sleep(RECONNECT_DELAY if attempts is None else 3)

    async def _connect_once(self) -> None:
        self._set("scanning")
        self._event("info", "Scanning for the watch")
        found = asyncio.Event()
        device = None

        def seen(dev, adv):
            nonlocal device
            if dev.address.upper() == self._mac.upper():
                device, self.rssi = dev, adv.rssi
                found.set()

        async with BleakScanner(detection_callback=seen):
            try:
                await asyncio.wait_for(found.wait(), 15)
            except asyncio.TimeoutError:
                raise RuntimeError(
                    "Watch not found. Keep it near the PC and turn off your phone's Bluetooth."
                ) from None

        self._set("connecting")
        client = BleakClient(device, timeout=45, disconnected_callback=self._on_disconnect)
        self._client = client
        await client.connect()

        self._set("authenticating")
        replies: asyncio.Queue[bytes] = asyncio.Queue()
        await client.start_notify(p.AUTH, lambda _, d: replies.put_nowait(bytes(d)))
        await client.write_gatt_char(p.AUTH, p.AUTH_REQUEST_RANDOM, response=False)
        await client.write_gatt_char(
            p.AUTH, p.auth_response(self._key, await asyncio.wait_for(replies.get(), 10)), response=False
        )
        reply = await asyncio.wait_for(replies.get(), 10)
        if reply[:3] != p.AUTH_OK:
            raise RuntimeError(f"Auth rejected ({reply.hex()}). The key may be stale; fetch it again.")
        await client.stop_notify(p.AUTH)

        self.connected_since = time.time()
        self._set("connected")
        self._event("info", "Connected and authenticated")
        self._gatt_cache = self._read_gatt(client)
        try:
            await self.read_battery()
        except Exception as e:
            self._event("warn", f"Battery read failed: {e}")
        if self._want_hr:
            await self.hr_start()
        if self._want_raw:
            await self.raw_start()

    def _on_disconnect(self, client: BleakClient) -> None:
        if client is not self._client:
            return
        self._client = None
        self._stop_ping()
        self.streaming = False
        self._close_raw()
        self.connected_since = None
        self._set("disconnected")
        if self._want_connected:
            self._event("warn", "Connection lost, reconnecting")
            self._connect_task = asyncio.create_task(self._connect_loop(None))
        else:
            self._event("info", "Disconnected")

    async def _drop_client(self) -> None:
        client, self._client = self._client, None
        if client is not None:
            try:
                await client.disconnect()
            except Exception:
                pass

    async def disconnect(self) -> None:
        self._want_connected = False
        self._want_hr = False
        if self._connect_task and not self._connect_task.done():
            self._connect_task.cancel()
        if self.streaming:
            try:
                await self.hr_stop()
            except Exception:
                pass
        if self.raw_streaming:
            try:
                await self.raw_stop()
            except Exception:
                pass
        self._want_raw = False
        self._close_raw()
        self._stop_ping()
        self.streaming = False
        await self._drop_client()
        self.connected_since = None
        if self.connection != "disconnected":
            self._set("disconnected")
            self._event("info", "Disconnected")

    async def shutdown(self) -> None:
        await self.disconnect()

    # ---- reads ---------------------------------------------------------------

    async def read_battery(self) -> dict:
        client = self._require()
        async with self._lock:
            data = await client.read_gatt_char(p.BATTERY)
        self.battery = p.parse_battery(bytes(data), time.time())
        self._push_status()
        return self.battery

    async def device_info(self) -> dict:
        client = self._require()
        info = {}
        async with self._lock:
            for name, uuid in p.DEVICE_INFO.items():
                try:
                    info[name] = p.decode_info(bytes(await client.read_gatt_char(uuid)))
                except Exception:
                    info[name] = None
        return info

    @staticmethod
    def _read_gatt(client: BleakClient) -> list[dict]:
        return [
            {
                "uuid": s.uuid,
                "name": s.description,
                "characteristics": [{"uuid": c.uuid, "properties": list(c.properties)} for c in s.characteristics],
            }
            for s in client.services
        ]

    def gatt(self) -> list[dict]:
        if self._client is not None and self.connection == "connected":
            self._gatt_cache = self._read_gatt(self._client)
        if self._gatt_cache is None:
            raise NotConnected("The watch is not connected and no GATT table is cached yet")
        return self._gatt_cache

    # ---- heart rate ----------------------------------------------------------

    def _on_hr(self, _, data: bytearray) -> None:
        bpm, ts = p.parse_hr(bytes(data)), time.time()
        if bpm > 0:
            self.last_bpm, self.last_ts = bpm, ts
            self._store.add_hr(ts, bpm)
        self._emit({"type": "hr", "ts": ts, "bpm": bpm})

    async def hr_start(self) -> bool:
        client = self._require()
        self._want_hr = True
        if self.streaming:
            return True
        async with self._lock:
            await client.start_notify(p.HR_MEASURE, self._on_hr)
            await client.write_gatt_char(p.HR_CONTROL, p.HR_STOP_MANUAL, response=True)
            await client.write_gatt_char(p.HR_CONTROL, p.HR_START_CONTINUOUS, response=True)
        self.streaming = True
        self._ping_task = asyncio.create_task(self._ping_loop(client))
        self._push_status()
        self._event("info", "Live heart rate started")
        return True

    async def _ping_loop(self, client: BleakClient) -> None:
        while True:
            await asyncio.sleep(HR_PING_EVERY)
            try:
                async with self._lock:
                    await client.write_gatt_char(p.HR_CONTROL, p.HR_PING, response=True)
            except Exception as e:
                self._event("warn", f"Heart-rate keep-alive failed: {e}")
                return

    def _stop_ping(self) -> None:
        if self._ping_task and not self._ping_task.done():
            self._ping_task.cancel()
        self._ping_task = None

    async def hr_stop(self) -> bool:
        self._want_hr = False
        client = self._require()
        self._stop_ping()
        if self.streaming:
            async with self._lock:
                await client.write_gatt_char(p.HR_CONTROL, p.HR_STOP_CONTINUOUS, response=True)
                await client.stop_notify(p.HR_MEASURE)
            self.streaming = False
            self._push_status()
            self._event("info", "Live heart rate stopped")
        return False

    # ---- raw sensor stream ---------------------------------------------------

    async def _raw_begin(self, client: BleakClient, on_data, on_ctrl) -> None:
        """Enable notifications and send Gadgetbridge's 3-step start."""
        await client.start_notify(p.RAW_DATA, on_data)
        await client.start_notify(p.RAW_CTRL, on_ctrl)
        for cmd in p.RAW_START:
            await client.write_gatt_char(p.RAW_CTRL, cmd, response=False)
            await asyncio.sleep(0.4)

    async def raw_start(self) -> bool:
        client = self._require()
        self._want_raw = True
        if self.raw_streaming:
            return True
        self._open_raw_file()
        async with self._lock:
            await self._raw_begin(client, self._on_raw, lambda _, d: None)
        self.raw_streaming = True
        self._raw_flush_task = asyncio.create_task(self._raw_flush_loop())
        self._push_status()
        self._event("info", f"Raw sensor stream started, recording to {self.status()['raw']['file']}")
        return True

    async def raw_stop(self) -> bool:
        self._want_raw = False
        client = self._require()
        if self.raw_streaming:
            async with self._lock:
                await client.write_gatt_char(p.RAW_CTRL, p.RAW_STOP, response=False)
                await client.stop_notify(p.RAW_CTRL)
                await client.stop_notify(p.RAW_DATA)
            self._close_raw()
            self._push_status()
            self._event("info", "Raw sensor stream stopped")
        return False

    def _open_raw_file(self) -> None:
        folder = ROOT / "data" / "raw"
        folder.mkdir(parents=True, exist_ok=True)
        self._raw_file = folder / f"{datetime.now():%Y%m%d-%H%M%S}.csv"
        self._raw_fh = self._raw_file.open("w", newline="")
        self._raw_csv = csv.writer(self._raw_fh)
        self._raw_csv.writerow(["ts", "kind", "ppg", "ax", "ay", "az"])

    def _close_raw(self) -> None:
        self.raw_streaming = False
        if self._raw_flush_task and not self._raw_flush_task.done():
            self._raw_flush_task.cancel()
        self._raw_flush_task = None
        if self._raw_fh is not None:
            self._raw_fh.close()
        self._raw_fh = self._raw_csv = None
        self._raw_ppg, self._raw_accel = [], []

    def _on_raw(self, _, data: bytearray) -> None:
        kind, samples = p.parse_raw(bytes(data))
        if not samples or self._raw_csv is None:
            return
        now, n = time.time(), len(samples)
        # Samples in a packet are ~1/25 s apart and the packet arrives after the last one.
        for i, v in enumerate(samples):
            ts = round(now - (n - 1 - i) / p.RAW_RATE_HZ, 3)
            if kind == "ppg":
                self._raw_csv.writerow([ts, "ppg", v, "", "", ""])
            else:
                self._raw_csv.writerow([ts, "accel", "", *v])
        if kind == "ppg":
            self._raw_ppg.extend(samples)
        else:
            self._raw_accel.extend(list(v) for v in samples)

    async def _raw_flush_loop(self) -> None:
        while True:
            await asyncio.sleep(RAW_FLUSH_EVERY)
            if self._raw_ppg or self._raw_accel:
                msg = {"type": "raw", "ts": time.time(), "ppg": self._raw_ppg, "accel": self._raw_accel}
                self._raw_ppg, self._raw_accel = [], []
                self._emit(msg)
                if self._raw_fh is not None:
                    self._raw_fh.flush()

    async def raw_probe(self, seconds: float = 5.0) -> dict:
        """Run the 3-step raw-sensor start for a few seconds and report what the watch answers."""
        client = self._require()
        if self.raw_streaming:
            raise RuntimeError("Stop the raw sensor stream before running the probe")
        replies: list[str] = []
        packets = 0

        def on_data(_, d):
            nonlocal packets
            packets += 1

        async with self._lock:
            try:
                await self._raw_begin(client, on_data, lambda _, d: replies.append(bytes(d).hex()))
                await asyncio.sleep(seconds)
                await client.write_gatt_char(p.RAW_CTRL, p.RAW_STOP, response=False)
                await asyncio.sleep(0.5)
            finally:
                await client.stop_notify(p.RAW_CTRL)
                await client.stop_notify(p.RAW_DATA)

        # One reply per command, in order: start step 1, start step 2, go (02), stop (03).
        start, _, go, stop = (replies + ["", "", "", ""])[:4]
        supported = packets > 0
        note = (f"Raw sensor stream sent {packets} packets in {seconds:.0f} s." if supported
                else f"The watch sent no raw data (start reply {start or 'none'}).")
        self._event("info" if supported else "warn", f"Raw sensor probe: {note}")
        return {"supported": supported, "replies": {"start": start, "go": go, "stop": stop},
                "packets": packets, "note": note}

    # ---- activity history ----------------------------------------------------

    async def _fetch_once(self, client: BleakClient, kind: int, since: datetime) -> tuple[float | None, bytes]:
        """One fetch round. Never sends the 0x03 ack, so the data stays on the watch."""
        ctrl: asyncio.Queue[bytes] = asyncio.Queue()
        buf = bytearray()

        def on_data(_, d):
            buf.extend(bytes(d)[1:])  # first byte is a packet counter

        async with self._lock:
            await client.start_notify(p.ACT_CTRL, lambda _, d: ctrl.put_nowait(bytes(d)))
            await client.start_notify(p.ACT_DATA, on_data)
            await client.write_gatt_char(p.ACT_CTRL, p.fetch_command(kind, since), response=False)
        try:
            count, start = p.parse_fetch_start(await asyncio.wait_for(ctrl.get(), 10))
            if not count:
                return None, b""
            async with self._lock:
                await client.write_gatt_char(p.ACT_CTRL, p.FETCH_GO, response=False)
            done = await asyncio.wait_for(ctrl.get(), 120)
            if done[:3] != b"\x10\x02\x01":
                raise RuntimeError(f"History transfer ended with {done.hex()}")
            return start, bytes(buf)
        finally:
            async with self._lock:
                await client.stop_notify(p.ACT_DATA)
                await client.stop_notify(p.ACT_CTRL)

    async def activity_sync(self) -> dict:
        client = self._require()
        if self._syncing:
            raise RuntimeError("A history sync is already running")
        self._syncing = True
        try:
            now = datetime.now().astimezone()
            last = self._store.activity_last_ts()
            since = (datetime.fromtimestamp(last - SYNC_OVERLAP + 60).astimezone() if last
                     else now - timedelta(days=SYNC_FIRST_DAYS))
            self._event("info", f"Syncing activity history from {since:%d %b %H:%M}")
            added, first_ts, last_ts = 0, None, None
            for _ in range(SYNC_MAX_ROUNDS):
                if since > now:
                    break
                start, data = await self._fetch_once(client, p.FETCH_ACTIVITY, since)
                rows = p.parse_activity(data, start) if start is not None else []
                if not rows:
                    break
                added += self._store.upsert_activity(rows)
                first_ts = first_ts or rows[0]["ts"]
                last_ts = rows[-1]["ts"]
                self._event("info", f"Fetched {len(rows)} minutes from {datetime.fromtimestamp(rows[0]['ts']):%d %b %H:%M}")
                nxt = datetime.fromtimestamp(last_ts + 60).astimezone()
                if nxt <= since:
                    break
                since = nxt
            self._store.set_meta("activity_last_sync", str(time.time()))
            self._event("info", f"Activity sync done: {added} new minutes")

            def iso(ts):
                return datetime.fromtimestamp(ts).astimezone().isoformat() if ts else None

            return {"minutes_added": added, "from": iso(first_ts), "to": iso(last_ts)}
        finally:
            self._syncing = False

    # ---- find device ---------------------------------------------------------

    async def vibrate(self, seconds: float = 3.0) -> dict:
        """Gadgetbridge's find-device: alert level 3 on 0x2A06, then 0 to stop."""
        client = self._require()
        async with self._lock:
            await client.write_gatt_char(p.ALERT_LEVEL, p.ALERT_FIND, response=False)
        await asyncio.sleep(seconds)
        async with self._lock:
            await client.write_gatt_char(p.ALERT_LEVEL, p.ALERT_OFF, response=False)
        self._event("info", "Sent find-watch vibration")
        return {"ok": True}
