"""Authenticate, dump the GATT table, then stream live heart rate."""
import asyncio, json, pathlib, sys, time
from bleak import BleakClient, BleakScanner
from Crypto.Cipher import AES

CFG = json.loads((pathlib.Path(__file__).parent / "watch.json").read_text())
AUTH = "00000009-0000-3512-2118-0009af100700"
HR_MEASURE = "00002a37-0000-1000-8000-00805f9b34fb"
HR_CONTROL = "00002a39-0000-1000-8000-00805f9b34fb"

async def auth(c, q):
    await c.write_gatt_char(AUTH, bytes([0x82, 0x00, 0x02, 0x01, 0x00]), response=False)
    r = await asyncio.wait_for(q.get(), 10)
    enc = AES.new(bytes.fromhex(CFG["auth_key"]), AES.MODE_ECB).encrypt(r[3:19])
    await c.write_gatt_char(AUTH, bytes([0x83, 0x00]) + enc, response=False)
    r = await asyncio.wait_for(q.get(), 10)
    assert r[:3] == b"\x10\x83\x01", r.hex()

async def main(seconds):
    dev = await BleakScanner.find_device_by_address(CFG["mac"], timeout=15)
    q = asyncio.Queue()
    async with BleakClient(dev, timeout=45) as c:
        await c.start_notify(AUTH, lambda _, d: q.put_nowait(bytes(d)))
        await auth(c, q)
        lines = []
        for s in c.services:
            lines.append(f"{s.uuid}  {s.description}")
            for ch in s.characteristics:
                lines.append(f"    {ch.uuid}  {','.join(ch.properties)}")
        (pathlib.Path(__file__).parent / "gatt_table.txt").write_text("\n".join(lines))
        print("GATT table saved,", sum(len(s.characteristics) for s in c.services), "characteristics")

        t0 = time.time()
        def on_hr(_, d):
            flags = d[0]
            bpm = int.from_bytes(d[1:3], "little") if flags & 1 else d[1]
            print(f"{time.time()-t0:5.1f}s  HR {bpm} bpm")
        await c.start_notify(HR_MEASURE, on_hr)
        await c.write_gatt_char(HR_CONTROL, bytes([0x15, 0x02, 0x00]), response=True)   # stop manual
        await c.write_gatt_char(HR_CONTROL, bytes([0x15, 0x01, 0x01]), response=True)   # start continuous
        while time.time() - t0 < seconds:
            await asyncio.sleep(10)
            await c.write_gatt_char(HR_CONTROL, bytes([0x16]), response=True)            # keep-alive ping
        await c.write_gatt_char(HR_CONTROL, bytes([0x15, 0x01, 0x00]), response=True)   # stop

if __name__ == "__main__":
    asyncio.run(main(int(sys.argv[1]) if len(sys.argv) > 1 else 30))
