"""Connect to the watch, authenticate with the auth key, read basic info."""
import asyncio, json, pathlib
from bleak import BleakClient, BleakScanner
from Crypto.Cipher import AES

CFG = json.loads((pathlib.Path(__file__).parent.parent / "watch.json").read_text())
AUTH = "00000009-0000-3512-2118-0009af100700"
BATTERY = "00000006-0000-3512-2118-0009af100700"

async def main():
    key = bytes.fromhex(CFG["auth_key"])
    q = asyncio.Queue()
    dev = await BleakScanner.find_device_by_address(CFG["mac"], timeout=15)
    print("found:", dev)
    async with BleakClient(dev, timeout=45) as c:
        print("connected; services:")
        for s in c.services:
            print(" ", s.uuid, s.description)
        await c.start_notify(AUTH, lambda _, d: q.put_nowait(bytes(d)))
        await c.write_gatt_char(AUTH, bytes([0x82, 0x00, 0x02, 0x01, 0x00]), response=False)
        r = await asyncio.wait_for(q.get(), 10)
        print("random reply:", r[:3].hex())
        if r[:3] != b"\x10\x82\x01":
            return print("unexpected reply", r.hex())
        enc = AES.new(key, AES.MODE_ECB).encrypt(r[3:19])
        await c.write_gatt_char(AUTH, bytes([0x83, 0x00]) + enc, response=False)
        r = await asyncio.wait_for(q.get(), 10)
        print("auth reply:", r.hex(), "-> AUTH OK" if r[:3] == b"\x10\x83\x01" else "-> AUTH FAILED")
        try:
            b = await c.read_gatt_char(BATTERY)
            print("battery raw:", b.hex(), "level %:", b[1] if len(b) > 1 else None)
        except Exception as e:
            print("battery read failed:", e)

asyncio.run(main())
