package dev.heartline.data

/** Column order and nullability mirror server/store.py and server/insights.py:InsightsDB. */
internal object Schema {
    const val VERSION = 1
    data class Table(val name: String, val columns: List<String>, val keys: List<String>, val time: String?)
    val tables = listOf(
        Table("hr_samples", listOf("ts", "bpm"), emptyList(), "ts"),
        Table("events", listOf("ts", "level", "msg"), emptyList(), "ts"),
        Table("activity", listOf("ts", "kind", "intensity", "steps", "hr", "unknown1", "sleep", "deep", "rem"), listOf("ts"), "ts"),
        Table("meta", listOf("key", "value"), listOf("key"), null),
        Table("health", listOf("type", "ts", "value", "extra"), listOf("type", "ts"), "ts"),
        Table("workouts", listOf("start", "data", "track"), listOf("start"), "start"),
        Table("raw_fetches", listOf("ts", "type", "start", "reply", "data"), emptyList(), "ts"),
        Table("insight_sessions", listOf("file", "mtime", "start", "duration_s", "quality", "usable_s", "hr", "rmssd", "sdnn", "rhythm", "resp", "result"), listOf("file"), "start"),
        Table("recordings", listOf("file", "start", "duration_s", "sensors", "has_audio", "analysis"), listOf("file"), "start"),
        Table("checks", listOf("start", "file", "hr", "rmssd", "sdnn", "rhythm", "json"), listOf("start"), "start"),
    )
    val create = listOf(
        "CREATE TABLE hr_samples (ts REAL NOT NULL, bpm INTEGER NOT NULL)",
        "CREATE TABLE events (ts REAL NOT NULL, level TEXT NOT NULL, msg TEXT NOT NULL)",
        "CREATE TABLE activity (ts INTEGER PRIMARY KEY, kind INTEGER, intensity INTEGER, steps INTEGER, hr INTEGER, unknown1 INTEGER, sleep INTEGER, deep INTEGER, rem INTEGER)",
        "CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT)",
        "CREATE TABLE health (type TEXT NOT NULL, ts INTEGER NOT NULL, value REAL, extra TEXT, PRIMARY KEY(type, ts))",
        "CREATE TABLE workouts (start INTEGER PRIMARY KEY, data TEXT NOT NULL, track TEXT)",
        "CREATE TABLE raw_fetches (ts REAL NOT NULL, type TEXT NOT NULL, start REAL, reply TEXT, data BLOB)",
        "CREATE TABLE insight_sessions (file TEXT PRIMARY KEY, mtime REAL, start REAL, duration_s REAL, quality REAL, usable_s REAL, hr REAL, rmssd REAL, sdnn REAL, rhythm TEXT, resp REAL, result TEXT)",
        "CREATE TABLE recordings (file TEXT PRIMARY KEY, start REAL, duration_s REAL, sensors TEXT, has_audio INTEGER, analysis TEXT)",
        "CREATE TABLE checks (start REAL PRIMARY KEY, file TEXT, hr REAL, rmssd REAL, sdnn REAL, rhythm TEXT, json TEXT)",
    )
}
