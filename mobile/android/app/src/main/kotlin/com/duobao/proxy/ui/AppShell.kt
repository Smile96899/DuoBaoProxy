package com.duobao.proxy.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.duobao.proxy.ui.screens.PlaceholderScreen
import com.duobao.proxy.ui.theme.DbpTheme

@Composable
fun AppShell() {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    Scaffold(
        containerColor = DbpTheme.colors.bg,
        bottomBar = { BottomBar(entry?.destination, nav) },
    ) { padding ->
        NavHost(nav, startDestination = NodesRoute, modifier = Modifier.padding(padding)) {
            composable<NodesRoute> { PlaceholderScreen(Destination.Nodes) }
            composable<GroupsRoute> { PlaceholderScreen(Destination.Groups) }
            composable<RoutesRoute> { PlaceholderScreen(Destination.Routes) }
            composable<LogsRoute> { PlaceholderScreen(Destination.Logs) }
            composable<SettingsRoute> { PlaceholderScreen(Destination.Settings) }
        }
    }
}

/** The portrait tab bar: `railPanel2` with a `railLine` top edge and a `railPanel` pill on the current tab. */
@Composable
private fun BottomBar(current: NavDestination?, nav: NavHostController) {
    val c = DbpTheme.colors
    val itemColors = NavigationBarItemDefaults.colors(
        selectedIconColor = c.railFg,
        selectedTextColor = c.railFg,
        indicatorColor = c.railPanel,
        unselectedIconColor = c.railFg3,
        unselectedTextColor = c.railFg3,
    )
    Column {
        HorizontalDivider(thickness = 1.dp, color = c.railLine)
        NavigationBar(containerColor = c.railPanel2, tonalElevation = 0.dp) {
            for (d in Destination.entries) {
                val selected = current?.hierarchy?.any { it.hasRoute(d.route::class) } == true
                NavigationBarItem(
                    selected = selected,
                    onClick = { nav.navigateTab(d.route) },
                    icon = { Icon(painterResource(d.icon), contentDescription = null) },
                    label = {
                        Text(
                            stringResource(d.label),
                            fontSize = 12.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    },
                    colors = itemColors,
                )
            }
        }
    }
}

private fun NavHostController.navigateTab(route: Any) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}
