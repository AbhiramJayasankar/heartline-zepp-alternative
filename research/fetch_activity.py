"""Fetch per-minute activity history (type 0x01) without acking, so nothing is deleted on the watch."""
import asyncio, datetime as dt, sys
from bleak import BleakClient, BleakScanner
from live_hr import CFG, AUTH, auth

CTRL = "00000004-0000-3512-2118-0009af100700"
DATA = "00000005-0000-3512-2118-0009af100700"
TZ_Q = 22  # IST, quarter hours

def time_bytes(t: dt.datetime) -> bytes:
    return t.year.to_bytes(2, "little") + bytes([t.month, t.day, t.hour, t.minute, 0, TZ_Q])

def parse_ts(b: bytes) -> dt.datetime:
    return dt.datetime(int.from_bytes(b[0:2], "little"), b[2], b[3], b[4], b[5], b[6])

async def fetch(c, kind: int, since: dt.datetime):
    ctrl, buf, last = asyncio.Queue(), bytearray(), [-1]
    def on_data(_, d):
        d = bytes(d)
        if (last[0] + 1) & 0xff != d[0]:
            print("  counter gap", last[0], d[0])
        last[0] = d[0]; buf.extend(d[1:])
    await c.start_notify(CTRL, lambda _, d: ctrl.put_nowait(bytes(d)))
    await c.start_notify(DATA, on_data)
    try:
        await c.write_gatt_char(CTRL, bytes([0x01, kind]) + time_bytes(since), response=False)
        r = await asyncio.wait_for(ctrl.get(), 10)
        print("  start reply", r.hex())
        if r[:3] != b"\x10\x01\x01":
            return None, b""
        length = int.from_bytes(r[3:7], "little")
        start = parse_ts(r[7:]) if length else None
        print(f"  {length} bytes available from {start}")
        if not length:
            return start, b""
        await c.write_gatt_char(CTRL, bytes([0x02]), response=False)
        r = await asyncio.wait_for(ctrl.get(), 120)
        print("  done reply", r.hex(), "got", len(buf), "bytes")
        return start, bytes(buf)
    finally:
        await c.stop_notify(DATA); await c.stop_notify(CTRL)

async def main(hours):
    dev = await BleakScanner.find_device_by_address(CFG["mac"], timeout=15)
    q = asyncio.Queue()
    async with BleakClient(dev, timeout=45) as c:
        await c.start_notify(AUTH, lambda _, d: q.put_nowait(bytes(d)))
        await auth(c, q)
        since = dt.datetime.now() - dt.timedelta(hours=hours)
        start, data = await fetch(c, 0x01, since)
        if not data:
            return
        n = len(data) // 8
        print(f"{n} minutes, {len(data) % 8} leftover bytes")
        steps = sum(data[i + 2] for i in range(0, n * 8, 8))
        hrs = [data[i + 3] for i in range(0, n * 8, 8) if 0 < data[i + 3] < 255]
        print("total steps", steps, "| HR minutes", len(hrs), "min/avg/max", min(hrs, default=0), sum(hrs) // max(len(hrs), 1), max(hrs, default=0))
        for i in list(range(0, min(n, 5))) + list(range(max(n - 5, 0), n)):
            s = data[i * 8:i * 8 + 8]
            print(" ", start + dt.timedelta(minutes=i), "kind", s[0], "int", s[1], "steps", s[2], "hr", s[3], "rest", list(s[4:]))
        open("data/activity_raw.bin", "wb").write(data)
        open("data/activity_start.txt", "w").write(start.isoformat())

asyncio.run(main(float(sys.argv[1])))
