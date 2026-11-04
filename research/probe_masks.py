"""Try raw-sensor start masks other than Gadgetbridge's 0x03 and log which packet types appear."""
import asyncio, collections, json, sys, time
from bleak import BleakClient, BleakScanner
from live_hr import CFG, AUTH, auth
from raw_probe import CTRL, DATA

MASKS = [0x01, 0x02, 0x04, 0x08, 0x10, 0x20, 0x40, 0x80, 0x03, 0x07, 0x0f, 0x1f, 0x3f, 0x7f, 0xff]

async def main(out):
    dev = await BleakScanner.find_device_by_address(CFG["mac"], timeout=15)
    q, cq = asyncio.Queue(), asyncio.Queue()
    results = []
    async with BleakClient(dev, timeout=45) as c:
        await c.start_notify(AUTH, lambda _, d: q.put_nowait(bytes(d)))
        await auth(c, q)
        pk = []
        await c.start_notify(DATA, lambda _, d: pk.append(bytes(d)))
        await c.start_notify(CTRL, lambda _, d: cq.put_nowait(bytes(d)))
        async def send(b):
            await c.write_gatt_char(CTRL, bytes(b), response=False)
            try: return (await asyncio.wait_for(cq.get(), 1.5)).hex()
            except asyncio.TimeoutError: return ""
        for m in MASKS:
            while not cq.empty(): cq.get_nowait()
            pk.clear()
            r1 = await send([0x01, m, 0x19])
            r2 = await send([0x01, m, 0x00, 0x00, 0x00, 0x19])
            r3 = await send([0x02])
            await asyncio.sleep(4)
            r4 = await send([0x03])
            await asyncio.sleep(0.5)
            types = collections.Counter((p[0], len(p)) for p in pk)
            first = {}
            for p in pk: first.setdefault(f"{p[0]:#04x}/{len(p)}", p[:24].hex())
            row = {"mask": f"{m:#04x}", "replies": [r1, r2, r3, r4], "packets": len(pk),
                   "types": {f"{t:#04x}/{l}": n for (t, l), n in types.items()}, "first": first}
            results.append(row)
            print(row["mask"], row["replies"], row["types"])
    json.dump(results, open(out, "w"), indent=1)

asyncio.run(main(sys.argv[1]))
