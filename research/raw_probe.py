"""Probe the Huami raw-sensor channel (0x0001 control, 0x0002 data)."""
import asyncio, collections, sys, time
from bleak import BleakClient, BleakScanner
from live_hr import CFG, AUTH, HR_CONTROL, HR_MEASURE, auth

CTRL = "00000001-0000-3512-2118-0009af100700"
DATA = "00000002-0000-3512-2118-0009af100700"

async def main(mode, seconds):
    dev = await BleakScanner.find_device_by_address(CFG["mac"], timeout=15)
    q = asyncio.Queue()
    async with BleakClient(dev, timeout=45) as c:
        await c.start_notify(AUTH, lambda _, d: q.put_nowait(bytes(d)))
        await auth(c, q)
        stats = collections.Counter(); samples = collections.defaultdict(list)
        def on_data(_, d):
            d = bytes(d); key = (d[0], len(d)); stats[key] += 1
            if len(samples[key]) < 3: samples[key].append(d.hex())
        await c.start_notify(DATA, on_data)
        await c.start_notify(CTRL, lambda _, d: print("ctrl reply:", bytes(d).hex()))
        await c.start_notify(HR_MEASURE, lambda _, d: None)
        await c.write_gatt_char(CTRL, bytes([0x01, mode, 0x19]), response=False)
        await c.write_gatt_char(HR_CONTROL, bytes([0x15, 0x01, 0x01]), response=True)
        await c.write_gatt_char(CTRL, bytes([0x02]), response=False)
        t0 = time.time()
        while time.time() - t0 < seconds:
            await asyncio.sleep(5)
            await c.write_gatt_char(HR_CONTROL, bytes([0x16]), response=True)
        await c.write_gatt_char(CTRL, bytes([0x03]), response=False)
        await c.write_gatt_char(HR_CONTROL, bytes([0x15, 0x01, 0x00]), response=True)
        print(f"mode {mode:#04x}: {sum(stats.values())} packets in {seconds}s")
        for k, n in stats.items():
            print(f"  type {k[0]:#04x} len {k[1]}: {n} packets ({n/seconds:.1f}/s) e.g. {samples[k][:2]}")

if __name__ == "__main__":
    asyncio.run(main(int(sys.argv[1], 0), int(sys.argv[2])))
