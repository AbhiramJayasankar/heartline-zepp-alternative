"""Mic availability test: phase A with the screen kept on, phase B untouched. Buzzes mark the phases."""
import asyncio, json, sys, time
from bleak import BleakClient, BleakScanner
from live_hr import CFG, AUTH, auth
from raw_probe import CTRL, DATA

ALERT = "00002a06-0000-1000-8000-00805f9b34fb"

async def buzz(c, times):
    for _ in range(times):
        await c.write_gatt_char(ALERT, bytes([0x03]), response=False)
        await asyncio.sleep(0.6)
        await c.write_gatt_char(ALERT, bytes([0x00]), response=False)
        await asyncio.sleep(0.6)

async def phase(c, name, seconds, log):
    pk = []
    await c.start_notify(DATA, lambda _, d: pk.append((time.time(), bytes(d)[0], bytes(d)[1])))
    await c.start_notify(CTRL, lambda _, d: log.append((time.time(), name, "ctrl", bytes(d).hex())))
    for cmd in ([0x01, 0x20, 0x19], [0x01, 0x20, 0, 0, 0, 0x19], [0x02]):
        await c.write_gatt_char(CTRL, bytes(cmd), response=False)
        await asyncio.sleep(0.4)
    t0 = time.time()
    await asyncio.sleep(seconds)
    await c.write_gatt_char(CTRL, bytes([0x03]), response=False)
    await asyncio.sleep(0.5)
    await c.stop_notify(DATA); await c.stop_notify(CTRL)
    mic = [t - t0 for t, typ, _ in pk if typ == 0x05]
    per_s = [sum(1 for t in mic if k <= t < k + 1) for k in range(int(seconds))]
    print(f"{name}: {len(mic)} mic packets; per second: {per_s}")
    return per_s

async def main(out, swap):
    dev = await BleakScanner.find_device_by_address(CFG["mac"], timeout=15)
    q, log = asyncio.Queue(), []
    async with BleakClient(dev, timeout=45) as c:
        await c.start_notify(AUTH, lambda _, d: q.put_nowait(bytes(d)))
        await auth(c, q)
        first, second = ("untouched", "screen kept on") if swap else ("screen kept on", "untouched")
        await buzz(c, 1)
        if first == "untouched":
            await asyncio.sleep(8)  # let the screen time out after the buzz
        a = await phase(c, f"1 {first}", 25, log)
        await buzz(c, 2)
        if second == "untouched":
            await asyncio.sleep(10)
        b = await phase(c, f"2 {second}", 20, log)
        await buzz(c, 3)
    json.dump({first: a, second: b, "ctrl": log}, open(out, "w"))

asyncio.run(main(sys.argv[1], "--swap" in sys.argv))
