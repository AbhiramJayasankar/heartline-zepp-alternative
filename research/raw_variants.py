"""Try raw-sensor start-command variants; report the watch's reply and any data."""
import asyncio
from bleak import BleakClient, BleakScanner
from live_hr import CFG, AUTH, HR_CONTROL, HR_MEASURE, auth
from raw_probe import CTRL, DATA

VARIANTS = [[0x01, 0x01], [0x01, 0x01, 0x19, 0x00], [0x01, 0x00, 0x19], [0x01, 0x02, 0x19],
            [0x01, 0x04, 0x19], [0x01, 0x01, 0x32], [0x01, 0x01, 0x0a], [0x01], [0x02]]

async def main():
    dev = await BleakScanner.find_device_by_address(CFG["mac"], timeout=15)
    q, cq = asyncio.Queue(), asyncio.Queue()
    async with BleakClient(dev, timeout=45) as c:
        await c.start_notify(AUTH, lambda _, d: q.put_nowait(bytes(d)))
        await auth(c, q)
        got = []
        await c.start_notify(DATA, lambda _, d: got.append(bytes(d)))
        await c.start_notify(CTRL, lambda _, d: cq.put_nowait(bytes(d)))
        await c.start_notify(HR_MEASURE, lambda _, d: None)
        await c.write_gatt_char(HR_CONTROL, bytes([0x15, 0x01, 0x01]), response=True)
        async def send(b):
            await c.write_gatt_char(CTRL, bytes(b), response=False)
            try: return (await asyncio.wait_for(cq.get(), 3)).hex()
            except asyncio.TimeoutError: return "no reply"
        for v in VARIANTS:
            got.clear()
            r1 = await send(v)
            r2 = await send([0x02]) if v != [0x02] else "-"
            await asyncio.sleep(3)
            r3 = await send([0x03])
            print(f"{bytes(v).hex():10} -> {r1:10} start {r2:8} stop {r3:8} data packets {len(got)}"
                  + (f" e.g. {got[0].hex()}" if got else ""))
        await c.write_gatt_char(HR_CONTROL, bytes([0x15, 0x01, 0x00]), response=True)

asyncio.run(main())
