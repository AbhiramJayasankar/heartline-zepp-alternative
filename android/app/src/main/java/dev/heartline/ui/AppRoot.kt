package dev.heartline.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.heartline.app.HeartlineController
import dev.heartline.ui.screens.ActivityScreen
import dev.heartline.ui.screens.CheckResultScreen
import dev.heartline.ui.screens.CheckRunScreen
import dev.heartline.ui.screens.DataScreen
import dev.heartline.ui.screens.EventsScreen
import dev.heartline.ui.screens.ForwardingScreen
import dev.heartline.ui.screens.HeartScreen
import dev.heartline.ui.screens.OnboardingScreen
import dev.heartline.ui.screens.PreferencesScreen
import dev.heartline.ui.screens.ProfileScreen
import dev.heartline.ui.screens.SensorsScreen
import dev.heartline.ui.screens.TrendsScreen
import dev.heartline.ui.screens.WatchSettingsScreen
import dev.heartline.ui.screens.WatchMenuScreen
import dev.heartline.ui.screens.VibrationScreen
import dev.heartline.ui.screens.WorkoutScreen
import dev.heartline.ui.theme.HL

enum class Tab(val label: String, val icon: ImageVector) {
    TODAY("Today", Icons.today), SLEEP("Sleep", Icons.sleep), HEART("Heart", Icons.heart), ACTIVITY("Activity", Icons.activity),
    WATCH("Watch", Icons.watch),
}

/** Screens pushed on top of a tab. */
sealed interface Route {
    data object CheckRun : Route
    data class CheckResult(val id: String) : Route
    data class Workout(val start: Long, val epochDay: Long) : Route
    data object Sensors : Route
    data object WatchSettings : Route
    data object WatchMenu : Route
    data object Vibration : Route
    data object Alexa : Route
    /** A page of the Alexa settings (AlexaPages). */
    data class AlexaPage(val page: String) : Route
    /** A question in the conversation log, by its time in epoch milliseconds. */
    data class AlexaTurn(val at: Long) : Route
    data class AlexaTool(val name: String) : Route
    data object Forwarding : Route
    data object Data : Route
    data object Profile : Route
    data object Preferences : Route
    data object Events : Route
    data object KeepRunning : Route
    data object Weather : Route
    data object Alarms : Route
    data object Reminders : Route
    data object PhoneLink : Route
    data object HealthConnect : Route
    data object Capture : Route
    data class CaptureResult(val id: String) : Route
    /** A day detail page; [metric] is a DayMetric name. */
    data class Day(val metric: String, val epochDay: Long) : Route
    data class Trend(val key: String) : Route
    data object Trends : Route
    data object Insights : Route
    data object SendNotification : Route
}

class Navigator {
    val stack = mutableStateListOf<Route>()
    fun push(r: Route) { stack.add(r) }
    fun pop() { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) }
    fun replace(r: Route) { pop(); push(r) }
}

