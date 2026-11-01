# Heartline data

Framework SQLite storage in `dev.heartline.data`, with independent models in
`dev.heartline.data.model`. No dependency on another Heartline module.

`HeartlineDatabase.get(context)` is an application-context singleton backed by
`heartline.db`. It enables WAL and creates schema version 1. Future upgrades must
add explicit version-to-version migrations; unsupported upgrades/downgrades fail
without dropping history. All SQL stays inside this module. Writes are atomic,
serialized, and run on `Dispatchers.IO`. Each table has its own version StateFlow;
observers emit an initial query and requery after committed writes/imports. They
may conflate intermediate versions and always deliver the current query result.

Times are Unix seconds, including fractional seconds where the Python schema uses
REAL. All bounded queries/export use inclusive endpoints. JSON strings and raw
BLOBs survive import unchanged, including unknown fields. See [API.md](API.md)
for signatures and models.

Created files by role:

| Role | Files |
| --- | --- |
| Build/manifest | `build.gradle.kts`, `src/main/AndroidManifest.xml` |
| SQLite | `HeartlineDatabase.kt`, `Schema.kt`, `Sql.kt` |
| Repositories | `HeartRateRepository.kt`, `ActivityRepository.kt`, `HealthRepository.kt`, `EventRepository.kt`, `MetaRepository.kt`, `RecordingRepository.kt`, `CheckRepository.kt`, `InsightRepository.kt` |
| Settings/security | `AppSettings.kt`, `KeyStore.kt`, `Hex.kt` |
| Import/export/math | `PcImporter.kt`, `Exporter.kt`, `Csv.kt`, `Summaries.kt` |
| Models | `model/Models.kt`, `model/SettingsModels.kt` |
| JVM tests | `HexTest.kt`, `CsvTest.kt`, `SummariesTest.kt` |
| Phone tests | `DatabaseTest.kt`, `PcImporterTest.kt`, `SettingsAndKeyStoreTest.kt` |
| Fixtures/tooling | `watch-trimmed.db`, `python-oracle.json`, `tools/make_import_fixture.py` |
| Documentation | `README.md`, `API.md` |

Main Kotlin files are under `src/main/java/dev/heartline/data/`; tests use the
matching package beneath `src/test/` and `src/androidTest/`. The root `.gitignore`
also narrows `data/` to `/data/`, keeping the PC data directory ignored while
allowing this Android module and its package directories to appear in Git.

The repository queries and summaries follow `server/store.py` and
`server/insights.py:InsightsDB`. Activity state classification is a local port of
`server/protocol.py:activity_states`, so summaries also handle repeat markers,
masked kinds, invalid HR and the REM threshold. Daily aggregates accept an
explicit `ZoneId`; their default uses the phone's current timezone, matching
Python's use of local dates.

## Import and export

