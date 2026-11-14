package dev.heartline.app

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.math.*

/** World pixels keep projection, gestures and tile placement in the same space, even at fractional zoom. */
object WebMercator {
    const val TILE = 256
    const val MAX_LAT = 85.05112878
    fun worldSize(zoom: Double): Double = TILE * 2.0.pow(zoom)
    fun x(lon: Double, zoom: Double): Double = (lon + 180) / 360 * worldSize(zoom)
    fun y(lat: Double, zoom: Double): Double {
        val radians = Math.toRadians(lat.coerceIn(-MAX_LAT, MAX_LAT))
        return ((1 - ln(tan(radians) + 1 / cos(radians)) / PI) / 2 * worldSize(zoom))
            .coerceIn(0.0, worldSize(zoom))
    }
    fun lon(x: Double, zoom: Double): Double = x / worldSize(zoom) * 360 - 180
    fun lat(y: Double, zoom: Double): Double = Math.toDegrees(atan(sinh(PI * (1 - 2 * y / worldSize(zoom)))))
        .coerceIn(-MAX_LAT, MAX_LAT)
}

data class MapView(val lat: Double, val lon: Double, val zoom: Double)
data class TileId(val z: Int, val x: Int, val y: Int)
/** Tile coordinates are wrapped; screen positions are not, so a wide viewport can repeat the world. */
data class PlacedTile(val id: TileId, val left: Float, val top: Float, val size: Float)

private fun wrap(value: Double, period: Double): Double = ((value % period) + period) % period

/** Fit in Mercator space; the largest empty longitude gap is outside the route, including across the date line. */
fun fitRoute(points: List<Pair<Double, Double>>, widthPx: Int, heightPx: Int, paddingPx: Int, maxZoom: Double = 18.0): MapView {
    if (points.isEmpty()) return MapView(0.0, 0.0, 1.0)
    val xs = points.map { wrap(WebMercator.x(it.second, 0.0), 256.0) }.sorted()
    val ys = points.map { WebMercator.y(it.first, 0.0) }
    val gap = xs.indices.maxBy { i -> (if (i == xs.lastIndex) xs[0] + 256 else xs[i + 1]) - xs[i] }
    val minX = xs[(gap + 1) % xs.size]
    val maxX = xs[gap] + if (gap == xs.lastIndex) 0 else 256
    val minY = ys.min(); val maxY = ys.max()
    val centreLat = WebMercator.lat((minY + maxY) / 2, 0.0)
    val centreLon = WebMercator.lon(wrap((minX + maxX) / 2, 256.0), 0.0)
    if (minX == maxX && minY == maxY) return MapView(centreLat, centreLon, 16.0)
    require(paddingPx >= 0 && widthPx.toDouble() > 2.0 * paddingPx && heightPx.toDouble() > 2.0 * paddingPx)
    val zx = if (maxX == minX) Double.POSITIVE_INFINITY else log2((widthPx - 2.0 * paddingPx) / (maxX - minX))
    val zy = if (maxY == minY) Double.POSITIVE_INFINITY else log2((heightPx - 2.0 * paddingPx) / (maxY - minY))
    return MapView(centreLat, centreLon, minOf(zx, zy, maxZoom))
}

/** Half-open viewport bounds avoid requesting an extra column when the edge lies exactly on a tile. */
fun visibleTiles(view: MapView, widthPx: Int, heightPx: Int, maxTileZoom: Int = 19): List<PlacedTile> {
    if (widthPx <= 0 || heightPx <= 0) return emptyList()
    val z = floor(view.zoom).toInt().coerceIn(0, maxTileZoom.coerceIn(0, 22))
    val count = 1 shl z
    val size = WebMercator.TILE * 2.0.pow(view.zoom - z)
    val left = WebMercator.x(view.lon, view.zoom) - widthPx / 2.0
    val top = WebMercator.y(view.lat, view.zoom) - heightPx / 2.0
    val firstX = floor(left / size).toLong(); val lastX = ceil((left + widthPx) / size).toLong() - 1
    val firstY = maxOf(0L, floor(top / size).toLong())
    val lastY = minOf(count - 1L, ceil((top + heightPx) / size).toLong() - 1)
    return buildList {
        for (y in firstY..lastY) for (x in firstX..lastX) {
            val wrappedX = ((x % count + count) % count).toInt()
            add(PlacedTile(TileId(z, wrappedX, y.toInt()), (x * size - left).toFloat(), (y * size - top).toFloat(), size.toFloat()))
        }
    }
}

