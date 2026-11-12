package dev.heartline.service

import dev.heartline.ble.ConnectionState
import dev.heartline.ble.WatchConnection
import dev.heartline.data.HeartlineDatabase
import dev.heartline.data.SecretStore
import dev.heartline.protocol.Agps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.util.UUID
import java.util.zip.ZipInputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** A signed-in Zepp account: what fetching A-GPS data needs. Kept encrypted on the phone (see [SecretStore]). */
data class ZeppSession(val email: String, val userId: String, val appToken: String, val loginToken: String, val apiHost: String? = null) {
    fun json(): String = JSONObject().put("email", email).put("user_id", userId).put("app_token", appToken).put("login_token", loginToken)
        .put("api_host", apiHost ?: "").toString()
    companion object {
        fun parse(s: String?): ZeppSession? = s?.let { runCatching { JSONObject(it).let { j ->
            ZeppSession(j.getString("email"), j.getString("user_id"), j.getString("app_token"), j.getString("login_token"),
                j.optString("api_host").ifEmpty { null }) } }.getOrNull() }

    }
}

/** The account's sign-in is no longer accepted: sign in again. */
class ZeppSignedOut(message: String) : Exception(message)

/**
 * Zepp's sign-in and A-GPS download, as huami-token does them (zepp.py): an encrypted email and password exchange for an
 * access token, a login for the app token, then the A-GPS file list. The password is sent to Zepp once and not kept.
 */
object ZeppApi {
    private const val TOKENS = "https://api-user-us2.zepp.com/v2/registrations/tokens"
    private const val LOGIN = "https://api-mifit-us2.zepp.com/v2/client/login"
    private const val HOST = "api-mifit-us2.zepp.com"
    private val KEY = "xeNtBVqzDc6tuNTh".toByteArray()
    private val IV = "MAAAYAAAAAAAAABg".toByteArray()
    private const val VERSION = "9.12.5"
    private const val UA = "Zepp/9.12.5 (Pixel 4; Android 12; Density/2.75)"

