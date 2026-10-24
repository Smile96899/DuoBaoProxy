package com.duobao.proxy.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.HandlerThread
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.NetworkInterface
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Tracks the default **non-VPN** network: the underlying network that the core's own sockets, the
 * local DNS resolver and `VpnService.Builder.setUnderlyingNetworks` must use.
 *
 * Candidates come from `registerNetworkCallback(INTERNET + NOT_VPN)`. The app's own package is always
 * outside the VPN (`exclude_package`, or left out of `include_package`), so the app's
 * `activeNetwork` is the system default underlying network; it wins when it is a candidate,
 * otherwise the first validated candidate does. A default-network callback re-evaluates on switches.
 */
object NetworkTracker {
    /** One underlying network as the core sees it. [index] is the OS interface index, -1 when unknown. */
    data class DefaultNetwork(
        val network: Network,
        val interfaceName: String,
        val index: Int,
        val metered: Boolean,
    )

    private lateinit var cm: ConnectivityManager
    private val handler by lazy { Handler(HandlerThread("DuoBao-net").apply { start() }.looper) }
    private val candidates = LinkedHashMap<Network, Pair<NetworkCapabilities?, LinkProperties?>>()
    private val listeners = CopyOnWriteArrayList<(DefaultNetwork?) -> Unit>()
    private val _current = MutableStateFlow<DefaultNetwork?>(null)
    private var started = false

    /** The current default non-VPN network, or null when offline. */
    val current: StateFlow<DefaultNetwork?> = _current.asStateFlow()

    /** Registers the callbacks; idempotent. Called from `GoCore.init`. */
    @Synchronized
    fun start(context: Context) {
        if (started) return
        started = true
        cm = context.getSystemService(ConnectivityManager::class.java)
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        cm.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = update { candidates.getOrPut(network) { null to null } }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) =
                update { candidates[network] = caps to candidates[network]?.second }
            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) =
                update { candidates[network] = candidates[network]?.first to lp }
            override fun onLost(network: Network) = update { candidates.remove(network) }
        }, handler)
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = update {}
            override fun onLost(network: Network) = update {}
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = update {}
        }, handler)
        handler.post { update {} }
    }

    /** Called on the tracker thread with every change of [current] (also to null). */
    fun addListener(l: (DefaultNetwork?) -> Unit) = listeners.add(l)

    fun removeListener(l: (DefaultNetwork?) -> Unit) = listeners.remove(l)

    /** All non-VPN networks that are up, with their properties (for `Host.interfacesJSON`). */
    fun networks(): List<Triple<Network, NetworkCapabilities, LinkProperties>> {
        @Suppress("DEPRECATION")
        return cm.allNetworks.mapNotNull { n ->
            val caps = cm.getNetworkCapabilities(n) ?: return@mapNotNull null
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) return@mapNotNull null
            val lp = cm.getLinkProperties(n) ?: return@mapNotNull null
            Triple(n, caps, lp)
        }
    }

    private fun update(change: () -> Unit) {
        synchronized(candidates) { change() }
        val next = pick()
        if (next == _current.value) return
        _current.value = next
        for (l in listeners) runCatching { l(next) }
    }

    private fun pick(): DefaultNetwork? {
        val keys = synchronized(candidates) { candidates.keys.toList() }
        if (keys.isEmpty()) return null
        val active = cm.activeNetwork
        val ordered = buildList {
            if (active != null && active in keys) add(active)
            keys.filterTo(this) { n ->
                n != active && cm.getNetworkCapabilities(n)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
            }
            keys.filterTo(this) { it !in this }
        }
        for (n in ordered) {
            val caps = cm.getNetworkCapabilities(n) ?: continue
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) continue
            val name = cm.getLinkProperties(n)?.interfaceName ?: continue
            return DefaultNetwork(
                network = n,
                interfaceName = name,
                index = interfaceIndex(name),
                metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
            )
        }
        return null
    }

    /** `NetworkInterface.getByName(name).index`, -1 when the interface is gone. */
    fun interfaceIndex(name: String): Int = try {
        NetworkInterface.getByName(name)?.index ?: -1
    } catch (_: Exception) {
        -1
    }
}
