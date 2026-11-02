package dev.heartline.app

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import java.time.Instant

internal class CalendarProvider(private val context: Context) {
    fun permitted() = context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun calendars(): List<CalendarUi> {
        val columns = arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, CalendarContract.Calendars.ACCOUNT_NAME)
        return checkNotNull(context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, columns,
            "${CalendarContract.Calendars.VISIBLE} = 1", null, "${CalendarContract.Calendars._ID} ASC")) { "Calendar provider unavailable" }.use { c ->
            buildList { while (c.moveToNext()) add(CalendarUi(c.getLong(0), c.getString(1) ?: "Calendar", c.getString(2))) }
        }
    }

    fun instances(selected: Set<Long>, now: Instant): List<CalendarInstance> {
        if (selected.isEmpty()) return emptyList()
        val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        // Allow for the UTC date used by all-day events in every local timezone; the policy applies the exact window.
        ContentUris.appendId(builder, now.minusSeconds(86400).toEpochMilli())
        ContentUris.appendId(builder, now.plusSeconds(15 * 86400L).toEpochMilli())
        val columns = arrayOf(CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.CALENDAR_ID, CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN, CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY)
        val ids = selected.sorted()
        val selection = "${CalendarContract.Instances.CALENDAR_ID} IN (${ids.joinToString(",") { "?" }}) AND " +
            "(${CalendarContract.Events.STATUS} IS NULL OR ${CalendarContract.Events.STATUS} != ${CalendarContract.Events.STATUS_CANCELED})"
        return checkNotNull(context.contentResolver.query(builder.build(), columns, selection, ids.map(Long::toString).toTypedArray(),
            "${CalendarContract.Instances.BEGIN} ASC, ${CalendarContract.Instances.EVENT_ID} ASC")) { "Calendar instances unavailable" }.use { c ->
            buildList { while (c.moveToNext()) add(CalendarInstance(c.getLong(0), c.getLong(1), c.getString(2) ?: "Event", c.getLong(3), c.getLong(4), c.getInt(5) != 0)) }
        }
    }
}
