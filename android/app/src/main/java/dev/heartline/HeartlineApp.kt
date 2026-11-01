package dev.heartline

import android.app.Application
import dev.heartline.app.DemoController
import dev.heartline.app.HeartlineController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class HeartlineApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Demo data until the BLE, data and analytics modules are wired in. */
    lateinit var controller: HeartlineController
        private set

    override fun onCreate() {
        super.onCreate()
        controller = DemoController(scope)
    }
}
