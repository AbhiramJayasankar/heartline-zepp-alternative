"""Minimal NMEA 0183 parser for the watch's GPS stream (raw sensor packet type 0x03)."""
from datetime import datetime, timezone

SYSTEMS = {"GP": "GPS", "GL": "GLONASS", "GA": "Galileo", "GB": "BeiDou", "BD": "BeiDou", "GN": "GNSS", "GQ": "QZSS"}


def _float(s: str) -> float | None:
    try:
        return float(s)
    except ValueError:
        return None


def _deg(value: str, hemi: str) -> float | None:
    """ddmm.mmmm / dddmm.mmmm plus N/S/E/W to signed decimal degrees."""
    v = _float(value)
    if v is None:
        return None
    deg = int(v // 100)
    out = deg + (v - deg * 100) / 60
    return round(-out if hemi in ("S", "W") else out, 7)


def _checksum_ok(line: str) -> bool:
    if "*" not in line:
        return True
    body, _, given = line.partition("*")
    calc = 0
    for ch in body:
        calc ^= ord(ch)
    try:
        return calc == int(given[:2], 16)
    except ValueError:
        return False


class Nmea:
    """Feed it text chunks; `state` holds the latest fix and the satellites in view."""

    def __init__(self) -> None:
        self._buf = ""
        self._sats: dict[str, dict[str, dict]] = {}
        self.state: dict = {
            "fix": False, "quality": 0, "lat": None, "lon": None, "alt_m": None, "speed_mps": None,
            "course": None, "hdop": None, "sats_used": 0, "utc": None, "satellites": [],
        }

    def feed(self, text: str) -> bool:
        """Returns True when a GGA sentence completed an update (about once a second)."""
        self._buf += text
        *lines, self._buf = self._buf.replace("\r", "").split("\n")
        updated = False
        for raw in lines:
            line = raw.strip().lstrip("$")
            if not line or not _checksum_ok(line):
                continue
            fields = line.split("*")[0].split(",")
            kind = fields[0][2:]
            talker = fields[0][:2]
            try:
                if kind == "GGA":
                    self._gga(fields)
                    updated = True
                elif kind == "RMC":
                    self._rmc(fields)
                elif kind == "GSV":
                    self._gsv(talker, fields)
                elif kind == "VTG" and self.state["speed_mps"] is None:
                    kmh = _float(fields[7]) if len(fields) > 7 else None
                    self.state["speed_mps"] = round(kmh / 3.6, 2) if kmh is not None else None
            except (IndexError, ValueError):
                continue
        if len(self._buf) > 4096:
            self._buf = ""
        return updated

    def _gga(self, f: list[str]) -> None:
        quality = int(f[6] or 0)
        self.state.update(
            quality=quality, fix=quality > 0, sats_used=int(f[7] or 0), hdop=_float(f[8]), alt_m=_float(f[9]),
        )
        if quality > 0:
            self.state.update(lat=_deg(f[2], f[3]), lon=_deg(f[4], f[5]))
        self.state["satellites"] = [s for sys in self._sats.values() for s in sys.values()]

    def _rmc(self, f: list[str]) -> None:
        valid = f[2] == "A"
        knots = _float(f[7])
        self.state.update(
            speed_mps=round(knots * 0.514444, 2) if valid and knots is not None else None,
            course=_float(f[8]) if valid else None,
        )
        if f[1] and len(f[9]) == 6:
            t, d = f[1], f[9]
            try:
                self.state["utc"] = datetime(2000 + int(d[4:6]), int(d[2:4]), int(d[0:2]), int(t[0:2]), int(t[2:4]),
                                             int(float(t[4:])), tzinfo=timezone.utc).isoformat()
            except ValueError:
                pass

    def _gsv(self, talker: str, f: list[str]) -> None:
        system = SYSTEMS.get(talker, talker)
        if f[2] == "1":
            self._sats[system] = {}
        sats = self._sats.setdefault(system, {})
        for i in range(4, len(f) - 3, 4):
            prn = f[i]
            if not prn:
                continue
            snr = _float(f[i + 3])
            sats[prn] = {"id": prn, "system": system, "elev": _float(f[i + 1]), "az": _float(f[i + 2]),
                         "snr": snr}
