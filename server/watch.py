"""Owns the single BLE connection to the watch: connect, auth, HR streaming, reads."""
import asyncio
import json
import os
import pathlib
import time
from typing import Callable

from bleak import BleakClient, BleakScanner

from . import protocol as p
from .store import Store

DEFAULT_CONFIG = pathlib.Path(r"C:\Users\abhir\Desktop\projects\amazfit-gtr2e\watch.json")
HR_PING_EVERY = 10.0
CONNECT_ATTEMPTS = 3
RECONNECT_DELAY = 10.0


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

    def _on_disconnect(self, client: BleakClient) -> None:
        if client is not self._client:
            return
        self._client = None
        self._stop_ping()
        self.streaming = False
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

    # ---- experiments ---------------------------------------------------------

    async def raw_probe(self, seconds: float = 5.0) -> dict:
        """Try to start the Huami raw-sensor stream and report what the watch answers."""
        client = self._require()
        replies: asyncio.Queue[bytes] = asyncio.Queue()
        packets = 0

        def on_data(_, d):
            nonlocal packets
            packets += 1

        async def send(cmd: bytes) -> str:
            await client.write_gatt_char(p.RAW_CTRL, cmd, response=False)
            try:
                return (await asyncio.wait_for(replies.get(), 3)).hex()
            except asyncio.TimeoutError:
                return ""

        async with self._lock:
            await client.start_notify(p.RAW_DATA, on_data)
            await client.start_notify(p.RAW_CTRL, lambda _, d: replies.put_nowait(bytes(d)))
            try:
                start = await send(bytes([0x01, 0x01, 0x19]))
                go = await send(bytes([0x02]))
                await asyncio.sleep(seconds)
                stop = await send(bytes([0x03]))
            finally:
                await client.stop_notify(p.RAW_CTRL)
                await client.stop_notify(p.RAW_DATA)

        supported = packets > 0
        note = (
            f"Raw sensor stream sent {packets} packets."
            if supported
            else f"The watch rejected the raw-sensor start command (reply {start or 'none'}) and sent no data."
        )
        self._event("info" if supported else "warn", f"Raw sensor probe: {note}")
        return {"supported": supported, "replies": {"start": start, "go": go, "stop": stop},
                "packets": packets, "note": note}
