"""Does re-sending the start sequence get the microphone going when it stays silent?"""
import asyncio, time
from bleak import BleakClient, BleakScanner
from live_hr import CFG, AUTH, auth
from raw_probe import CTRL, DATA

async def main(sessions=3, attempts=10):
    dev = await BleakScanner.find_device_by_address(CFG["mac"], timeout=15)
    q = asyncio.Queue()
    async with BleakClient(dev, timeout=45) as c:
        await c.start_notify(AUTH, lambda _, d: q.put_nowait(bytes(d)))
        await auth(c, q)
        got = []
        await c.start_notify(DATA, lambda _, d: got.append(time.time()) if bytes(d)[0] == 0x05 else None)
        await c.start_notify(CTRL, lambda _, d: None)
        for s in range(sessions):
            for a in range(1, attempts + 1):
                got.clear()
                for cmd in ([0x01, 0x20, 0x19], [0x01, 0x20, 0, 0, 0, 0x19], [0x02]):
                    await c.write_gatt_char(CTRL, bytes(cmd), response=False)
                    await asyncio.sleep(0.4)
                await asyncio.sleep(3)
                if got:
                    t0 = time.time()
                    await asyncio.sleep(8)  # does it keep flowing once started?
                    rate = sum(1 for t in got if t >= t0) / 8
                    print(f"session {s + 1}: audio on attempt {a}, then {rate:.1f} packets/s for 8 s")
                    break
                await c.write_gatt_char(CTRL, bytes([0x03]), response=False)
                await asyncio.sleep(1)
            else:
                print(f"session {s + 1}: no audio after {attempts} attempts")
            await c.write_gatt_char(CTRL, bytes([0x03]), response=False)
            await asyncio.sleep(2)

asyncio.run(main())
