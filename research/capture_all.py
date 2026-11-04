"""Capture every raw stream (mask 0x7b) for N seconds into a JSON file of (t, hex) packets."""
import asyncio, json, sys, time
from bleak import BleakClient, BleakScanner
from live_hr import CFG, AUTH, auth
from raw_probe import CTRL, DATA

async def main(mask, seconds, out):
    dev = await BleakScanner.find_device_by_address(CFG["mac"], timeout=15)
    q = asyncio.Queue(); pk = []
    async with BleakClient(dev, timeout=45) as c:
        await c.start_notify(AUTH, lambda _, d: q.put_nowait(bytes(d)))
        await auth(c, q)
        await c.start_notify(DATA, lambda _, d: pk.append((time.time(), bytes(d).hex())))
        await c.start_notify(CTRL, lambda _, d: None)
        for cmd in ([0x01, mask, 0x19], [0x01, mask, 0x00, 0x00, 0x00, 0x19], [0x02]):
            await c.write_gatt_char(CTRL, bytes(cmd), response=False)
            await asyncio.sleep(0.4)
        await asyncio.sleep(seconds)
        await c.write_gatt_char(CTRL, bytes([0x03]), response=False)
    json.dump(pk, open(out, "w"))
    print(len(pk), "packets")

asyncio.run(main(int(sys.argv[1], 0), float(sys.argv[2]), sys.argv[3]))
