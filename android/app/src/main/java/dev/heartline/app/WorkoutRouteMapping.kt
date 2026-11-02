package dev.heartline.app

import dev.heartline.data.model.RawFetch
import dev.heartline.protocol.parseWorkoutRoute
import dev.heartline.protocol.parseWorkoutSummary

internal fun storedWorkoutRoutes(fetches: List<RawFetch>): Map<Long, List<RoutePoint>> {
    val summaries = fetches.filter { it.type == "workouts" && it.data != null }.sortedBy { it.ts }
        .mapNotNull { fetch -> parseWorkoutSummary(fetch.data!!)?.let { it.start to fetch.data } }.toMap()
    return fetches.filter { it.type == "workout_details" && it.start != null && it.data != null }.sortedBy { it.ts }
        .associate { fetch ->
            val start = fetch.start!!.toLong()
            start to summaries[start]?.let { summary -> parseWorkoutRoute(fetch.data!!, summary).map { RoutePoint(it.t, it.lat, it.lon, it.altM) } }.orEmpty()
        }
}
