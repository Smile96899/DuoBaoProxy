package com.duobao.proxy.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.duobao.proxy.R
import kotlinx.serialization.Serializable

@Serializable data object NodesRoute
@Serializable data object GroupsRoute
@Serializable data object RoutesRoute
@Serializable data object LogsRoute
@Serializable data object SettingsRoute

/** Bottom-bar order of the desktop rail / portrait tab bar: 节点 · 分组 · 路由 · 日志 · 设置. */
enum class Destination(val route: Any, @StringRes val label: Int, @DrawableRes val icon: Int) {
    Nodes(NodesRoute, R.string.nav_nodes, R.drawable.ic_nav_nodes),
    Groups(GroupsRoute, R.string.nav_groups, R.drawable.ic_nav_groups),
    Routes(RoutesRoute, R.string.nav_routes, R.drawable.ic_nav_routes),
    Logs(LogsRoute, R.string.nav_logs, R.drawable.ic_nav_logs),
    Settings(SettingsRoute, R.string.nav_settings, R.drawable.ic_nav_settings),
}