@Composable
fun AppRoot(controller: HeartlineController) {
    val prefs by controller.prefs.collectAsStateWithLifecycle()
    if (!prefs.onboarded) {
        OnboardingScreen(controller)
        return
    }
    var tab by rememberSaveable { mutableStateOf(Tab.TODAY) }
    val nav = remember { Navigator() }
    BackHandler(enabled = nav.stack.isNotEmpty()) { nav.pop() }
    val c = HL.colors
    val top = nav.stack.lastOrNull()
    val tabScroll = remember { Tab.entries.associateWith { androidx.compose.foundation.ScrollState(0) } }

    Scaffold(
        containerColor = c.paper,
        // Screens draw behind the status bar themselves (full-bleed headers); only the bottom bar is inset here.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (top == null) {
                NavigationBar(containerColor = c.paper, tonalElevation = 0.dp) {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = tab == t,
                            onClick = { tab = t },
                            icon = { Icon(t.icon, contentDescription = null, modifier = Modifier.size(24.dp)) },
                            alwaysShowLabel = true,
                            label = { Text(t.label, style = HL.type.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = c.pulse, selectedTextColor = c.ink, indicatorColor = c.wash,
                                unselectedIconColor = c.inkSoft, unselectedTextColor = c.inkSoft,
                            ),
                        )
                    }
                }
            }
        },
    ) { inner ->
        Box(Modifier.fillMaxSize().background(c.paper).padding(inner).then(if (top != null) Modifier.navigationBarsPadding() else Modifier)) {
            Column(Modifier.fillMaxSize()) {
            dev.heartline.ui.screens.FindPhoneBanner(controller)
            if (top != Route.Capture) dev.heartline.ui.screens.CaptureBanner(controller) { nav.push(Route.Capture) }
            AnimatedContent(targetState = top ?: tab, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "screen") { target ->
                Column(Modifier.fillMaxSize()) {
                    val syncUi by controller.sync.collectAsStateWithLifecycle()
                    val link by controller.connection.collectAsStateWithLifecycle()
                    // Pulling down a tab syncs the watch, or reconnects it first when the link is down.
                    val pageRefresh = (target as? Tab)?.let { dev.heartline.ui.screens.PageRefresh(syncUi.running) {
                        if (link.state == dev.heartline.app.LinkState.CONNECTED) controller.syncNow() else controller.connect()
                    } }
                    androidx.compose.runtime.CompositionLocalProvider(dev.heartline.ui.screens.LocalTabScroll provides (target as? Tab)?.let { tabScroll[it] },
                        dev.heartline.ui.screens.LocalPageRefresh provides pageRefresh) {
                    when (target) {
                        Tab.TODAY -> dev.heartline.ui.screens.OverviewScreen(controller, nav) { tab = it }
                        Tab.SLEEP -> dev.heartline.ui.screens.SleepScreen(controller, nav)
                        Tab.HEART -> HeartScreen(controller, nav)
                        Tab.ACTIVITY -> ActivityScreen(controller, nav)
                        Route.Trends -> TrendsScreen(controller, nav) { nav.pop() }
                        Route.Insights -> dev.heartline.ui.screens.InsightsScreen(controller, nav)
                        Tab.WATCH -> dev.heartline.ui.screens.WatchHubScreen(controller, nav)
                        Route.SendNotification -> dev.heartline.ui.screens.SendNotificationScreen(controller, nav)
                        Route.CheckRun -> CheckRunScreen(controller, nav)
                        is Route.CheckResult -> CheckResultScreen(controller, target.id, nav)
                        is Route.Workout -> WorkoutScreen(controller, target, nav)
                        Route.Sensors -> SensorsScreen(controller, nav)
                        Route.WatchSettings -> WatchSettingsScreen(controller, nav)
                        Route.WatchMenu -> WatchMenuScreen(controller, nav)
                        Route.Vibration -> VibrationScreen(controller, nav)
                        Route.Alexa -> dev.heartline.ui.screens.AlexaScreen(controller, nav)
                        is Route.AlexaPage -> dev.heartline.ui.screens.AlexaPageScreen(controller, target.page, nav)
                        is Route.AlexaTurn -> dev.heartline.ui.screens.AlexaTurnScreen(controller, target.at, nav)
                        is Route.AlexaTool -> dev.heartline.ui.screens.AlexaToolScreen(controller, target.name, nav)
                        Route.Forwarding -> ForwardingScreen(controller, nav)
                        Route.Data -> DataScreen(controller, nav)
                        Route.Profile -> ProfileScreen(controller, nav)
                        Route.Preferences -> PreferencesScreen(controller, nav)
                        Route.Events -> EventsScreen(controller, nav)
                        Route.KeepRunning -> dev.heartline.ui.screens.KeepRunningScreen(controller, nav)
                        Route.Weather -> dev.heartline.ui.screens.WeatherScreen(controller, nav)
                        Route.Alarms -> dev.heartline.ui.screens.AlarmsScreen(controller, nav)
                        Route.Reminders -> dev.heartline.ui.screens.RemindersScreen(controller, nav)
                        Route.PhoneLink -> dev.heartline.ui.screens.PhoneLinkScreen(controller, nav)
                        Route.HealthConnect -> dev.heartline.ui.screens.HealthConnectScreen(controller, nav)
                        Route.Capture -> dev.heartline.ui.screens.CaptureScreen(controller, nav)
                        is Route.CaptureResult -> dev.heartline.ui.screens.CaptureResultScreen(controller, target.id, nav)
                        is Route.Trend -> dev.heartline.ui.screens.TrendDetailScreen(controller, target.key, nav)
                        is Route.Day -> dev.heartline.ui.screens.DayScreen(controller, dev.heartline.ui.screens.DayMetric.valueOf(target.metric), target.epochDay, nav)
                        else -> Unit
                    }
                    }
                }
            }
            }
        }
    }
}