`PcImporter(db).import(file)` opens the source SQLite file read-only and merges all
ten recognized tables in one target transaction. Absent tables count as zero;
malformed recognized tables fail and roll back the entire merge. The source file
is never trimmed or changed. Use a consistent copy of the PC database (including
committed WAL data, e.g. Python's SQLite backup API) when transferring it to the
phone. Unknown future tables are ignored; they are not reported as imported.

`ImportReport.counts` reports newly inserted rows per table, and `updatedCounts`
reports changed rows with existing primary keys. An identical second import
reports zero in both maps. Append-only HR samples, events and raw transfers use
value multiplicities: identical duplicate rows from Python are preserved, while
repeat imports add none. Existing extra phone rows are retained. Imported values
win primary-key conflicts, except a NULL workout track preserves the phone's
downloaded track, matching Python. Importing an older PC file can therefore
replace a newer phone value at the same key; no source has modification timestamps
to resolve that conflict automatically.

PC `meta.profile` and `meta.watch_settings` are preserved as metadata. Import does
not change `AppSettings`, the paired watch, or auth keys. Recording/cache rows
describe files; raw recording/audio files need separate transfer.

`Exporter.exportCsvZip` emits all ten CSV files, including headers for empty
tables, in one database snapshot on IO. It includes metadata unconditionally and
recordings/cache rows with unknown start times. Files use UTF-8, RFC 4180 quoting,
CRLF, blank NULL cells and lowercase hex for BLOBs. Empty strings and SQL NULL
share the conventional blank CSV representation. The caller's output stream
stays open. This exports database tables; it does not bundle recording files or
SharedPreferences/Keystore secrets.

## Settings and key safety

`AppSettings` publishes read-only StateFlows and persists complete grouped values
in private SharedPreferences. Quiet-hour endpoints are minutes after midnight,
with both NULL meaning disabled. Overnight intervals are supported as stored
values. Zero auto-sync minutes disables periodic sync. Default raw sensors are
ACCEL + PPG. Close short-lived settings instances to unregister their preference
listener. Last-written watch settings are for successful BLE writes only; the
watch has no readback API.

`KeyStore` validates 16-byte keys and provides strict 32-character ASCII hex
parsing. Only a random 12-byte IV and AES-GCM authenticated ciphertext enter
private preferences. A non-exportable Android Keystore AES-256 key uses alias
`heartline_auth`. Save/clear commit synchronously; missing, corrupt, restored
without a wrapping key, or invalidated ciphertext loads as NULL. The wrapping key
is generated only on save and deleted on clear. The auth key is never included
in SQLite, CSV, fixture files, or logs.

## Validation

From `android/` with Java 17+ (the Android Studio JBR is suitable):

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
./gradlew --no-daemon :core:data:testDebugUnitTest
ANDROID_SERIAL=3a3c6381 ./gradlew --no-daemon :core:data:connectedDebugAndroidTest
```

There are 16 JVM tests (hex, CSV, summary/state math and Python rounding) and 20
phone tests (SQLite/WAL/indices, queries/upserts, flows, rollback, settings,
Keystore/tamper detection, PC merge/idempotency and CSV ZIP export).

Execution on 2026-11-01: all 16 JVM tests passed (zero failures/skips), and the
instrumented APK compiled. Lint passed with zero errors and one dependency-update
notice for the existing coroutines 1.10.2 version. The connected-test task reached
phone `3a3c6381` (Android 13), but APK installation failed with
`INSTALL_FAILED_USER_RESTRICTED: Install canceled by user`. Consequently **zero
of the 20 phone tests ran**. Unlock the phone and approve USB installation (or
enable Install via USB in Developer options), then rerun the connected task.
No requested storage feature was omitted; phone runtime validation is pending.

The 1,421,312-byte real `src/androidTest/assets/watch-trimmed.db` has 4,310 activity
minutes from the most recent three days, every health type, every workout, all
35 raw transfers and all 10 insight-cache entries. Temperature retains its oldest
sample plus its newest three days. The full original 40-day temperature transfer
also remains in `raw_fetches`. Production import keeps every row of every known
table; only this test fixture is trimmed. `python-oracle.json` records reference
query results, per-table counts, daily means/medians, raw-blob hashes and the
fixture hash. The phone tests compare every stored cell to the source as well as
the Python summaries and daily aggregates.

Regenerate explicitly, using the reference Python environment:

```powershell
& '<reference>/.venv/Scripts/python.exe' tools/make_import_fixture.py '<reference>'
```

The generator refuses to overwrite an existing fixture, opens the original with
`mode=ro`, and disables Python bytecode writes into the reference project.
It does not read `watch.json` or extract the auth key.

In a restricted Windows shell, set `GRADLE_USER_HOME` to the writable shared cache
and `ANDROID_USER_HOME` to a writable directory. If necessary, run Kotlin in the
Gradle process with `'-Pkotlin.compiler.execution.strategy=in-process'`. The
optional `'-PheartlineTestKeystore=<existing-debug-key.jks>'` selects an existing
Android debug key for this module's test APK; it avoids Android's default debug
key creation lock in environments that block that lock operation. It changes no
app signing configuration. Inspect the connected-test output/results: this
Gradle version can print BUILD SUCCESSFUL even when Android rejects APK install.
