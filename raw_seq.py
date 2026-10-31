"""Gadgetbridge's 3-step raw sensor start: 01 03 19, 01 03 00 00 00 19, 02."""
import asyncio, collections, struct, sys, time
from bleak import BleakClient, BleakScanner
from live_hr import CFG, AUTH, HR_CONTROL, HR_MEASURE, auth
from raw_probe import CTRL, DATA

async def main(seconds, with_hr):
    dev = await BleakScanner.find_device_by_address(CFG["mac"], timeout=15)
    q = asyncio.Queue()
    async with BleakClient(dev, timeout=45) as c:
        await c.start_notify(AUTH, lambda _, d: q.put_nowait(bytes(d)))
        await auth(c, q)
        stats = collections.Counter(); first = {}; accel = []
        def on_data(_, d):
            d = bytes(d); stats[(d[0], len(d))] += 1; first.setdefault((d[0], len(d)), d.hex())
            if d[0] == 0 and (len(d) - 2) % 6 == 0:
                accel.extend(struct.unpack_from("<3h", d, i) for i in range(2, len(d), 6))
        await c.start_notify(DATA, on_data)
        await c.start_notify(CTRL, lambda _, d: print("  ctrl reply:", bytes(d).hex()))
        if with_hr:
            await c.start_notify(HR_MEASURE, lambda _, d: print("  hr", d[1]))
            await c.write_gatt_char(HR_CONTROL, bytes([0x15, 0x01, 0x01]), response=True)
        for cmd in ([0x01, 0x03, 0x19], [0x01, 0x03, 0x00, 0x00, 0x00, 0x19], [0x02]):
            print("send", bytes(cmd).hex())
            await c.write_gatt_char(CTRL, bytes(cmd), response=False)
            await asyncio.sleep(0.5)
        t0 = time.time()
        while time.time() - t0 < seconds:
            await asyncio.sleep(5)
            if with_hr:
                await c.write_gatt_char(HR_CONTROL, bytes([0x16]), response=True)
        await c.write_gatt_char(CTRL, bytes([0x03]), response=False)
        await asyncio.sleep(0.5)
        if with_hr:
            await c.write_gatt_char(HR_CONTROL, bytes([0x15, 0x01, 0x00]), response=True)
        print(f"{sum(stats.values())} packets in {seconds}s")
        for k, n in stats.items():
            print(f"  type {k[0]:#04x} len {k[1]}: {n} ({n/seconds:.1f}/s) first {first[k][:80]}")
        if accel:
            print(f"  accel samples: {len(accel)} ({len(accel)/seconds:.1f} Hz), first 5: {accel[:5]}")

asyncio.run(main(int(sys.argv[1]), len(sys.argv) > 2))
