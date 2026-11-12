import java.util.Properties

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
        // Keep native code for the phone (arm64) and the emulator (x86_64) only.
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        targetSdk = 36
        versionCode = 7
        versionName = "0.6.0"
    }

    // A release key, when android/keystore.properties names one (storeFile, storePassword, keyAlias, keyPassword; never in
    // git). Without it the release build is signed with the debug key, which phones running the debug build can update over.
    val releaseKey = rootProject.file("keystore.properties").takeIf { it.isFile }?.let { f -> Properties().apply { f.inputStream().use { load(it) } } }
    signingConfigs {
        if (releaseKey != null) create("release") {
            storeFile = rootProject.file(releaseKey.getProperty("storeFile"))
            storePassword = releaseKey.getProperty("storePassword")
            keyAlias = releaseKey.getProperty("keyAlias")
            keyPassword = releaseKey.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName(if (releaseKey != null) "release" else "debug")
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
    // WebSocket client for Gemini's Live transcription (the Alexa button's speech-to-text).
    implementation("com.squareup.okhttp3:okhttp:5.5.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
}
