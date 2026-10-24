package com.duobao.proxy.vpn

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.ProxyInfo
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.os.Process
import com.duobao.core.mobile.Host
import com.duobao.core.mobile.NetworkListener
import com.duobao.proxy.MainActivity
import com.duobao.proxy.core.LogRepository
import com.duobao.proxy.core.NetworkTracker
import com.duobao.proxy.core.VpnProtector
import com.duobao.proxy.state.AppController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * The VPN: a foreground [VpnService] that is the gomobile [Host] of the running engine. It runs in
 * the app process, so the engine, the UI and [AppController] share one Go runtime.
 *
 * Start paths: [connectIntent] from [AppController.connect], the system's Always-on intent
 * (`android.net.VpnService` action → connect the last node), the tile. Each goes to
 * [AppController.startEngine], which renders and calls `Mobile.start(opts, this)`; the engine then
 * calls [openTun] / [startNetworkMonitor] / [interfacesJSON] / [findOwnerJSON] from Go threads.
 *
 * Foreground type: `specialUse` (subtype `vpn`). `systemExempted` is only allowed for an *active*
 * VPN app, which this service is not yet when it has to call `startForeground`.
 */
class DuoBaoVpnService : VpnService(), Host {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val lock = Any()
    private var tun: ParcelFileDescriptor? = null
    private var monitor: ((NetworkTracker.DefaultNetwork?) -> Unit)? = null
    private var notifyJob: Job? = null
    @Volatile private var sessionName = "多宝代理"
    private lateinit var notification: VpnNotification

    override fun onCreate() {
        super.onCreate()
        instance = this
        VpnProtector.service = this
        notification = VpnNotification(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                AppController.instance.disconnect()
                return START_NOT_STICKY
            }
            else -> {
                // startForegroundService requires startForeground right away, also on Always-on.
                startForeground(VpnNotification.ID, notification.connecting(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                val system = intent == null || intent.action == SERVICE_INTERFACE
                if (system) LogRepository.log("系统启动了 VPN（始终开启的 VPN）")
                AppController.instance.startEngine(this, system)
            }
        }
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        LogRepository.log("VPN 权限被撤销或被其他 VPN 应用接管")
        AppController.instance.onRevoked()
    }

    override fun onDestroy() {
        scope.cancel()
        closeTun()
        closeNetworkMonitor()
        if (instance === this) instance = null
        if (VpnProtector.service === this) VpnProtector.service = null
        super.onDestroy()
        AppController.instance.onServiceDestroyed()
    }

    // ---- driven by AppController ------------------------------------------------------------

    /** The `setSession` label of the next TUN (the node name). */
    fun prepareSession(name: String) {
        sessionName = name
    }

    /** Switches the notification to the connected look with live speed. */
    fun showConnected(nodeName: String) {
        sessionName = nodeName
        notifyJob?.cancel()
        notifyJob = scope.launch {
            AppController.instance.traffic.collect { t -> notification.update(notification.connected(nodeName, t)) }
        }
        ToggleTileService.refresh(this)
    }

    /** Closes the TUN of the stopped engine (the engine must be stopped first). */
    fun closeTun() {
        synchronized(lock) {
            runCatching { tun?.close() }
            tun = null
        }
    }

    /** Stops the foreground service; the engine must already be stopped. */
    fun shutdown() {
        notifyJob?.cancel()
        closeTun()
        closeNetworkMonitor()
        VpnProtector.service = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        ToggleTileService.refresh(this)
    }

    // ---- Host (called by Go) --------------------------------------------------------------

    override fun openTun(tunConfigJSON: String): Int {
        val o = Json.parseToJsonElement(tunConfigJSON) as JsonObject
        val b = Builder().setSession(sessionName)
        o.int("mtu")?.takeIf { it > 0 }?.let { b.setMtu(it) }
        for (a in o.strings("inet4_address") + o.strings("inet6_address")) {
            val (addr, prefix) = cidr(a)
            b.addAddress(addr, prefix)
        }
        if (o.bool("auto_route")) {
            for (r in o.strings("inet4_route_range") + o.strings("inet6_route_range")) {
                val (addr, prefix) = cidr(r)
                b.addRoute(addr, prefix)
            }
        }
        if (o.string("dns_mode") != "disabled") for (d in o.strings("dns_servers")) b.addDnsServer(d)
        val include = o.strings("include_package").filter { it != packageName }
        if (include.isNotEmpty()) {
            for (p in include) runCatching { b.addAllowedApplication(p) }.onFailure { LogRepository.log("找不到应用 $p，已跳过") }
        } else {
            // The app itself must stay outside the VPN (its sockets are the engine's).
            val exclude = (o.strings("exclude_package") + packageName).distinct()
            for (p in exclude) runCatching { b.addDisallowedApplication(p) }.onFailure { LogRepository.log("找不到应用 $p，已跳过") }
        }
        (o["http_proxy"] as? JsonObject)?.let { hp ->
            val server = hp.string("server")
            val port = hp.int("server_port") ?: 0
            if (server.isNotEmpty() && port > 0) b.setHttpProxy(ProxyInfo.buildDirectProxy(server, port, hp.strings("bypass_domain")))
        }
        b.setMetered(false)
        b.setBlocking(false)
        NetworkTracker.current.value?.network?.let { b.setUnderlyingNetworks(arrayOf(it)) }
        b.setConfigureIntent(
            PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE),
        )
        val pfd = b.establish() ?: throw Exception("VPN 未授权或已被其他 VPN 应用占用")
        synchronized(lock) {
            runCatching { tun?.close() }
            tun = pfd
        }
        return pfd.fd
    }

