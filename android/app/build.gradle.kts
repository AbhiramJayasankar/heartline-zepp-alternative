plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    // local.properties is per-machine and gitignored; its path syntax is not ours to lint.
    lint { disable += "PropertyEscape" }
    namespace = "dev.heartline"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.heartline"
        minSdk = 31
        // Vosk ships native code for six CPU types; keep the phone's (arm64) and the emulator's (x86_64).
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }
}

dependencies {
    implementation(project(":core:protocol"))
    implementation(project(":core:analytics"))
    implementation(project(":core:data"))
    implementation(project(":core:ble"))

    val bom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(bom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    // First stable release, 2025-10-08; no experimental APIs used.
    implementation("androidx.health.connect:connect-client:1.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    // Offline speech-to-text for the watch's Alexa button (Apache 2.0). Android's own recognizer returns nothing for recorded audio.
    implementation("com.alphacephei:vosk-android:0.3.47")
    implementation("net.java.dev.jna:jna:5.13.0@aar")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
}

// The Vosk small English model (Apache 2.0, ~40 MB) goes into the app's assets but not into git: fetch it when missing.
val voskModel = layout.projectDirectory.dir("src/main/assets/vosk-en")
val fetchVoskModel by tasks.registering {
    onlyIf { !voskModel.file("am/final.mdl").asFile.exists() }
    doLast {
        val zip = layout.buildDirectory.file("vosk-model-small-en-us-0.15.zip").get().asFile
        zip.parentFile.mkdirs()
        uri("https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip").toURL().openStream().use { input -> zip.outputStream().use { input.copyTo(it) } }
        copy {
            from(zipTree(zip)) { eachFile { path = path.substringAfter('/') }; includeEmptyDirs = false }
            into(voskModel)
        }
    }
}
tasks.named("preBuild") { dependsOn(fetchVoskModel) }
