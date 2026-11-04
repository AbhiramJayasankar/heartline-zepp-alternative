# Research scripts

The first exploration of the GTR 2e's Bluetooth protocol, from Windows with [bleak](https://github.com/hbldh/bleak):
authentication, the GATT table (`gatt_table.txt`), live heart rate, the activity fetch, the raw sensor stream and
its sensor bitmask, the microphone, and the health fetches. What they found is built into `server/` and the
Android app; these are kept for reference.

They read `watch.json` (MAC and auth key) from the repository root. `get-key.ps1` prints the auth key from your
Zepp account with [huami-token](https://github.com/argrento/huami-token) cloned into `huami-token/`.
