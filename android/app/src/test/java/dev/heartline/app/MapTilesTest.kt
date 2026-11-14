package dev.heartline.app

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.*

class MapTilesTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun mercatorRoundTripsIncludingFractionalZoom() {
        for (zoom in listOf(0.0, 2.0, 8.75, 15.0, 19.25)) {
            assertEquals(256 * 2.0.pow(zoom), WebMercator.worldSize(zoom), 1e-6)
            for (lat in listOf(-85.0, -60.3, -12.9716, 0.0, 12.9716, 51.5, 85.0)) {
                for (lon in listOf(-180.0, -179.9, -74.0, 0.0, 77.5946, 179.9, 180.0)) {
                    assertEquals(lon, WebMercator.lon(WebMercator.x(lon, zoom), zoom), 1e-6)
                    assertEquals(lat, WebMercator.lat(WebMercator.y(lat, zoom), zoom), 1e-6)
                }
            }
        }
    }

    @Test fun mercatorClampsPolesAndHasKnownEdges() {
        assertEquals(0.0, WebMercator.x(-180.0, 0.0), 0.0)
        assertEquals(128.0, WebMercator.y(0.0, 0.0), 1e-10)
        assertEquals(0.0, WebMercator.y(90.0, 0.0), 1e-6)
        assertEquals(256.0, WebMercator.y(-90.0, 0.0), 1e-6)
        assertEquals(WebMercator.y(WebMercator.MAX_LAT, 3.5), WebMercator.y(90.0, 3.5), 0.0)
    }

    @Test fun bengaluruTileFromTheProjectionFormula() {
        assertEquals(23446, floor(WebMercator.x(77.5946, 15.0) / 256).toInt())
        assertEquals(15193, floor(WebMercator.y(12.9716, 15.0) / 256).toInt())
    }

    private fun assertFits(points: List<Pair<Double, Double>>, width: Int, height: Int, padding: Int, cap: Double = 18.0): MapView {
        val view = fitRoute(points, width, height, padding, cap)
        fun inside(view: MapView): Boolean = points.all { (lat, lon) ->
            val (x, y) = screenOf(lat, lon, view, width, height)
            x >= padding - 0.001 && x <= width - padding + 0.001 && y >= padding - 0.001 && y <= height - padding + 0.001
        }
        assertTrue("Every route point must fit: $view", inside(view))
        if (view.zoom < cap) assertFalse("The fit must use the largest possible zoom", inside(view.copy(zoom = view.zoom + 0.01)))
        return view
    }

    @Test fun fitRouteIsLimitedByWidth() {
        val points = listOf(12.0 to 70.0, 12.01 to 80.0, 12.005 to 75.0)
        val view = assertFits(points, 900, 600, 40)
        assertEquals(40f, screenOf(12.0, 70.0, view, 900, 600).first, 0.001f)
        assertEquals(860f, screenOf(12.01, 80.0, view, 900, 600).first, 0.001f)
    }

    @Test fun fitRouteIsLimitedByHeightAndCentredInMercatorSpace() {
        val points = listOf(20.0 to 77.0, 65.0 to 77.01)
        val view = assertFits(points, 900, 600, 40)
        assertEquals(40f, screenOf(65.0, 77.01, view, 900, 600).second, 0.001f)
        assertEquals(560f, screenOf(20.0, 77.0, view, 900, 600).second, 0.001f)
        assertTrue(abs(view.lat - 42.5) > 1)
    }

    @Test fun fitRouteEmptySingleIdenticalAndCapped() {
        assertEquals(MapView(0.0, 0.0, 1.0), fitRoute(emptyList(), 900, 600, 40))
        for (points in listOf(listOf(12.9716 to 77.5946), List(5) { 12.9716 to 77.5946 })) {
            val view = fitRoute(points, 900, 600, 40)
            assertEquals(16.0, view.zoom, 0.0)
            assertEquals(12.9716, view.lat, 1e-6)
            assertEquals(77.5946, view.lon, 1e-6)
        }
        assertEquals(10.0, assertFits(listOf(12.0 to 77.0, 12.0001 to 77.0001), 900, 600, 40, 10.0).zoom, 0.0)
    }

    @Test fun fitRouteHandlesDateLineAndZeroExtentInOneDimension() {
        assertFits(listOf(12.0 to 179.8, 12.1 to -179.8), 700, 500, 30)
        assertFits(listOf(12.0 to 70.0, 12.0 to 80.0), 700, 500, 30)
        assertFits(listOf(12.0 to 77.0, 30.0 to 77.0), 700, 500, 30)
    }

    /** Sweep rectangle edges, then check the union of vertical intervals in every resulting strip. */
    private fun assertCoverage(view: MapView, width: Int, height: Int, cap: Int = 19) {
        val tiles = visibleTiles(view, width, height, cap)
        assertTrue(tiles.isNotEmpty())
        val world = WebMercator.worldSize(view.zoom)
        val centreY = WebMercator.y(view.lat, view.zoom)
        val north = maxOf(0.0, height / 2.0 - centreY)
        val south = minOf(height.toDouble(), height / 2.0 - centreY + world)
        val edges = (listOf(0.0, width.toDouble()) + tiles.flatMap { listOf(it.left.toDouble(), (it.left + it.size).toDouble()) })
            .filter { it in 0.0..width.toDouble() }.distinct().sorted()
        for ((left, right) in edges.zipWithNext()) {
            val x = (left + right) / 2
            val intervals = tiles.filter { x >= it.left - 0.0001 && x <= it.left + it.size + 0.0001 }.sortedBy { it.top }
            var covered = north
            for (tile in intervals) {
                if (tile.top + tile.size < north) continue
                assertTrue("Gap at x=$x, y=$covered", tile.top <= covered + 0.001)
                covered = maxOf(covered, (tile.top + tile.size).toDouble())
            }
            assertTrue("Missing bottom at x=$x", covered >= south - 0.001)
        }
        for (tile in tiles) {
            assertTrue(tile.id.z in 0..cap)
            assertTrue(tile.id.x in 0 until (1 shl tile.id.z))
            assertTrue(tile.id.y in 0 until (1 shl tile.id.z))
            assertEquals((256 * 2.0.pow(view.zoom - tile.id.z)).toFloat(), tile.size, 0.0001f)
            val (x, y) = screenOf(WebMercator.lat(tile.id.y * 256.0, tile.id.z.toDouble()),
                WebMercator.lon(tile.id.x * 256.0, tile.id.z.toDouble()), view, width, height)
            assertEquals(y, tile.top, 0.001f)
            val dx = (tile.left - x).toDouble()
            assertEquals(0.0, dx - round(dx / world) * world, 0.01)
        }
    }

    @Test fun visibleTilesCoverIntegerAndFractionalViewports() {
        for (zoom in listOf(3.0, 3.625, 15.0, 15.3)) assertCoverage(MapView(12.9716, 77.5946, zoom), 913, 647)
    }

    @Test fun visibleTilesWrapAtDateLine() {
        val view = MapView(0.0, 179.9, 3.4)
        assertCoverage(view, 720, 500)
        val xs = visibleTiles(view, 720, 500).map { it.id.x }.toSet()
        assertTrue(0 in xs && 7 in xs)
    }

    @Test fun visibleTilesClipPolarRowsAndRepeatWideWorld() {
        assertCoverage(MapView(WebMercator.MAX_LAT, 0.0, 2.5), 1000, 800)
        assertCoverage(MapView(-WebMercator.MAX_LAT, 0.0, 2.5), 1000, 800)
        val view = MapView(0.0, 0.0, 0.0)
        assertCoverage(view, 1000, 800)
        assertTrue(visibleTiles(view, 1000, 800).groupingBy { it.id }.eachCount().values.any { it > 1 })
    }

    @Test fun visibleTilesRespectTileZoomCapAndEmptyViewport() {
        assertCoverage(MapView(12.0, 77.0, 6.5), 640, 480, 4)
        assertTrue(visibleTiles(MapView(0.0, 0.0, 3.0), 0, 480).isEmpty())
    }

    @Test fun exactTileBoundsDoNotAddAnExtraRowOrColumn() {
        val tiles = visibleTiles(MapView(0.0, 0.0, 0.0), 256, 256)
        assertEquals(listOf(PlacedTile(TileId(0, 0, 0), 0f, 0f, 256f)), tiles)
    }

    @Test fun panAndInversePanRestoreTheView() {
        val view = MapView(12.9716, 179.99, 10.4)
        val panned = view.panned(173f, -91f)
        assertTrue(panned.lat < view.lat)
        assertTrue(panned.lon < view.lon)
        val restored = panned.panned(-173f, 91f)
        assertEquals(view.lat, restored.lat, 1e-8)
        assertEquals(view.lon, restored.lon, 1e-8)
        assertEquals(view.zoom, restored.zoom, 0.0)
        assertTrue(abs(view.panned(0f, Float.MAX_VALUE).lat) <= WebMercator.MAX_LAT)
    }

    @Test fun zoomKeepsTheFingerCoordinateFixedIncludingAtZoomLimits() {
        for (view in listOf(MapView(12.9716, 77.5946, 10.3), MapView(10.0, 179.99, 10.3))) {
            for (factor in listOf(2f, 0.5f, 100000f, 0.00001f)) {
                val fx = 621f; val fy = 177f
                val lat = WebMercator.lat(WebMercator.y(view.lat, view.zoom) + fy - 300, view.zoom)
                val lon = WebMercator.lon(WebMercator.x(view.lon, view.zoom) + fx - 400, view.zoom)
                val next = view.zoomed(factor, fx, fy, 800, 600)
                val screen = screenOf(lat, lon, next, 800, 600)
                assertEquals(fx, screen.first, 0.5f)
                assertEquals(fy, screen.second, 0.5f)
                assertTrue(next.zoom in 2.0..19.0)
            }
        }
    }

    private val clock = AtomicLong(10_000_000)
    private val id = TileId(3, 4, 3)
    private val body = byteArrayOf(1, 2, 3, 4)
    private fun store(dir: File, fresh: Long = 1000, retry: Long = 100, max: Long = 1_000_000, parallel: Int = 4, prune: Int = 200,
        fetch: suspend (String, String) -> ByteArray?): TileStore = TileStore(dir, "https://example.test/{z}/{x}/{y}.png", "Heartline test",
        maxBytes = max, freshMs = fresh, retryAfterMs = retry, parallel = parallel, pruneEvery = prune, now = clock::get, fetch = fetch)

    @Test fun firstDownloadThenDiskHitIncludingANewStoreInstance() = runBlocking {
        val dir = temporary.newFolder()
        var calls = 0
        val fetch: suspend (String, String) -> ByteArray? = { url, agent ->
            assertEquals("https://example.test/3/4/3.png", url)
            assertEquals("Heartline test", agent)
            calls++; body
        }
        val cache = store(dir, fetch = fetch)
        assertArrayEquals(body, cache.tile(id))
        assertArrayEquals(body, cache.tile(id))
        assertArrayEquals(body, store(dir, fetch = fetch).tile(id))
        assertEquals(1, calls)
        assertArrayEquals(body, File(dir, "3/4/3.png").readBytes())
        assertEquals(dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }, cache.sizeBytes())
    }

    @Test fun twentyConcurrentCallersShareOneDownload() = runBlocking {
        val calls = AtomicInteger()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val cache = store(temporary.newFolder()) { _, _ -> calls.incrementAndGet(); entered.complete(Unit); release.await(); body }
        withTimeout(5000) {
            val waiters = List(20) { async { cache.tile(id) } }
            entered.await()
            release.complete(Unit)
            waiters.awaitAll().forEach { assertArrayEquals(body, it) }
        }
        assertEquals(1, calls.get())
    }

    @Test fun cancellingOneWaiterDoesNotCancelTheSharedDownload() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val cache = store(temporary.newFolder()) { _, _ -> calls.incrementAndGet(); entered.complete(Unit); release.await(); body }
        withTimeout(5000) {
            val first = async { cache.tile(id) }
            entered.await()
            val second = async { cache.tile(id) }
            first.cancelAndJoin()
            release.complete(Unit)
            assertArrayEquals(body, second.await())
        }
        assertEquals(1, calls.get())
    }

    @Test fun expiredTileRefreshesAndFailedRefreshServesStaleBytes() = runBlocking {
        var calls = 0
        val replacement = byteArrayOf(8, 9)
        val cache = store(temporary.newFolder()) { _, _ -> when (++calls) { 1 -> body; 2 -> replacement; else -> null } }
        assertArrayEquals(body, cache.tile(id))
        clock.addAndGet(1001)
        assertArrayEquals(replacement, cache.tile(id))
        clock.addAndGet(1001)
        assertArrayEquals(replacement, cache.tile(id))
        assertArrayEquals(replacement, cache.tile(id))
        assertEquals(3, calls)
    }

    @Test fun failedDownloadsWaitForTheRetryDeadline() = runBlocking {
        var calls = 0
        val cache = store(temporary.newFolder()) { _, _ -> calls++; if (calls < 2) null else body }
        assertNull(cache.tile(id))
        clock.addAndGet(99)
        assertNull(cache.tile(id))
        assertEquals(1, calls)
        clock.incrementAndGet()
        assertArrayEquals(body, cache.tile(id))
        assertEquals(2, calls)
    }

    @Test fun invalidIdsNeverReachDiskOrFetch() = runBlocking {
        var calls = 0
        val dir = temporary.newFolder()
        val cache = store(dir) { _, _ -> calls++; body }
        for (bad in listOf(TileId(-1, 0, 0), TileId(23, 0, 0), TileId(3, -1, 0), TileId(3, 0, -1), TileId(3, 8, 0), TileId(3, 0, 8))) {
            assertNull(cache.tile(bad))
        }
        assertEquals(0, calls)
        assertEquals(0L, cache.sizeBytes())
        assertTrue(dir.listFiles()!!.isEmpty())
    }

    @Test fun highestValidTileZoomIsAccepted() = runBlocking {
        val cache = store(temporary.newFolder()) { url, _ -> assertTrue(url.endsWith("/22/4194303/4194303.png")); body }
        assertArrayEquals(body, cache.tile(TileId(22, 4194303, 4194303)))
    }

    @Test fun pruningKeepsRecentlyReadTilesAndCountsMetadata() = runBlocking {
        val dir = temporary.newFolder()
        val calls = AtomicInteger()
        val cache = store(dir, fresh = Long.MAX_VALUE, max = 350, prune = 2) { _, _ -> calls.incrementAndGet(); ByteArray(100) { 7 } }
        for (x in 0..2) { cache.tile(TileId(3, x, 3)); clock.incrementAndGet() }
        clock.addAndGet(3_600_001)
        cache.tile(TileId(3, 0, 3))
        clock.incrementAndGet()
        cache.tile(TileId(3, 3, 3))
        assertTrue(cache.sizeBytes() <= 280)
        assertTrue(File(dir, "3/0/3.png").isFile)
        assertTrue(File(dir, "3/3/3.png").isFile)
        assertFalse(File(dir, "3/1/3.png").exists())
        cache.tile(TileId(3, 0, 3))
        assertEquals(4, calls.get())
    }

    @Test fun readTouchesLruAtMostHourlyWithoutExtendingFreshness() = runBlocking {
        val dir = temporary.newFolder()
        var calls = 0
        val cache = store(dir, fresh = 7_200_000) { _, _ -> calls++; body }
        cache.tile(id)
        val file = File(dir, "3/4/3.png")
        val downloaded = file.lastModified()
        clock.addAndGet(1000)
        cache.tile(id)
        assertEquals(downloaded, file.lastModified())
        clock.addAndGet(3_600_000)
        cache.tile(id)
        assertEquals(clock.get(), file.lastModified())
        clock.addAndGet(3_600_000)
        cache.tile(id)
        assertEquals(2, calls)
    }

    @Test fun pruningRunsOnDownloadCadenceAndNeverOnHits() = runBlocking {
        val cache = store(temporary.newFolder(), max = 50, prune = 3) { _, _ -> ByteArray(100) }
        cache.tile(id)
        assertTrue(cache.sizeBytes() > 50)
        repeat(5) { cache.tile(id) }
        assertTrue(cache.sizeBytes() > 50)
        cache.tile(TileId(3, 5, 3))
        cache.tile(TileId(3, 6, 3))
        assertEquals(0L, cache.sizeBytes())
    }

    @Test fun parallelLimitBoundsFetchConcurrency() = runBlocking {
        val active = AtomicInteger(); val peak = AtomicInteger(); val calls = AtomicInteger()
        val firstThree = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val cache = store(temporary.newFolder(), parallel = 3) { _, _ ->
            calls.incrementAndGet()
            val count = active.incrementAndGet()
            peak.updateAndGet { maxOf(it, count) }
            if (count == 3) firstThree.complete(Unit)
            try { release.await(); delay(10); body } finally { active.decrementAndGet() }
        }
        withTimeout(5000) {
            val waiters = List(20) { x -> async { cache.tile(TileId(5, x, 3)) } }
            firstThree.await()
            assertEquals(3, active.get())
            release.complete(Unit)
            waiters.awaitAll().forEach { assertArrayEquals(body, it) }
        }
        assertEquals(3, peak.get())
        assertEquals(20, calls.get())
    }

    @Test fun clearDeletesFilesAndAllowsRetryAfterFailure() = runBlocking {
        var calls = 0
        val cache = store(temporary.newFolder()) { _, _ -> calls++; if (calls == 2) null else body }
        cache.tile(id)
        assertNull(cache.tile(TileId(3, 5, 3)))
        cache.clear()
        assertEquals(0L, cache.sizeBytes())
        assertArrayEquals(body, cache.tile(TileId(3, 5, 3)))
        assertEquals(3, calls)
    }

    @Test fun clearDuringDownloadDoesNotRepopulateTheCache() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val cache = store(temporary.newFolder()) { _, _ -> entered.complete(Unit); release.await(); body }
        withTimeout(5000) {
            val waiting = async { cache.tile(id) }
            entered.await()
            cache.clear()
            release.complete(Unit)
            assertNull(waiting.await())
            assertEquals(0L, cache.sizeBytes())
        }
    }

    @Test fun fetchExceptionsAndInvalidBodiesAreFailuresButCancellationPropagates() = runBlocking {
        val cache = store(temporary.newFolder()) { _, _ -> throw java.io.IOException("offline") }
        assertNull(cache.tile(id))
        for (bytes in listOf(byteArrayOf(), ByteArray(2 * 1024 * 1024 + 1))) {
            assertNull(store(temporary.newFolder()) { _, _ -> bytes }.tile(id))
        }
        try {
            store(temporary.newFolder()) { _, _ -> throw CancellationException("cancelled fetch") }.tile(id)
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
    }
}