/** Use the nearest copy of a longitude so routes remain continuous across the date line. */
fun screenOf(lat: Double, lon: Double, view: MapView, widthPx: Int, heightPx: Int): Pair<Float, Float> {
    val world = WebMercator.worldSize(view.zoom)
    val dx = wrap(WebMercator.x(lon, view.zoom) - WebMercator.x(view.lon, view.zoom) + world / 2, world) - world / 2
    return (widthPx / 2.0 + dx).toFloat() to
        (heightPx / 2.0 + WebMercator.y(lat, view.zoom) - WebMercator.y(view.lat, view.zoom)).toFloat()
}

fun MapView.panned(dx: Float, dy: Float): MapView {
    val world = WebMercator.worldSize(zoom)
    return copy(lat = WebMercator.lat((WebMercator.y(lat, zoom) - dy).coerceIn(0.0, world), zoom),
        lon = WebMercator.lon(wrap(WebMercator.x(lon, zoom) - dx, world), zoom))
}

/** Moving the centre by the change in the finger's world offset keeps its coordinate anchored. */
fun MapView.zoomed(factor: Float, fx: Float, fy: Float, widthPx: Int, heightPx: Int, minZoom: Double = 2.0, maxZoom: Double = 19.0): MapView {
    require(factor.isFinite() && factor > 0)
    val nextZoom = (zoom + log2(factor.toDouble())).coerceIn(minZoom, maxZoom)
    val scale = 2.0.pow(nextZoom - zoom)
    val world = WebMercator.worldSize(nextZoom)
    val x = WebMercator.x(lon, nextZoom) + (fx - widthPx / 2.0) * (scale - 1)
    val y = WebMercator.y(lat, nextZoom) + (fy - heightPx / 2.0) * (scale - 1)
    return MapView(WebMercator.lat(y.coerceIn(0.0, world), nextZoom), WebMercator.lon(wrap(x, world), nextZoom), nextZoom)
}

