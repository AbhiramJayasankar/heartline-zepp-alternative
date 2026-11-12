package dev.heartline.ui

import dev.heartline.ui.theme.raised

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
    /** A-GPS for the watch's GPS. */
    data object Agps : Route
    data object CheckRun : Route
    data class CheckResult(val id: String) : Route
    data class Workout(val start: Long, val epochDay: Long) : Route
    data object Sensors : Route
    data object WatchSettings : Route
    data object Notes : Route
    /** How readiness and the other Today scores are worked out; [focus] scrolls to one factor or score. */
    data class Readiness(val focus: String? = null) : Route
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
    /** A capture's analysis; [focus] is an exercise name to scroll to. */
    data class CaptureResult(val id: String, val focus: String? = null) : Route
    /** A day detail page; [metric] is a DayMetric name. */
    data class Day(val metric: String, val epochDay: Long) : Route
    data class Trend(val key: String) : Route
    data object Trends : Route
    data object Insights : Route
    /** Heartline's AI providers, keys, models and each feature's fallbacks. */
    data object AiModels : Route
    /** A page under AI models (AiPages). */
    data class AiPage(val page: String) : Route
    data object SendNotification : Route
    data object GymLog : Route
    /** The gym log opened on one session ("date|name"), scrolled to it: a link from a recording's exercise. */
    data class GymLogAt(val session: String) : Route
}

class Navigator {
    val stack = mutableStateListOf<Route>()
    fun push(r: Route) { stack.add(r) }
    fun pop() { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) }
    fun replace(r: Route) { pop(); push(r) }
    /** Set by [AppRoot]: switches the bottom tab. */
    internal var tabSwitch: (Tab) -> Unit = {}
    /** A section the shown tab should scroll to and outline (see [dev.heartline.ui.screens.section]); cleared once done. */
    var focus by androidx.compose.runtime.mutableStateOf<String?>(null)
    /** Set by [AppRoot]: the bottom tab showing now. */
    internal var currentTab: () -> Tab = { Tab.TODAY }
    /** Where Back goes after a returnable [toTab]: the screens that were open and the tab under them. */
    private var returnTo by androidx.compose.runtime.mutableStateOf<Pair<List<Route>, Tab>?>(null)
    val canReturn: Boolean get() = returnTo != null && stack.isEmpty()
    /** Kept here so the Trends page comes back where you left it. */
    val trendsScroll = androidx.compose.foundation.ScrollState(0)

    /**
     * Closes any pushed screens and shows [t], scrolled to [focus] if given. With [returnable], Back from that tab
     * reopens the screens that were open (a Trends card, say), instead of leaving the app.
     */
    fun toTab(t: Tab, focus: String? = null, returnable: Boolean = false) {
        returnTo = if (returnable && stack.isNotEmpty()) stack.toList() to currentTab() else null
        stack.clear(); this.focus = focus; tabSwitch(t)
    }
    fun returnBack() { val r = returnTo ?: return; returnTo = null; focus = null; tabSwitch(r.second); stack.addAll(r.first) }
    /** Choosing a tab yourself forgets the way back. */
    fun forgetReturn() { returnTo = null; focus = null }
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
    nav.tabSwitch = { tab = it }
    nav.currentTab = { tab }
    BackHandler(enabled = nav.stack.isNotEmpty()) { nav.pop() }
    BackHandler(enabled = nav.canReturn) { nav.returnBack() }
    // Back on any other tab goes to Today first, rather than leaving the app.
    BackHandler(enabled = nav.stack.isEmpty() && !nav.canReturn && tab != Tab.TODAY) { tab = Tab.TODAY }
    val c = HL.colors
    val top = nav.stack.lastOrNull()
    val tabScroll = remember { Tab.entries.associateWith { androidx.compose.foundation.ScrollState(0) } }
    // Each tab, and each screen in the back stack, keeps its own state (the day it shows, how far it is scrolled, what is
    // open) while another screen is on top; Back returns to it as it was. A screen's state goes when it is closed.
    val saved = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    val routeKeys = remember { mutableSetOf<String>() }
    val liveKeys = nav.stack.mapIndexed { i, r -> "route:$i:$r" }.toSet()
    androidx.compose.runtime.SideEffect {
        (routeKeys - liveKeys).forEach { saved.removeState(it) }
        routeKeys.retainAll(liveKeys); routeKeys += liveKeys
    }

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
                            onClick = { nav.forgetReturn(); tab = t },
                            icon = { Icon(t.icon, contentDescription = null, modifier = Modifier.size(24.dp)) },
                            alwaysShowLabel = true,
                            label = { Text(t.label, style = HL.type.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = c.pulse, selectedTextColor = c.ink, indicatorColor = c.raised,
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
            val ringing by controller.phoneLink.collectAsStateWithLifecycle()
            val capturing by controller.captureRun.collectAsStateWithLifecycle()
            val checking by controller.hrvCheck.collectAsStateWithLifecycle()
            val covered = ringing.findPhoneRinging || (capturing != null && top != Route.Capture) || checking != null
            dev.heartline.ui.screens.FindPhoneBanner(controller)
            if (top != Route.Capture) dev.heartline.ui.screens.CaptureBanner(controller) { nav.push(Route.Capture) }
            dev.heartline.ui.screens.HrvCheckBanner(controller) { night -> nav.stack.clear(); tab = if (night) Tab.SLEEP else Tab.HEART }
            androidx.compose.runtime.CompositionLocalProvider(dev.heartline.ui.screens.LocalStatusBarCovered provides covered) {
            AnimatedContent(targetState = top ?: tab, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "screen") { target ->
                val stateKey = if (target is Tab) "tab:${target.name}" else "route:${nav.stack.lastIndexOf(target).coerceAtLeast(0)}:$target"
                saved.SaveableStateProvider(stateKey) {
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
                        Route.AiModels -> dev.heartline.ui.screens.AiModelsScreen(controller, nav)
                        is Route.AiPage -> dev.heartline.ui.screens.AiPageScreen(controller, target.page, nav)
                        Tab.WATCH -> dev.heartline.ui.screens.WatchHubScreen(controller, nav)
                        Route.SendNotification -> dev.heartline.ui.screens.SendNotificationScreen(controller, nav)
                        Route.CheckRun -> CheckRunScreen(controller, nav)
                        is Route.CheckResult -> CheckResultScreen(controller, target.id, nav)
                        is Route.Workout -> WorkoutScreen(controller, target, nav)
                        Route.GymLog -> dev.heartline.ui.screens.GymLogScreen(controller, nav)
                        is Route.GymLogAt -> dev.heartline.ui.screens.GymLogScreen(controller, nav, target.session)
                        Route.Sensors -> SensorsScreen(controller, nav)
                        Route.Agps -> dev.heartline.ui.screens.AgpsScreen(controller, nav)
                        Route.WatchSettings -> WatchSettingsScreen(controller, nav)
                        Route.Notes -> dev.heartline.ui.screens.NotesScreen(controller, nav)
                        is Route.Readiness -> dev.heartline.ui.screens.ReadinessScreen(controller, target.focus, nav)
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
                        is Route.CaptureResult -> dev.heartline.ui.screens.CaptureResultScreen(controller, target.id, nav, target.focus)
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
    }
}
