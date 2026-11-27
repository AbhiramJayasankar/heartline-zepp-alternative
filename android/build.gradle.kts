plugins {
    id("com.android.application") version "9.4.1" apply false
    id("com.android.library") version "9.4.1" apply false
    id("org.jetbrains.kotlin.jvm") version "2.4.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
}

// Every module's unit tests plus lint: what CI runs and what to run before a commit. testDebugUnitTest alone skips
// the pure Kotlin modules (core/analytics, core/protocol), which only have `test`.
tasks.register("verify") {
    group = "verification"
    description = "Runs every module's unit tests and the app's lint."
    dependsOn(":app:testDebugUnitTest", ":app:lintDebug", ":core:ble:testDebugUnitTest", ":core:data:testDebugUnitTest",
        ":core:analytics:test", ":core:protocol:test")
}
