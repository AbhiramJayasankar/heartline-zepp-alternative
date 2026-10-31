"""Try every Huami fetch data type for the last N days and save the raw bytes. Never sends the 0x03 ack."""
import asyncio, datetime as dt, json, pathlib, sys
from bleak import BleakClient, BleakScanner
from live_hr import CFG, AUTH, auth

CTRL = "00000004-0000-3512-2118-0009af100700"
DATA = "00000005-0000-3512-2118-0009af100700"
TYPES = {0x02: "manual_hr", 0x05: "sports_summaries", 0x06: "sports_details", 0x0d: "pai", 0x12: "stress_manual",
         0x13: "stress_auto", 0x25: "spo2", 0x26: "spo2_sleep", 0x2c: "statistics", 0x2e: "temperature",
         0x38: "sleep_respiratory_rate", 0x3a: "resting_hr", 0x3d: "max_hr", 0x48: "sleep_session", 0x49: "hrv"}
OUT = pathlib.Path("discovery")

def tbytes(t):
    tz_q = int(t.utcoffset().total_seconds() // 900)
    return t.year.to_bytes(2, "little") + bytes([t.month, t.day, t.hour, t.minute, 0, tz_q])

async def fetch(c, kind, since):
    ctrl, buf, counters = asyncio.Queue(), bytearray(), []
    def on_data(_, d):
        d = bytes(d); counters.append(d[0]); buf.extend(d[1:])
    await c.start_notify(CTRL, lambda _, d: ctrl.put_nowait(bytes(d)))
    await c.start_notify(DATA, on_data)
    try:
        await c.write_gatt_char(CTRL, bytes([0x01, kind]) + tbytes(since), response=False)
        try:
            r = await asyncio.wait_for(ctrl.get(), 8)
        except asyncio.TimeoutError:
            return {"reply": None}
        res = {"reply": r.hex()}
        if r[:3] != b"\x10\x01\x01":
            return res
        res["count"] = int.from_bytes(r[3:7], "little")
        if not res["count"]:
            return res
        await c.write_gatt_char(CTRL, bytes([0x02]), response=False)
        try:
            done = await asyncio.wait_for(ctrl.get(), 60)
            res["done"] = done.hex()
        except asyncio.TimeoutError:
            res["done"] = None
        res["len"] = len(buf); res["packets"] = len(counters)
        res["data"] = bytes(buf).hex()
        return res
    finally:
        await c.stop_notify(DATA); await c.stop_notify(CTRL)

async def main(days, only):
    dev = await BleakScanner.find_device_by_address(CFG["mac"], timeout=15)
    q = asyncio.Queue()
    async with BleakClient(dev, timeout=45) as c:
        await c.start_notify(AUTH, lambda _, d: q.put_nowait(bytes(d)))
        await auth(c, q)
        since = dt.datetime.now().astimezone() - dt.timedelta(days=days)
        results = {}
        for code, name in TYPES.items():
            if only and name not in only:
                continue
            r = await fetch(c, code, since)
            results[name] = {"code": code, **r}
            print(f"{code:#04x} {name:24} reply={r.get('reply')} count={r.get('count')} len={r.get('len')} done={r.get('done')}")
            await asyncio.sleep(0.5)
        OUT.mkdir(exist_ok=True)
        (OUT / f"fetch_{days}d.json").write_text(json.dumps(results, indent=1))

if __name__ == "__main__":
    asyncio.run(main(float(sys.argv[1]), sys.argv[2:]))
