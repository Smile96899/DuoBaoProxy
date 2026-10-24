package com.duobao.proxy.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.duobao.core.mobile.LogHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The in-memory log of both engines and of the app (`App.logs` + `App.log()` on desktop): every line
 * has its ANSI colours stripped, is translated by [LogZh] on arrival and kept in a ring of
 * [CAPACITY] lines (oldest dropped).
 *
 * It is the process-wide gomobile [LogHandler] (installed by [GoCore]). [lines] is published at most
 * every [PUBLISH_MS] ms so a chatty debug log does not copy the ring for every line.
 */
object LogRepository : LogHandler {
    /** Lines kept, as on desktop. */
    const val CAPACITY = 500
    private const val PUBLISH_MS = 100L

    /** Logcat tag of the mirrored lines (debug builds) and of the debug receiver. */
    const val TAG = "DuoBao"

    private val ring = ArrayDeque<String>(CAPACITY)
    private val lock = Any()
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val publishPending = AtomicBoolean(false)

    /** Mirror every line to logcat (tag [TAG]); [GoCore] turns it on for debug builds. */
    @Volatile var mirrorToLogcat = false

    /** The kept lines, oldest first, already in Chinese. */
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    /** gomobile callback: one engine line ([level] uses the sing-box scale 0 panic … 6 trace). */
    override fun writeLog(level: Int, line: String) {
        append(LogZh.translate(LogZh.stripAnsi(line)), level)
    }

    /** `App.log(line)`: an app message (already Chinese); it goes through the same translation. */
    fun log(line: String) = append(LogZh.translate(LogZh.stripAnsi(line)), LEVEL_INFO)

    private fun append(text: String, level: Int) {
        if (mirrorToLogcat) Log.println(logcatPriority(level), TAG, text)
        synchronized(lock) {
            if (ring.size >= CAPACITY) ring.removeFirst()
            ring.addLast(text)
        }
        schedulePublish()
    }

    private fun schedulePublish() {
        if (!publishPending.compareAndSet(false, true)) return
        scope.launch {
            delay(PUBLISH_MS)
            publishPending.set(false)
            _lines.value = snapshot()
        }
    }

    /** The current lines without waiting for the next publish. */
    fun snapshot(): List<String> = synchronized(lock) { ring.toList() }

    /** 清空. */
    fun clear() {
        synchronized(lock) { ring.clear() }
        _lines.value = emptyList()
    }

    /** All lines joined by `\n`, for 复制 / export. */
    fun text(): String = snapshot().joinToString("\n")

    /**
     * The report that 分享日志 / export writes: a header, the kept lines and, when present, the Go
     * crash output of an earlier run (`filesDir/crash.log`, last 64 KiB).
     */
    fun exportText(context: Context): String = buildString {
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date())
        append("DuoBao Proxy 日志 · ").append(now).append('\n')
        append(text()).append('\n')
        crashLog(context)?.let { append("\n---- crash.log ----\n").append(it).append('\n') }
    }

    /** Tail of `filesDir/crash.log`, or null when it is missing or empty. */
    fun crashLog(context: Context): String? {
        val f = File(context.filesDir, "crash.log")
        if (!f.isFile || f.length() == 0L) return null
        val max = 64 * 1024L
        return f.inputStream().use { input ->
            if (f.length() > max) input.skip(f.length() - max)
            input.readBytes().decodeToString()
        }.takeIf { it.isNotBlank() }
    }

    /** An `ACTION_SEND` chooser for 分享日志 (plain text). */
    fun shareIntent(context: Context): Intent {
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "DuoBao Proxy 日志")
            .putExtra(Intent.EXTRA_TEXT, exportText(context))
        return Intent.createChooser(send, "分享日志")
    }

    /** Writes [exportText] to a SAF document ([uri] from `ACTION_CREATE_DOCUMENT`). */
    suspend fun exportTo(context: Context, uri: Uri) = withContext(Dispatchers.IO) {
        val text = exportText(context)
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) }
            ?: error("无法写入 $uri")
    }

    /**
     * LogsPage colour of a line: [LineTone.Bad] for errors / failures / timeouts, [LineTone.Warn] for
     * warnings, otherwise [LineTone.Normal] (`LogsPage.qml:15-17`).
     */
    fun tone(line: String): LineTone = when {
        BAD_RE.containsMatchIn(line) -> LineTone.Bad
        WARN_RE.containsMatchIn(line) -> LineTone.Warn
        else -> LineTone.Normal
    }

    enum class LineTone { Normal, Warn, Bad }

    private val BAD_RE = Regex("""(^|\s)(错误|致命|崩溃)[ ：]|失败|超时""")
    private val WARN_RE = Regex("""(^|\s)警告 """)
    private const val LEVEL_INFO = 4

    private fun logcatPriority(level: Int) = when (level) {
        0, 1, 2 -> Log.ERROR
        3 -> Log.WARN
        4 -> Log.INFO
        else -> Log.DEBUG
    }
}
