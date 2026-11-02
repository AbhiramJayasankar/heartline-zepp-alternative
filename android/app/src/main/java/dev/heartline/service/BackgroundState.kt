package dev.heartline.service

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import dev.heartline.app.BackgroundUi

internal fun isMiui(manufacturer: String, brand: String): Boolean = listOf(manufacturer, brand)
    .any { value -> value.lowercase(java.util.Locale.ROOT) in setOf("xiaomi", "redmi", "poco") }

internal fun backgroundState(context: Context): BackgroundUi {
    val listener = ComponentName(context, NotificationForwarder::class.java)
    val enabled = runCatching { Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") }
        .getOrNull().orEmpty().split(':').mapNotNull(ComponentName::unflattenFromString).contains(listener)
    return BackgroundUi(
        batteryUnrestricted = runCatching { context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName) }.getOrDefault(false),
        notificationAccess = enabled,
        miui = isMiui(Build.MANUFACTURER, Build.BRAND),
        bluetoothOn = runCatching {
            if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) false
            else context.getSystemService(BluetoothManager::class.java).adapter?.isEnabled == true
        }.getOrDefault(false),
        postNotifications = Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
        companionLinked = runCatching { CompanionLink.linked(context, (context.applicationContext as dev.heartline.HeartlineApp).settings.watch.value.mac) }.getOrDefault(false),
    )
}