    override fun startNetworkMonitor(l: NetworkListener) {
        closeNetworkMonitor()
        val report: (NetworkTracker.DefaultNetwork?) -> Unit = { d ->
            if (d == null) {
                l.updateDefaultInterface("", -1, false, false)
            } else {
                // The index can lag right after a switch; resolve it again at report time.
                val index = d.index.takeIf { it >= 0 } ?: NetworkTracker.interfaceIndex(d.interfaceName)
                l.updateDefaultInterface(d.interfaceName, index, d.metered, false)
                runCatching { setUnderlyingNetworks(arrayOf(d.network)) }
            }
        }
        // Must report the current network before returning, else dials fail until the first change.
        report(NetworkTracker.current.value)
        synchronized(lock) { monitor = report }
        NetworkTracker.addListener(report)
    }

    override fun closeNetworkMonitor() {
        val m = synchronized(lock) { monitor.also { monitor = null } }
        if (m != null) NetworkTracker.removeListener(m)
    }

    override fun interfacesJSON(): String = buildJsonArray {
        for ((_, caps, lp) in NetworkTracker.networks()) {
            val name = lp.interfaceName ?: continue
            add(buildJsonObject {
                put("name", name)
                put("index", NetworkTracker.interfaceIndex(name))
                put("mtu", lp.mtu.takeIf { it > 0 } ?: 1500)
                put("type", when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                    else -> "other"
                })
                put("addresses", buildJsonArray { for (a in lp.linkAddresses) add(JsonPrimitive("${a.address.hostAddress?.substringBefore('%')}/${a.prefixLength}")) })
                put("dns", buildJsonArray { for (d in lp.dnsServers) add(JsonPrimitive(d.hostAddress?.substringBefore('%'))) })
                put("gateways", buildJsonArray {
                    for (r in lp.routes) if (r.isDefaultRoute && r.gateway != null && !r.gateway!!.isAnyLocalAddress) add(JsonPrimitive(r.gateway!!.hostAddress?.substringBefore('%')))
                })
                put("metered", !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
            })
        }
    }.toString()

    override fun findOwnerJSON(proto: Int, src: String, sport: Int, dst: String, dport: Int): String {
        val cm = getSystemService(ConnectivityManager::class.java)
        val uid = cm.getConnectionOwnerUid(proto, InetSocketAddress(InetAddress.getByName(src), sport), InetSocketAddress(InetAddress.getByName(dst), dport))
        if (uid == Process.INVALID_UID) return """{"uid":-1}"""
        val pkgs = packageManager.getPackagesForUid(uid).orEmpty()
        return buildJsonObject {
            put("uid", uid)
            put("packages", buildJsonArray { for (p in pkgs) add(JsonPrimitive(p)) })
        }.toString()
    }

    companion object {
        const val ACTION_CONNECT = "com.duobao.proxy.action.CONNECT"
        const val ACTION_STOP = "com.duobao.proxy.action.STOP"

        /** The running service, or null. */
        @Volatile var instance: DuoBaoVpnService? = null
            private set

        fun connectIntent(context: Context): Intent = Intent(context, DuoBaoVpnService::class.java).setAction(ACTION_CONNECT)

        fun stopIntent(context: Context): Intent = Intent(context, DuoBaoVpnService::class.java).setAction(ACTION_STOP)

        /** Whether the VPN permission is granted (`VpnService.prepare == null`). */
        fun permissionGranted(context: Context) = prepare(context) == null

        private fun cidr(s: String): Pair<InetAddress, Int> {
            val addr = InetAddress.getByName(s.substringBefore('/'))
            val prefix = s.substringAfter('/', "").toIntOrNull() ?: if (addr is Inet4Address) 32 else 128
            return addr to prefix
        }

        private fun JsonObject.strings(key: String): List<String> =
            (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()

        private fun JsonObject.string(key: String): String = (this[key] as? JsonPrimitive)?.content.orEmpty()
        private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
        private fun JsonObject.bool(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull == true
    }
}
