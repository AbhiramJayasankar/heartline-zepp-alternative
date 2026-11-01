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
import dev.heartline.ui.screens.TodayScreen
import dev.heartline.ui.screens.TrendsScreen
import dev.heartline.ui.screens.WatchScreen
import dev.heartline.ui.screens.WatchSettingsScreen
import dev.heartline.ui.screens.WorkoutScreen
import dev.heartline.ui.theme.HL

enum class Tab(val label: String, val icon: ImageVector) {
    TODAY("Today", Icons.today), HEART("Heart", Icons.heart), ACTIVITY("Activity", Icons.activity),
    TRENDS("Trends", Icons.trends), WATCH("Watch", Icons.watch),
}

/** Screens pushed on top of a tab. */
sealed interface Route {
    data object CheckRun : Route
    data class CheckResult(val id: String) : Route
    data class Workout(val start: Long, val epochDay: Long) : Route
    data object Sensors : Route
    data object WatchSettings : Route
    data object Forwarding : Route
    data object Data : Route
    data object Profile : Route
    data object Preferences : Route
    data object Events : Route
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
            AnimatedContent(targetState = top ?: tab, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "screen") { target ->
                Column(Modifier.fillMaxSize()) {
                    when (target) {
                        Tab.TODAY -> TodayScreen(controller, onOpenHeart = { tab = Tab.HEART }, onOpenActivity = { tab = Tab.ACTIVITY })
                        Tab.HEART -> HeartScreen(controller, nav)
                        Tab.ACTIVITY -> ActivityScreen(controller, nav)
                        Tab.TRENDS -> TrendsScreen(controller)
                        Tab.WATCH -> WatchScreen(controller, nav)
                        Route.CheckRun -> CheckRunScreen(controller, nav)
                        is Route.CheckResult -> CheckResultScreen(controller, target.id, nav)
                        is Route.Workout -> WorkoutScreen(controller, target, nav)
                        Route.Sensors -> SensorsScreen(controller, nav)
                        Route.WatchSettings -> WatchSettingsScreen(controller, nav)
                        Route.Forwarding -> ForwardingScreen(controller, nav)
                        Route.Data -> DataScreen(controller, nav)
                        Route.Profile -> ProfileScreen(controller, nav)
                        Route.Preferences -> PreferencesScreen(controller, nav)
                        Route.Events -> EventsScreen(controller, nav)
                        else -> Unit
                    }
                }
            }
        }
    }
}
