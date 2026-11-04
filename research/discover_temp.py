"""Fetch the temperature buffer twice, 70 s apart, to learn its sampling and freshness."""
import asyncio, datetime as dt, json, time
from bleak import BleakClient, BleakScanner
from live_hr import CFG, AUTH, auth
from discover_fetch import fetch

async def main():
    dev = await BleakScanner.find_device_by_address(CFG["mac"], timeout=15)
    q = asyncio.Queue()
    async with BleakClient(dev, timeout=45, disconnected_callback=lambda _: print("DISCONNECTED", time.strftime("%X"))) as c:
        await c.start_notify(AUTH, lambda _, d: q.put_nowait(bytes(d)))
        await auth(c, q)
        since = dt.datetime.now().astimezone() - dt.timedelta(days=1)
        a = await fetch(c, 0x2e, since); ta = time.time(); print("A", a["reply"], a.get("len"))
        await asyncio.sleep(70)
        b = await fetch(c, 0x2e, since); tb = time.time(); print("B", b["reply"], b.get("len"))
        A, B = bytes.fromhex(a["data"]), bytes.fromhex(b["data"])
        for k in range(0, 10):
            if (A[8 * k:] == B[:len(B) - 8 * k]) if k else A == B:
                print("buffer shifted by", k, "samples in", round(tb - ta), "s"); break
        else:
            print("no clean shift; tails", A[-24:].hex(), B[-24:].hex())
        json.dump({"A": a, "ta": ta, "B": b, "tb": tb}, open("discovery/temp_twice.json", "w"))

asyncio.run(main())
