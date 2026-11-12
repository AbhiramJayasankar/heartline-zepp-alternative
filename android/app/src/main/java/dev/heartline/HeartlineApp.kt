package dev.heartline

import android.app.Application
import dev.heartline.app.DemoController
import dev.heartline.app.HeartlineController
import dev.heartline.app.RealController
import dev.heartline.app.WatchStore
import dev.heartline.data.AppSettings
import dev.heartline.data.HeartlineDatabase
import dev.heartline.data.KeyStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class HeartlineApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + dev.heartline.ble.CrashGuard.handler)
    /** For writing a background failure to the activity log. */
    private val logScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val db by lazy { HeartlineDatabase.get(this) }
    val settings by lazy { AppSettings(this) }
    val keys by lazy { KeyStore(this) }
    val watchStore by lazy { WatchStore(db, settings) }
    /** Heartline's AI providers, keys, models and the router every AI feature shares. */
    val ai by lazy { dev.heartline.service.ai.AiStore({ db.meta.get(it) }, { k, v -> db.meta.set(k, v) }) }
    val realController by lazy { RealController(this, scope, db, settings, keys) }
    lateinit var controller: HeartlineController
        private set

    override fun onCreate() {
        super.onCreate()
        dev.heartline.app.perfLogging = debuggable
        dev.heartline.ble.BleLog.init(filesDir)
        dev.heartline.ble.CrashGuard.report = { e ->
            logScope.launch { runCatching { db.events.add("error", "Background task failed: ${e.message ?: e.javaClass.simpleName}") } }
        }
        controller = realController
    }

    fun selectController(demo: Boolean): HeartlineController {
        controller = if (debuggable && demo) DemoController(scope) else realController
        return controller
    }
    val debuggable: Boolean get() = applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
}
