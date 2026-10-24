package com.duobao.proxy.core

import android.net.DnsResolver
import android.net.VpnService
import android.os.CancellationSignal
import com.duobao.core.mobile.LocalDNS
import com.duobao.core.mobile.Protector
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The process-wide gomobile [Protector]: `VpnService.protect` of the running VPN, or `true` when no
 * VPN is up (then there is nothing to escape). The VPN service sets [service] while it runs.
 */
object VpnProtector : Protector {
    /** The running VPN service, or null. */
    @Volatile var service: VpnService? = null

    override fun protect(fd: Int): Boolean = service?.protect(fd) ?: true
}

/**
 * The gomobile [LocalDNS]: `DnsResolver.rawQuery` on the current default non-VPN network
 * ([NetworkTracker.current]), so `type: local` DNS of sing-box never loops into the VPN and never
 * falls back to 127.0.0.1:53 (spec/critic.md B3). Each query times out after [TIMEOUT_MS].
 */
object AndroidLocalDns : LocalDNS {
    const val TIMEOUT_MS = 10_000L

    private val executor = Executors.newCachedThreadPool { r -> Thread(r, "DuoBao-dns").apply { isDaemon = true } }

    override fun exchange(message: ByteArray): ByteArray {
        val network = NetworkTracker.current.value?.network ?: throw Exception("没有可用的网络")
        val latch = CountDownLatch(1)
        var answer: ByteArray? = null
        var error: Throwable? = null
        val cancel = CancellationSignal()
        // getInstance() is deprecated in SDK 37 but is the only entry point on minSdk 29.
        @Suppress("DEPRECATION")
        val resolver = DnsResolver.getInstance()
        resolver.rawQuery(
            network, message, DnsResolver.FLAG_EMPTY, executor, cancel,
            object : DnsResolver.Callback<ByteArray> {
                override fun onAnswer(a: ByteArray, rcode: Int) {
                    answer = a
                    latch.countDown()
                }

                override fun onError(e: DnsResolver.DnsException) {
                    error = e
                    latch.countDown()
                }
            },
        )
        if (!latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            cancel.cancel()
            throw Exception("DNS 查询超时 (i/o timeout)")
        }
        error?.let { throw Exception("DNS 查询失败：" + (it.message ?: it.toString())) }
        return answer ?: throw Exception("DNS 查询无应答")
    }
}
