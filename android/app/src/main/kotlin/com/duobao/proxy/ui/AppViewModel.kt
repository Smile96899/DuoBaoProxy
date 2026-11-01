package com.duobao.proxy.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.duobao.proxy.DuoBaoApplication
import com.duobao.proxy.data.asString
import com.duobao.proxy.state.AppController
import com.duobao.proxy.ui.components.UiMessages
import com.duobao.proxy.ui.theme.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/** Share-link schemes a pasted or opened text may start with (`go/node/link.go`). */
val LINK_SCHEMES = listOf(
    "vmess://", "vless://", "ss://", "ssr://", "trojan://", "hysteria2://", "hy2://", "hysteria://", "tuic://",
    "socks://", "socks4://", "socks4a://", "socks5://", "naive+https://", "naive+quic://", "anytls://", "wireguard://", "wg://",
)

/** Whether [text] looks importable: a share link, or a single http(s) subscription URL. */
fun looksImportable(text: String): Boolean {
    val t = text.trim()
    if (t.isEmpty()) return false
    return LINK_SCHEMES.any { t.startsWith(it, ignoreCase = true) } || Regex("""^https?://\S+$""", RegexOption.IGNORE_CASE).matches(t)
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    val store = (app as DuoBaoApplication).store
    private val controller get() = AppController.instance

    val themeMode: StateFlow<ThemeMode> = store.ui
        .map(::themeOf)
        .stateIn(viewModelScope, SharingStarted.Eagerly, themeOf(store.ui.value))

    private fun themeOf(ui: JsonObject) = ThemeMode.from(ui["theme"].asString())

    /** Text waiting for the user's OK to import (share intent, link opened, clipboard detection). */
    private val _pendingImport = MutableStateFlow<PendingImport?>(null)
    val pendingImport: StateFlow<PendingImport?> = _pendingImport.asStateFlow()

    data class PendingImport(val text: String, val source: String)

    /** `(name, link)` pairs the QR dialog shows. */
    private val _qr = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val qr: StateFlow<List<Pair<String, String>>> = _qr.asStateFlow()

    private var lastClip = ""

    fun offerImport(text: String, source: String) {
        if (looksImportable(text) || text.lines().any { looksImportable(it) }) _pendingImport.value = PendingImport(text.trim(), source)
        else UiMessages.show("没有可导入的链接")
    }

    /** 自动检测剪贴板: offers the clipboard once per distinct content. */
    fun checkClipboard(text: String?) {
        val t = text?.trim().orEmpty()
        if (t.isEmpty() || t == lastClip || !controller.prefs.clipDetect) return
        lastClip = t
        if (t.lines().any { looksImportable(it) }) _pendingImport.value = PendingImport(t, "剪贴板")
    }

    fun dismissImport() {
        _pendingImport.value = null
    }

    /** Imports [text] (links, base64, Clash, or a subscription URL) and reports in the snackbar. */
    fun importNow(text: String, group: String? = null) {
        _pendingImport.value = null
        viewModelScope.launch {
            val r = if (group != null) controller.importText(text, group) else controller.importText(text)
            UiMessages.show(r.message)
        }
    }

    fun showQr(items: List<Pair<String, String>>) {
        _qr.value = items
    }

    fun closeQr() {
        _qr.value = emptyList()
    }
}
