import asyncio, sys
from bleak import BleakScanner

async def main():
    target = sys.argv[1].upper()
    found = await BleakScanner.discover(timeout=12, return_adv=True)
    for dev, adv in found.values():
        if dev.address.upper() == target or (adv.local_name or "").lower().startswith(("amazfit", "gtr")):
            print(dev.address, adv.local_name, "rssi", adv.rssi, "services", adv.service_uuids)
    print("total devices seen:", len(found))

asyncio.run(main())
