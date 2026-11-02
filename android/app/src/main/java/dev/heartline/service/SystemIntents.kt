package dev.heartline.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.net.toUri

internal fun <T> firstResolvable(candidates: List<T>, resolves: (T) -> Boolean): T? = candidates.firstOrNull {
    runCatching { resolves(it) }.getOrDefault(false)
}

/** Null means the device exposes no settings Activity, including the generic Settings fallback. */
object SystemIntents {
    private fun first(context: Context, vararg intents: Intent): Intent? =
        firstResolvable(intents.toList() + Intent(Settings.ACTION_SETTINGS)) { it.resolveActivity(context.packageManager) != null }
    private fun details(context: Context) = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())
    fun appDetails(context: Context): Intent? = first(context, details(context))
    fun batteryExemption(context: Context): Intent? = first(context,
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:${context.packageName}".toUri()),
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS), details(context))
    fun miuiAutostart(context: Context): Intent? = first(context,
        Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")), details(context))
    fun miuiBatterySaver(context: Context): Intent? = first(context,
        Intent("miui.intent.action.POWER_HIDE_MODE_APP_LIST").setComponent(ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"))
            .putExtra("package_name", context.packageName).putExtra("package_label", runCatching {
                context.applicationInfo.loadLabel(context.packageManager).toString()
            }.getOrDefault(context.packageName)),
        details(context))
    fun notificationListenerSettings(context: Context): Intent? = first(context, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS), details(context))
}
