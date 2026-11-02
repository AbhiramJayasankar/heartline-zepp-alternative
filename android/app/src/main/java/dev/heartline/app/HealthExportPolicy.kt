package dev.heartline.app

import java.security.MessageDigest

internal const val HEALTH_EXPORT_DAYS = 30L * 86400
internal const val HEALTH_EXPORT_OVERLAP = 7L * 86400
internal const val HEALTH_EXPORT_BATCH = 1000

internal fun exportFrom(now: Long, watermark: Long?, full: Boolean): Long =
    if (full || watermark == null) now - HEALTH_EXPORT_DAYS
    else maxOf(now - HEALTH_EXPORT_DAYS, minOf(now, watermark) - HEALTH_EXPORT_OVERLAP)

internal fun exportFingerprint(payload: String): String = MessageDigest.getInstance("SHA-256")
    .digest(payload.toByteArray(Charsets.UTF_8)).joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }

internal data class ExportStamp(val time: Long, val fingerprint: String)
internal data class ExportItem<T>(val id: String, val time: Long, val fingerprint: String, val value: T)

/** Commit both the ledger and watermark only after every batch succeeds. Retried partial writes
 * use the same IDs and a newer version, so a process death cannot duplicate records. */
internal suspend fun <T> writeExportType(
    items: List<ExportItem<T>>, previous: Map<String, ExportStamp>, now: Long,
    insert: suspend (List<T>) -> Unit,
    commit: suspend (Map<String, ExportStamp>, Long) -> Unit,
): Int {
    val unique = items.distinctBy { it.id }
    val changed = unique.filter { previous[it.id]?.fingerprint != it.fingerprint }
    for (batch in changed.chunked(HEALTH_EXPORT_BATCH)) insert(batch.map { it.value })
    val ledger = previous.filterValues { it.time >= now - HEALTH_EXPORT_DAYS }.toMutableMap()
    for (item in unique) ledger[item.id] = ExportStamp(item.time, item.fingerprint)
    commit(ledger, now)
    return changed.size
}
