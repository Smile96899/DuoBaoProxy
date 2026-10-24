package com.duobao.proxy.core

import android.app.Application
import com.duobao.core.mobile.Mobile
import com.duobao.core.mobile.SetupOptions
import com.duobao.proxy.BuildConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * The Go library (`com.duobao.core.mobile.Mobile`) for the rest of the app.
 *
 * Every Go call blocks, so none may run on the main thread. Two dispatchers carry them:
 * - [lifecycle], one thread: `Setup`, `Start`, `Stop` — strictly ordered;
 * - [pool], a few threads: renders, tests, stats, imports — tests run up to 6 at a time
 *   (`Tests.maxActive`), so they cannot share one thread.
 *
 * [call] / [serial] wait until `Setup` finished, so callers never race it. [init] runs once from
 * `Application.onCreate`: it installs the log handler, protector and local DNS, then runs `Setup`
 * on [lifecycle] (loading the 60 MB native library off the main thread).
 */
object GoCore {
    /** One engine as `ListCoresJSON` reports it. */
    data class CoreInfo(val id: String, val version: String, val protocols: List<String>, val running: Boolean)

    /** Single-thread dispatcher for Setup / Start / Stop. */
    val lifecycle: CoroutineDispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "DuoBao-go").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    private val poolIds = AtomicInteger()

    /** Pool for every other Go call (tests, renders, stats, imports). */
    val pool: CoroutineDispatcher = Executors.newFixedThreadPool(POOL_SIZE) { r ->
        Thread(r, "DuoBao-go-${poolIds.incrementAndGet()}").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    private const val POOL_SIZE = 8

    /** Process scope for fire-and-forget core work. */
    val scope = CoroutineScope(SupervisorJob() + pool)

    private val setupDone = CompletableDeferred<Unit>()
    private val _ready = MutableStateFlow(false)
    private val _setupError = MutableStateFlow<String?>(null)
    private val _cores = MutableStateFlow<List<CoreInfo>>(emptyList())

    /** True once `Setup` succeeded. */
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    /** The `Setup` error, if it failed (calls then throw it). */
    val setupError: StateFlow<String?> = _setupError.asStateFlow()

    /** Engines and their protocols (`ListCoresJSON`), filled after `Setup`; drives `supports`. */
    val cores: StateFlow<List<CoreInfo>> = _cores.asStateFlow()

    @Volatile private var options: SetupOptions? = null

    /** Library version baked in at build time ("dev" for local builds); "" before `Setup`. */
    @Volatile var version: String = ""
        private set

    /** Starts the network tracker and runs `Setup` in the background. Idempotent. */
    @Synchronized
    fun init(app: Application) {
        if (options != null) return
        LogRepository.mirrorToLogcat = BuildConfig.DEBUG
        NetworkTracker.start(app)
        val o = SetupOptions().apply {
            basePath = app.filesDir.absolutePath
            tempPath = app.cacheDir.absolutePath
            assetPath = File(app.filesDir, "geo").absolutePath
            platform = "android"
            ownPackage = app.packageName
            debug = BuildConfig.DEBUG
        }
        options = o
        CoroutineScope(lifecycle).launch {
            try {
                Mobile.setLogHandler(LogRepository)
                Mobile.setProtector(VpnProtector)
                Mobile.setLocalDNS(AndroidLocalDns)
                Mobile.setup(o)
                version = Mobile.version()
                _cores.value = parseCores(Mobile.listCoresJSON())
                _ready.value = true
                setupDone.complete(Unit)
            } catch (e: Throwable) {
                _setupError.value = e.message ?: e.toString()
                LogRepository.log("核心初始化失败：${e.message}")
                setupDone.completeExceptionally(e)
            }
        }
    }

    /** Suspends until `Setup` finished; throws its error when it failed. */
    suspend fun awaitReady() = setupDone.await()

    /** Runs a Go call on [pool] after `Setup`. */
    suspend fun <T> call(block: () -> T): T {
        awaitReady()
        return withContext(pool) { block() }
    }

    /** Runs a Go call on [lifecycle] (ordered with Start / Stop) after `Setup`. */
    suspend fun <T> serial(block: () -> T): T {
        awaitReady()
        return withContext(lifecycle) { block() }
    }

    /** Re-reads `ListCoresJSON` (also refreshes `running`). */
    suspend fun refreshCores(): List<CoreInfo> = call {
        parseCores(Mobile.listCoresJSON()).also { _cores.value = it }
    }

    /**
     * 重启核心 on mobile: re-run `Setup` with the same options (critic A6). The caller stops the
     * tunnel first.
     */
    suspend fun restart() = serial {
        options?.let { Mobile.setup(it) }
        Unit
    }

    /** Whether engine [coreId] can carry [protocol], from the `ListCoresJSON` protocol lists. */
    fun supports(coreId: String, protocol: String): Boolean =
        _cores.value.firstOrNull { it.id == coreId }?.protocols?.contains(protocol) == true

    /** Parses `ListCoresJSON`. */
    fun parseCores(json: String): List<CoreInfo> = runCatching {
        (Json.parseToJsonElement(json) as JsonArray).mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            CoreInfo(
                id = (o["id"] as? JsonPrimitive)?.content ?: return@mapNotNull null,
                version = (o["version"] as? JsonPrimitive)?.content.orEmpty(),
                protocols = (o["protocols"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty(),
                running = (o["running"] as? JsonPrimitive)?.booleanOrNull == true,
            )
        }
    }.getOrDefault(emptyList())
}