    private fun form(pairs: List<Pair<String, String>>) = pairs.joinToString("&") { (k, v) -> URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v, "UTF-8") }

    private class Response(val code: Int, val location: String?, val body: ByteArray)
    private fun request(url: String, method: String, headers: Map<String, String>, body: ByteArray? = null, timeoutMs: Int = 20_000): Response {
        val c = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            c.requestMethod = method; c.instanceFollowRedirects = false; c.connectTimeout = 15_000; c.readTimeout = timeoutMs
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (body != null) { c.doOutput = true; c.outputStream.use { it.write(body) } }
            val code = c.responseCode
            val stream = if (code >= 400) c.errorStream else c.inputStream
            return Response(code, c.getHeaderField("Location"), stream?.use { it.readBytes() } ?: ByteArray(0))
        } finally { c.disconnect() }
    }

    /** Signs in with a Zepp account's email and password (accounts made with Google or Apple sign-in have none). */
    suspend fun signIn(email: String, password: String): ZeppSession = withContext(Dispatchers.IO) {
        val payload = form(listOf("emailOrPhone" to email, "state" to "REDIRECTION", "client_id" to "HuaMi", "password" to password,
            "redirect_uri" to "https://s3-us-west-2.amazonaws.com/hm-registration/successsignin.html", "region" to "us-west-2",
            "token" to "access", "token" to "refresh", "country_code" to "US")).toByteArray()
        val encrypted = Cipher.getInstance("AES/CBC/PKCS5Padding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(KEY, "AES"), IvParameterSpec(IV)) }.doFinal(payload)
        val tokens = request(TOKENS, "POST", mapOf("app_name" to "com.huami.midong", "appname" to "com.huami.midong", "cv" to "151689_$VERSION", "v" to "2.0",
            "appplatform" to "android_phone", "vb" to "202509151347", "vn" to VERSION, "user-agent" to UA, "x-hm-ekv" to "1",
            "content-type" to "application/x-www-form-urlencoded; charset=UTF-8"), encrypted)
        if (tokens.code != 303 || tokens.location == null) throw IllegalStateException(
            if (tokens.code in 400..499) "Zepp didn't accept that email and password" else "Zepp sign-in failed (HTTP ${tokens.code})")
        val query = URI(tokens.location).rawQuery.orEmpty().split("&").mapNotNull { p -> p.split("=", limit = 2).takeIf { it.size == 2 }?.let { it[0] to java.net.URLDecoder.decode(it[1], "UTF-8") } }.toMap()
        val access = query["access"] ?: throw IllegalStateException("Zepp didn't accept that email and password")
        val login = request(LOGIN, "POST", mapOf("app_name" to "com.huami.webapp", "appname" to "com.huami.webapp", "origin" to "https://user.zepp.com",
            "referer" to "https://user.zepp.com/", "user-agent" to "Mozilla/5.0 (X11; Linux x86_64; rv:133.0) Gecko/20100101 Firefox/133.0",
            "content-type" to "application/x-www-form-urlencoded; charset=UTF-8", "accept" to "application/json, text/plain, */*"),
            form(listOf("code" to access, "device_id" to UUID.randomUUID().toString(), "device_model" to "android_phone", "app_version" to VERSION,
                "dn" to "api-mifit.zepp.com,api-user.zepp.com,api-mifit.zepp.com,api-watch.zepp.com,app-analytics.zepp.com,auth.zepp.com,api-analytics.zepp.com",
                "third_name" to "huami", "source" to "com.huami.watch.hmwatchmanager:$VERSION:151689", "app_name" to "com.huami.midong",
                "country_code" to "US", "grant_type" to "access_token", "allow_registration" to "false", "lang" to "en", "countryState" to "US-NY")).toByteArray())
        check(login.code == 200) { "Zepp sign-in failed at the second step (HTTP ${login.code})" }
        val info = JSONObject(String(login.body)).optJSONObject("token_info") ?: error("Zepp's sign-in answer had no tokens")
        ZeppSession(email, info.optString("user_id").ifEmpty { error("Zepp's sign-in answer had no user id") },
            info.optString("app_token").ifEmpty { error("Zepp's sign-in answer had no app token") }, info.optString("login_token"))
    }

    /** The almanac and orbit-prediction files, by name, from the current `cep_7days.zip` and `lle_1week.zip`. */
    suspend fun agpsFiles(s: ZeppSession): Map<String, ByteArray> = withContext(Dispatchers.IO) {
        val headers = mapOf("hm-privacy-diagnostics" to "false", "country" to "US", "appplatform" to "android_phone", "hm-privacy-ceip" to "false",
            "x-request-id" to UUID.randomUUID().toString(), "timezone" to "Europe/London", "channel" to "a100900101016", "vb" to "202509151347",
            "cv" to "151689_$VERSION", "appname" to "com.huami.midong", "v" to "2.0", "vn" to VERSION, "apptoken" to s.appToken, "lang" to "en_US", "user-agent" to UA)
        val r = UUID.randomUUID().toString()
        val params = form(listOf("r" to r, "r" to r, "userid" to s.userId, "appid" to java.security.SecureRandom().nextLong().toULong().toString(),
            "channel" to "a100900101016", "country" to "US", "cv" to "151689_$VERSION", "device" to "android_32", "device_type" to "android_phone",
            "lang" to "en_US", "timezone" to "Europe/Berlin", "v" to "2.0"))
        val archives = HashMap<String, ByteArray>()
        // huami-token asks for every type and keeps the two archives the bundle is made from; they come as AGPSZIP and LLE.
        for (type in listOf("AGPSZIP", "LLE", "AGPS", "AGPS_ALM")) {
            if (archives.size == 2) break
            val list = request("https://${s.apiHost ?: HOST}/apps/com.xiaomi.hm.health/fileTypes/$type/files?$params", "GET", headers)
            if (list.code == 401 || list.code == 403) throw ZeppSignedOut("Zepp no longer accepts this sign-in; sign in again")
            if (list.code != 200) continue
            val url = runCatching { JSONArray(String(list.body)).optJSONObject(0)?.optString("fileUrl") }.getOrNull()?.takeIf { it.isNotEmpty() } ?: continue
            val name = url.substringAfterLast('/').substringBefore('?')
            val kind = when { name.endsWith("cep_7days.zip") -> "cep"; name.endsWith("lle_1week.zip") -> "lle"; else -> null } ?: continue
            if (kind in archives) continue
            val file = request(url, "GET", headers, timeoutMs = 60_000)
            check(file.code == 200) { "Downloading $name failed (HTTP ${file.code})" }
            archives[kind] = file.body
        }
        check("cep" in archives && "lle" in archives) { "Zepp didn't offer the A-GPS files (missing ${listOf("cep", "lle").filter { it !in archives }.joinToString()})" }
        archives.values.flatMap { unzip(it).entries }.associate { it.key to it.value }
    }

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> = buildMap {
        ZipInputStream(bytes.inputStream()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (!e.isDirectory) put(e.name.substringAfterLast('/'), ByteArrayOutputStream().also { out -> z.copyTo(out) }.toByteArray())
            }
        }
    }
}

