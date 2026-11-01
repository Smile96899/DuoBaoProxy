package com.duobao.proxy.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/**
 * xray's `geoip.dat` / `geosite.dat` in `filesDir/geo` (the `AssetPath` of Setup). They are not
 * bundled (DESIGN §0): [ensure] downloads the missing ones on demand from [BASE_URL], which is only
 * needed when an xray config uses `geosite:` / `geoip:` rules (sing-box fetches its own `.srs`).
 *
 * The app's own package is outside the VPN, so a plain [HttpURLConnection] goes direct.
 */
class GeoData(context: Context) {
    /** Download progress; [Idle] when nothing runs. */
    sealed interface State {
        data object Idle : State

        /** [file] is being fetched; [total] is -1 when the server sends no length. */
        data class Downloading(val file: String, val done: Long, val total: Long) : State

        data class Failed(val file: String, val error: String) : State
    }

    val dir = File(context.filesDir, "geo")
    private val _state = MutableStateFlow<State>(State.Idle)
    private val mutex = Mutex()

    /** Progress of the current [ensure]. */
    val state: StateFlow<State> = _state.asStateFlow()

    fun file(name: String) = File(dir, name)

    /** Whether [name] (`geoip.dat` / `geosite.dat`) is present. */
    fun has(name: String) = file(name).let { it.isFile && it.length() > 0 }

    /**
     * Which files an xray [config] needs: `geosite.dat` for `geosite:` and `geoip.dat` for `geoip:`
     * references.
     */
    fun needed(config: String): List<String> = buildList {
        if ("geoip:" in config) add(GEOIP)
        if ("geosite:" in config) add(GEOSITE)
    }

    /** Downloads every file of [names] that is missing. Throws on failure (state becomes [State.Failed]). */
    suspend fun ensure(names: Collection<String>) = mutex.withLock {
        for (name in names) if (!has(name)) download(name)
    }

    /** Re-downloads [names] even when present (更新规则数据). */
    suspend fun update(names: Collection<String> = listOf(GEOIP, GEOSITE)) = mutex.withLock {
        for (name in names) download(name)
    }

    private suspend fun download(name: String) = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val tmp = File(dir, "$name.part")
        _state.value = State.Downloading(name, 0, -1)
        LogRepository.log("正在下载 $name …")
        try {
            var url = URL(BASE_URL + name)
            var conn: HttpURLConnection
            var redirects = 0
            while (true) {
                conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    instanceFollowRedirects = false
                    setRequestProperty("User-Agent", "DuoBaoProxy/${GoCore.version.ifEmpty { "dev" }}")
                }
                val code = conn.responseCode
                if (code in 300..399 && redirects++ < 5) {
                    val loc = conn.getHeaderField("Location") ?: throw IOException("HTTP $code")
                    conn.disconnect()
                    url = URL(url, loc) // GitHub → objects.githubusercontent.com (https → https)
                    continue
                }
                if (code != 200) throw IOException("HTTP $code")
                break
            }
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var last = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (done - last >= 256 * 1024) {
                            last = done
                            _state.value = State.Downloading(name, done, total)
                        }
                    }
                    if (total > 0 && done != total) throw IOException("下载不完整（$done / $total）")
                }
            }
            conn.disconnect()
            if (!tmp.renameTo(file(name))) throw IOException("无法保存 $name")
            LogRepository.log("已下载 $name（${file(name).length() / 1024} KB）")
            _state.value = State.Idle
        } catch (e: Exception) {
            tmp.delete()
            val msg = e.message ?: e.toString()
            _state.value = State.Failed(name, msg)
            LogRepository.log("下载 $name 失败：$msg")
            throw IOException("下载 $name 失败：$msg", e)
        }
    }

    companion object {
        const val GEOIP = "geoip.dat"
        const val GEOSITE = "geosite.dat"
        const val BASE_URL = "https://github.com/Loyalsoldier/v2ray-rules-dat/releases/latest/download/"
    }
}
