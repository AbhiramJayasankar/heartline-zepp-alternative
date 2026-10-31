"""Clock, realtime steps, device events, config acks, notification, temperature buffer drift."""
import asyncio, datetime as dt, json, time
from bleak import BleakClient, BleakScanner
from live_hr import CFG, AUTH, HR_CONTROL, auth
from discover_fetch import fetch

U = lambda n: f"0000{n}-0000-3512-2118-0009af100700"
TIME = "00002a2b-0000-1000-8000-00805f9b34fb"
STEPS, EVENTS, CONFIG, CHUNKED = U("0007"), U("0010"), U("0003"), U("0020")

async def main():
    dev = await BleakScanner.find_device_by_address(CFG["mac"], timeout=15)
    q = asyncio.Queue(); log = []
    async with BleakClient(dev, timeout=45) as c:
        await c.start_notify(AUTH, lambda _, d: q.put_nowait(bytes(d)))
        await auth(c, q)
        print("mtu", c.mtu_size)
        t = await c.read_gatt_char(TIME); print("watch time raw", bytes(t).hex(), "pc", dt.datetime.now())
        s = await c.read_gatt_char(STEPS); print("steps raw", bytes(s).hex(), len(s))
        await c.start_notify(STEPS, lambda _, d: log.append(("steps", bytes(d).hex())))
        await c.start_notify(EVENTS, lambda _, d: log.append(("event", bytes(d).hex())))
        cfg = asyncio.Queue()
        await c.start_notify(CONFIG, lambda _, d: cfg.put_nowait(bytes(d)))
        hrq = asyncio.Queue()
        await c.start_notify(HR_CONTROL, lambda _, d: hrq.put_nowait(bytes(d))) if "notify" in c.services.get_characteristic(HR_CONTROL).properties else None
        async def conf(name, data):
            await c.write_gatt_char(CONFIG, bytes(data), response=False)
            try: r = (await asyncio.wait_for(cfg.get(), 3)).hex()
            except asyncio.TimeoutError: r = None
            print(f"config {name:28} {bytes(data).hex():20} -> {r}")
        async def hrc(name, data):
            await c.write_gatt_char(HR_CONTROL, bytes(data), response=True)
            print(f"hrctl  {name:28} {bytes(data).hex():20} -> write ok")
        # settings that make the watch collect more data (Gadgetbridge HuamiSupport bytes)
        await hrc("hr interval 1 min", [0x14, 0x01])
        await hrc("hr sleep support on", [0x15, 0x00, 0x01])
        await conf("hr activity monitoring on", [0x06, 0x22, 0x00, 0x01])
        await conf("stress monitoring on", [0xfe, 0x06, 0x00, 0x01])
        await conf("time format 24h", [0x06, 0x02, 0x00, 0x01])
        await conf("request gps version", [0x0e])
        # time sync
        now = dt.datetime.now().astimezone(); tz = int(now.utcoffset().total_seconds() // 900)
        tb = now.year.to_bytes(2, "little") + bytes([now.month, now.day, now.hour, now.minute, now.second, now.isoweekday(), 0, 0, tz])
        await c.write_gatt_char(TIME, tb, response=True); print("time set", tb.hex())
        t = await c.read_gatt_char(TIME); print("watch time after", bytes(t).hex())
        # notification on chunked 0x0020, type 0, CustomHuami(0xfa) icon APP_11
        msg = "Hello from your PC".encode(); suffix = b"\0Dashboard\0"
        payload = bytes([0xfa, 0x01, 11]) + msg + suffix
        chunk = max(20, c.mtu_size - 3) - 3; cnt = 0
        for i in range(0, len(payload), chunk):
            part = payload[i:i + chunk]; last = i + chunk >= len(payload)
            flags = (0x80 | (0x40 if cnt == 0 else 0)) if last else (0x40 if cnt else 0)
            await c.write_gatt_char(CHUNKED, bytes([0, flags, cnt]) + part, response=False); cnt += 1
        print("notification sent in", cnt, "chunks")
        # temperature buffer twice, 70 s apart
        since = now - dt.timedelta(days=1)
        a = await fetch(c, 0x2e, since); ta = time.time()
        await asyncio.sleep(70)
        b = await fetch(c, 0x2e, since); tb2 = time.time()
        A, B = bytes.fromhex(a["data"]), bytes.fromhex(b["data"])
        print("temp lens", len(A), len(B), "replies", a["reply"], b["reply"])
        for k in range(0, 6):
            if A[8 * k:] == B[:len(B) - 8 * k] if k else A == B:
                print("buffer shifted by", k, "samples in", round(tb2 - ta), "s"); break
        else:
            print("no clean shift; tails", A[-24:].hex(), B[-24:].hex())
        await asyncio.sleep(3)
        print("notifications:", log[:20])
        json.dump({"tempA": a, "tempB": b}, open("discovery/temp_twice.json", "w"))

asyncio.run(main())
