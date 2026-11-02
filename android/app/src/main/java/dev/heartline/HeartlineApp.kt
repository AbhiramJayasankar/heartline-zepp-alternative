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

class HeartlineApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val db by lazy { HeartlineDatabase.get(this) }
    val settings by lazy { AppSettings(this) }
    val keys by lazy { KeyStore(this) }
    val watchStore by lazy { WatchStore(db, settings) }
    val realController by lazy { RealController(this, scope, db, settings, keys) }
    lateinit var controller: HeartlineController
        private set

    override fun onCreate() {
        super.onCreate()
        dev.heartline.ble.BleLog.init(filesDir)
        controller = realController
    }

    fun selectController(demo: Boolean): HeartlineController {
        controller = if (debuggable && demo) DemoController(scope) else realController
        return controller
    }
    val debuggable: Boolean get() = applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
}
