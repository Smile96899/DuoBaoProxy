package com.duobao.proxy.ui

import kotlinx.serialization.Serializable

@Serializable data object HomeRoute
@Serializable data object SubsRoute
@Serializable data object RulesRoute
@Serializable data object SettingsRoute

/** 管理分组. */
@Serializable data object GroupsRoute

/** 添加订阅 / 编辑订阅 ([name] empty = new; [url] prefills the link). */
@Serializable data class SubEditRoute(val name: String = "", val url: String = "")

/** 添加节点 ([protocol]) / 编辑节点 ([id] ≥ 0). */
@Serializable data class NodeEditRoute(val id: Int = -1, val protocol: String = "")

@Serializable data object AppsRoute
@Serializable data object DnsRoute
@Serializable data object CoreRoute
@Serializable data object BackupRoute
@Serializable data object SubUpdateRoute
@Serializable data object LatencyRoute
@Serializable data object NotifyRoute
@Serializable data object LogsRoute
@Serializable data object DiagnoseRoute
@Serializable data object AboutRoute
/** 设置 → a switch's 说明 page (开启后 / 关闭后). */
@Serializable data class InfoRoute(val key: String)

/** The bottom tabs of the design: 首页 · 订阅 · 规则 · 设置. */
enum class Destination(val route: Any, val label: String, val icon: String) {
    Home(HomeRoute, "首页", "home"),
    Subs(SubsRoute, "订阅", "cloud"),
    Rules(RulesRoute, "规则", "rules"),
    Settings(SettingsRoute, "设置", "settings"),
}
