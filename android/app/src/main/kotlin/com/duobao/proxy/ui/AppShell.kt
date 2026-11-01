package com.duobao.proxy.ui

import com.duobao.proxy.core.tr
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import com.duobao.proxy.ui.components.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.duobao.proxy.ui.components.DbpIcon
import com.duobao.proxy.ui.components.UiMessages
import com.duobao.proxy.ui.screens.GlobalOverlays
import com.duobao.proxy.ui.screens.groups.GroupsScreen
import com.duobao.proxy.ui.screens.home.HomeScreen
import com.duobao.proxy.ui.screens.home.NodeEditorScreen
import com.duobao.proxy.ui.screens.rules.AppsScreen
import com.duobao.proxy.ui.screens.rules.RulesScreen
import com.duobao.proxy.ui.screens.settings.AboutScreen
import com.duobao.proxy.ui.screens.settings.BackupScreen
import com.duobao.proxy.ui.screens.settings.CoreScreen
import com.duobao.proxy.ui.screens.settings.DiagnoseScreen
import com.duobao.proxy.ui.screens.settings.DnsScreen
import com.duobao.proxy.ui.screens.settings.LatencyScreen
import com.duobao.proxy.ui.screens.settings.LogsScreen
import com.duobao.proxy.ui.screens.settings.InfoScreen
import com.duobao.proxy.ui.screens.settings.LocalNav
import androidx.compose.runtime.CompositionLocalProvider
import com.duobao.proxy.ui.screens.settings.NotifyScreen
import com.duobao.proxy.ui.screens.settings.SettingsScreen
import com.duobao.proxy.ui.screens.settings.SubUpdateScreen
import com.duobao.proxy.ui.screens.subs.SubEditScreen
import com.duobao.proxy.ui.screens.subs.SubsScreen
import com.duobao.proxy.ui.theme.DbpTheme

@Composable
fun AppShell(vm: AppViewModel) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val c = DbpTheme.colors
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { UiMessages.flow.collect { snack.showSnackbar(tr(it)) } }
    val onTab = Destination.entries.any { d -> entry?.destination?.hierarchy?.any { it.hasRoute(d.route::class) } == true }
    val back: () -> Unit = { nav.popBackStack() }
    Scaffold(
        containerColor = c.bg,
        bottomBar = { if (onTab) BottomBar(entry?.destination, nav) },
        snackbarHost = { SnackbarHost(snack) { Snackbar(it, containerColor = c.fg, contentColor = c.panel) } },
    ) { padding ->
        CompositionLocalProvider(LocalNav provides nav) {
        NavHost(nav, startDestination = HomeRoute, modifier = Modifier.padding(padding)) {
            composable<HomeRoute> { HomeScreen(nav, vm) }
            composable<SubsRoute> { SubsScreen(nav) }
            composable<RulesRoute> { RulesScreen(nav) }
            composable<SettingsRoute> { SettingsScreen(nav) }
            composable<GroupsRoute> { GroupsScreen(back) }
            composable<SubEditRoute> { SubEditScreen(it.toRoute<SubEditRoute>(), back) }
            composable<NodeEditRoute> { NodeEditorScreen(it.toRoute<NodeEditRoute>(), back) }
            composable<AppsRoute> { AppsScreen(back) }
            composable<DnsRoute> { DnsScreen(back) }
            composable<CoreRoute> { CoreScreen(back) }
            composable<BackupRoute> { BackupScreen(back) }
            composable<SubUpdateRoute> { SubUpdateScreen(back) }
            composable<LatencyRoute> { LatencyScreen(back) }
            composable<NotifyRoute> { NotifyScreen(back) }
            composable<LogsRoute> { LogsScreen(back) }
            composable<DiagnoseRoute> { DiagnoseScreen(back) }
            composable<AboutRoute> { AboutScreen(back) }
            composable<InfoRoute> { InfoScreen(it.toRoute<InfoRoute>().key, back) }
        }
        }
        GlobalOverlays(vm, nav)
    }
}

/** The tab bar: white (dark: panel) with a top line; the current tab is accent, others fg2. */
@Composable
private fun BottomBar(current: NavDestination?, nav: NavHostController) {
    val c = DbpTheme.colors
    Column(Modifier.background(c.navBg)) {
        HorizontalDivider(thickness = 1.dp, color = c.line)
        Row(Modifier.fillMaxWidth().navigationBarsPadding().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
            for (d in Destination.entries) {
                val selected = current?.hierarchy?.any { it.hasRoute(d.route::class) } == true
                val tint = if (selected) c.accent else c.fg2
                Column(
                    Modifier
                        .weight(1f)
                        .clickable(remember { MutableInteractionSource() }, indication = null) { nav.navigateTab(d.route) }
                        .padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    DbpIcon(d.icon, tint, size = 24.dp)
                    Text(d.label, color = tint, fontSize = 12.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
        }
    }
}

private fun NavHostController.navigateTab(route: Any) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}
