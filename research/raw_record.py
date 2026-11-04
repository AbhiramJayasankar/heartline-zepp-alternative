"""Record the raw stream (with HR on) to CSV files for analysis."""
import asyncio, csv, json, sys, time
from bleak import BleakClient, BleakScanner
from live_hr import CFG, AUTH, HR_CONTROL, HR_MEASURE, auth
from raw_probe import CTRL, DATA

async def main(seconds, out):
    dev = await BleakScanner.find_device_by_address(CFG["mac"], timeout=15)
    q = asyncio.Queue(); packets = []; hrs = []
    async with BleakClient(dev, timeout=45) as c:
        await c.start_notify(AUTH, lambda _, d: q.put_nowait(bytes(d)))
        await auth(c, q)
        await c.start_notify(DATA, lambda _, d: packets.append((time.time(), bytes(d).hex())))
        await c.start_notify(CTRL, lambda _, d: None)
        await c.start_notify(HR_MEASURE, lambda _, d: hrs.append((time.time(), d[1])))
        await c.write_gatt_char(HR_CONTROL, bytes([0x15, 0x01, 0x01]), response=True)
        for cmd in ([0x01, 0x03, 0x19], [0x01, 0x03, 0x00, 0x00, 0x00, 0x19], [0x02]):
            await c.write_gatt_char(CTRL, bytes(cmd), response=False)
            await asyncio.sleep(0.5)
        t0 = time.time()
        while time.time() - t0 < seconds:
            await asyncio.sleep(5)
            await c.write_gatt_char(HR_CONTROL, bytes([0x16]), response=True)
        await c.write_gatt_char(CTRL, bytes([0x03]), response=False)
        await c.write_gatt_char(HR_CONTROL, bytes([0x15, 0x01, 0x00]), response=True)
    json.dump({"packets": packets, "hr": hrs}, open(out, "w"))
    print(len(packets), "packets,", len(hrs), "hr readings:", [b for _, b in hrs])

asyncio.run(main(int(sys.argv[1]), sys.argv[2]))