/** What the A-GPS screen shows. [validUntil]: the orbit predictions cover a week from the update. */
data class AgpsStatus(val signedInAs: String? = null, val lastUpdate: Long? = null, val running: Boolean = false, val progress: Int? = null,
    val message: String? = null, val failed: Boolean = false, val auto: Boolean = true) {
    val validUntil: Long? get() = lastUpdate?.let { it + 7 * 86_400 }
}

/**
 * Keeps the watch's A-GPS data fresh: with a Zepp sign-in, downloads the week's data and sends it to the watch, by hand
 * or every [AUTO_EVERY_S] while the watch is connected.
 */
class AgpsUpdater(context: android.content.Context, private val db: HeartlineDatabase, private val watch: WatchConnection,
    private val scope: kotlinx.coroutines.CoroutineScope) {
    private val secret = SecretStore(context, "zepp")
    private val lock = Mutex()
    private val state = MutableStateFlow(AgpsStatus())
    val status: StateFlow<AgpsStatus> = state

    suspend fun load() {
        val saved = runCatching { JSONObject(db.meta.get(KEY) ?: "{}") }.getOrDefault(JSONObject())
        state.value = AgpsStatus(ZeppSession.parse(secret.load())?.email, saved.optLong("last").takeIf { it > 0 }, false, null,
            saved.optString("message").ifEmpty { null }, saved.optBoolean("failed"), saved.optBoolean("auto", true))
    }

    suspend fun signIn(email: String, password: String) {
        val s = ZeppApi.signIn(email.trim(), password)
        secret.save(s.json())
        state.value = state.value.copy(signedInAs = s.email, message = null, failed = false)
        db.events.add("info", "Signed in to Zepp for A-GPS data")
    }
    private var listener: kotlinx.coroutines.Job? = null

    /**
     * Sign-in in the phone's own browser, where Google or Apple is usually signed in already: returns Zepp's sign-in page
     * to open, and listens once on this phone (127.0.0.1, a random port and path) for the page to hand the sign-in back,
     * as Zepp's own command-line tools do on a computer. Gives up after 15 minutes.
     */
    fun browserSignIn(): String {
        listener?.cancel()
        val server = java.net.ServerSocket(0, 4, java.net.InetAddress.getByName("127.0.0.1")).apply { soTimeout = 1000 }
        val path = "/" + UUID.randomUUID().toString().replace("-", "")
        val back = "http://127.0.0.1:${server.localPort}$path"
        listener = scope.launch(Dispatchers.IO) {
            try {
                val until = System.currentTimeMillis() + 15 * 60_000L
                while (isActive && System.currentTimeMillis() < until) {
                    val socket = try { server.accept() } catch (_: java.net.SocketTimeoutException) { continue }
                    val session = socket.use { runCatching { answer(it, path) }.getOrNull() } ?: continue
                    secret.save(session.json())
                    state.value = state.value.copy(signedInAs = session.email, message = null, failed = false)
                    db.events.add("info", "Signed in to Zepp in the browser for A-GPS data")
                    // Signed in to send it, so send it now when the watch is there.
                    if (watch.connection.value.state == ConnectionState.CONNECTED) runCatching { update() }
                    break
                }
            } finally { server.close() }
        }
        return BROWSER_SIGN_IN + URLEncoder.encode(back, "UTF-8")
    }

    /** Reads one request; the session when it's the page handing the sign-in back to [path], after answering it. */
    private fun answer(socket: java.net.Socket, path: String): ZeppSession? {
        socket.soTimeout = 10_000
        val line = socket.getInputStream().bufferedReader().readLine().orEmpty()
        val target = line.split(" ").getOrNull(1).orEmpty()
        val query = target.substringAfter('?', "").split("&").mapNotNull { p -> p.split("=", limit = 2).takeIf { it.size == 2 }
            ?.let { it[0] to java.net.URLDecoder.decode(it[1], "UTF-8") } }.toMap()
        val session = if (target.substringBefore('?') != path) null else query["userid"]?.takeIf { it.isNotBlank() }?.let { user ->
            query["apptoken"]?.takeIf { it.isNotBlank() }?.let { token ->
                ZeppSession("Zepp account, through your browser", user, token, "",
                    Regex("api-mifit[a-z0-9-]*[.](?:zepp|huami)[.]com").find(query["cname"].orEmpty())?.value)
            }
        }
        val bytes = (if (session != null) RETURN_PAGE else "<!doctype html><title>Heartline</title><p>Nothing to do here.</p>").toByteArray()
        socket.getOutputStream().apply {
            write(("HTTP/1.1 ${if (session != null) "200 OK" else "404 Not Found"}\r\nContent-Type: text/html; charset=utf-8\r\n" +
                "Content-Length: ${bytes.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray())
            write(bytes); flush()
        }
        return session
    }
    suspend fun signOut() { secret.clear(); state.value = state.value.copy(signedInAs = null); db.events.add("info", "Signed out of Zepp") }
    suspend fun setAuto(on: Boolean) { state.value = state.value.copy(auto = on); save() }

    /** Downloads and sends the data now. Throws with a readable message when it can't. */
    suspend fun update() = lock.withLock {
        val session = ZeppSession.parse(secret.load()) ?: error("Sign in to Zepp first")
        check(watch.connection.value.state == ConnectionState.CONNECTED) { "Connect the watch first" }
        state.value = state.value.copy(running = true, progress = null, message = "Downloading from Zepp", failed = false)
        try {
            val files = try { ZeppApi.agpsFiles(session) } catch (e: ZeppSignedOut) { secret.clear(); state.value = state.value.copy(signedInAs = null); throw e }
            val bundle = Agps.bundle(files)
            state.value = state.value.copy(message = "Sending to the watch", progress = 0)
            watch.uploadAgps(bundle) { p -> state.value = state.value.copy(progress = p, message = if (p >= 100) "The watch is unpacking it" else "Sending to the watch") }
            state.value = state.value.copy(running = false, progress = null, lastUpdate = System.currentTimeMillis() / 1000, message = null, failed = false)
            db.events.add("info", "A-GPS data updated on the watch (${bundle.size / 1024} KB)")
        } catch (e: Exception) {
            dev.heartline.ble.rethrowIfCancelled(e)
            state.value = state.value.copy(running = false, progress = null, message = e.message ?: "A-GPS update failed", failed = true)
            db.events.add("warn", "A-GPS update failed: ${e.message}")
            throw e
        } finally { if (state.value.running) state.value = state.value.copy(running = false, progress = null); save() }
    }

    /** Called now and then while connected: updates when it's on, signed in and the data is [AUTO_EVERY_S] old. */
    suspend fun maybeAuto() {
        val s = state.value
        if (!s.auto || s.signedInAs == null || s.running) return
        val now = System.currentTimeMillis() / 1000
        if (s.lastUpdate != null && now - s.lastUpdate < AUTO_EVERY_S) return
        // After a failure, wait 6 hours before the next try.
        if (s.failed && (db.meta.get(KEY)?.let { runCatching { JSONObject(it).optLong("tried") }.getOrNull() } ?: 0L) > now - 6 * 3600) return
        runCatching { update() }
    }

    private suspend fun save() {
        val s = state.value
        db.meta.set(KEY, JSONObject().put("last", s.lastUpdate ?: 0).put("message", s.message ?: "").put("failed", s.failed).put("auto", s.auto)
            .put("tried", System.currentTimeMillis() / 1000).toString())
    }

    companion object {
        /** Zepp's sign-in page in the mode its command-line tools use: it hands the sign-in to the address given, with Google offered. */
        private const val BROWSER_SIGN_IN = "https://user.zepp.com/universalLogin/index.html#/login?project_name=zepp_agent_remote_cli" +
            "&platform_app=com.huami.zeppos.cli&specify_lang=en&project_redirect_uri="
        /** What the browser shows once Heartline has the sign-in; it goes back to the app on its own, or with the button. */
        private const val RETURN_PAGE = "<!doctype html><html><head><meta charset=utf-8><meta name=viewport content=\"width=device-width,initial-scale=1\">" +
            "<title>Signed in</title><style>body{font:17px/1.5 system-ui,sans-serif;margin:0;padding:48px 24px;background:#fbf6f3;color:#1b2340}" +
            "@media(prefers-color-scheme:dark){body{background:#000;color:#e9ecf5}}h1{font-size:26px;margin:0 0 8px}" +
            "a{display:block;margin-top:28px;padding:14px;border-radius:999px;background:#c8302c;color:#fff;text-align:center;text-decoration:none;font-weight:600}</style>" +
            "</head><body><h1>Signed in to Zepp</h1><p>Heartline has it and is updating the watch's GPS.</p>" +
            "<a id=b href=\"intent://zepp-signed-in#Intent;scheme=heartline;package=dev.heartline;end\">Back to Heartline</a>" +
            "<script>setTimeout(function(){location.href=document.getElementById('b').href},300)</script></body></html>"
        const val KEY = "agps_status"
        /** The orbit predictions cover a week; refreshing every 3 days keeps them current with room for a missed day. */
        const val AUTO_EVERY_S = 3 * 86_400L
    }
}