/** Disk bookkeeping is serialized, while downloads share a bounded, caller-independent IO scope. */
class TileStore(
    private val dir: File,
    private val urlTemplate: String,
    private val userAgent: String,
    private val maxBytes: Long = 80L * 1024 * 1024,
    private val freshMs: Long = 30L * 86_400_000,
    private val retryAfterMs: Long = 60_000,
    private val parallel: Int = 4,
    private val pruneEvery: Int = 200,
    private val now: () -> Long = System::currentTimeMillis,
    private val fetch: (suspend (url: String, userAgent: String) -> ByteArray?)? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val permits = Semaphore(parallel)
    private val inFlight = mutableMapOf<TileId, Deferred<ByteArray?>>()
    private val failures = mutableMapOf<TileId, Long>()
    private var downloads = 0
    private var generation = 0L

    init { require(maxBytes >= 0 && freshMs >= 0 && retryAfterMs >= 0 && pruneEvery > 0) }

    suspend fun tile(id: TileId): ByteArray? = withContext(Dispatchers.IO) {
        if (id.z !in 0..22 || id.x !in 0 until (1 shl id.z) || id.y !in 0 until (1 shl id.z)) return@withContext null
        val pending = mutex.withLock {
            inFlight[id]?.let { return@withLock it }
            val file = file(id)
            val downloaded = quietly { stamp(file).readText().toLong() }
            if (downloaded != null && now() - downloaded < freshMs) {
                read(file)?.let { return@withContext it }
            }
            failures[id]?.let { if (now() - it < retryAfterMs) return@withContext read(file) }
            val epoch = generation
            lateinit var task: Deferred<ByteArray?>
            task = scope.async(start = CoroutineStart.LAZY) {
                try { download(id, epoch) }
                finally {
                    withContext(NonCancellable) {
                        mutex.withLock { if (inFlight[id] === task) inFlight.remove(id) }
                    }
                }
            }
            inFlight[id] = task
            task.start()
            task
        }
        pending.await()
    }

    private suspend fun download(id: TileId, epoch: Long): ByteArray? {
        val bytes = permits.withPermit {
            try {
                val url = urlTemplate.replace("{z}", "${id.z}").replace("{x}", "${id.x}").replace("{y}", "${id.y}")
                val result = if (fetch == null) http(url) else fetch.invoke(url, userAgent)
                result?.takeIf { it.size in 1..MAX_BODY }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { null }
        }
        return mutex.withLock {
            // A clear during a request must not let that request repopulate the cache afterwards.
            if (epoch != generation) return@withLock null
            val file = file(id)
            if (bytes == null) {
                failures[id] = now()
                return@withLock read(file)
            }
            failures.remove(id)
            quietly {
                file.parentFile!!.mkdirs()
                // If metadata fails, the tile is usable but conservatively treated as stale next time.
                atomicWrite(file, bytes)
                stamp(file).delete()
                atomicWrite(stamp(file), now().toString().toByteArray(Charsets.UTF_8))
                file.setLastModified(now())
            }
            downloads++
            if (downloads % pruneEvery == 0) quietly { prune() }
            bytes
        }
    }

    private fun file(id: TileId) = File(dir, "${id.z}/${id.x}/${id.y}.png")
    private fun stamp(file: File) = File(file.parentFile, "${file.name}.downloaded")

    /** Touch only the LRU clock; the sidecar retains the time the bytes were downloaded. */
    private fun read(file: File): ByteArray? = quietly {
        if (!file.isFile || file.length() !in 1..MAX_BODY.toLong()) return@quietly null
        file.readBytes().also { if (now() - file.lastModified() >= 3_600_000) file.setLastModified(now()) }
    }

    private fun atomicWrite(file: File, bytes: ByteArray) {
        val temp = File.createTempFile("tile-", ".tmp", file.parentFile)
        try {
            temp.writeBytes(bytes)
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temp.delete() }
    }

    private fun files(): List<File> = if (dir.isDirectory) dir.walkTopDown().filter { it.isFile }.toList() else emptyList()

    private fun prune() {
        val files = files()
        var total = files.sumOf { it.length() }
        if (total <= maxBytes) return
        val target = (maxBytes * 0.8).toLong()
        for (file in files.filter { it.extension == "png" }.sortedBy { it.lastModified() }) {
            for (part in listOf(file, stamp(file))) {
                val size = part.length()
                if (part.delete()) total -= size
            }
            if (total <= target) break
        }
    }

    /** Includes freshness metadata, so the settings value reflects actual file bytes. */
    suspend fun sizeBytes(): Long = withContext(Dispatchers.IO) { mutex.withLock { files().sumOf { it.length() } } }

    suspend fun clear() = withContext(Dispatchers.IO) {
        mutex.withLock {
            generation++
            inFlight.clear()
            failures.clear()
            downloads = 0
            if (dir.exists()) dir.deleteRecursively()
            Unit
        }
    }

    private suspend fun http(url: String): ByteArray? {
        var connection: HttpURLConnection? = null
        return try {
            val uri = URI(url)
            if (!uri.scheme.equals("https", ignoreCase = true)) return null
            connection = uri.toURL().openConnection() as HttpURLConnection
            connection.connectTimeout = 10_000; connection.readTimeout = 10_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", userAgent)
            if (connection.responseCode != 200 || connection.contentType?.startsWith("image/", ignoreCase = true) != true) return null
            if (connection.contentLengthLong > MAX_BODY) return null
            connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (output.size() + n > MAX_BODY) return null
                    output.write(buffer, 0, n)
                }
                output.toByteArray().takeIf { it.isNotEmpty() }
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { null }
        finally { connection?.disconnect() }
    }

    private inline fun <T> quietly(block: () -> T): T? = try { block() }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { null }

    private companion object { const val MAX_BODY = 2 * 1024 * 1024 }
}
