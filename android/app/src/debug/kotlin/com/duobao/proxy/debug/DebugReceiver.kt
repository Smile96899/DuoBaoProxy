package com.duobao.proxy.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.duobao.core.mobile.Mobile
import com.duobao.proxy.core.GoCore
import com.duobao.proxy.core.LogRepository
import com.duobao.proxy.data.parseObject
import com.duobao.proxy.state.AppController
import com.duobao.proxy.tests.TestFormat
import com.duobao.proxy.tests.TestKinds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

/**
 * DEBUG BUILDS ONLY (src/debug): drives the app from adb and prints results to logcat, tag `DuoBao`,
 * lines prefixed `DBG`. Explicit broadcasts (the receiver has no intent filter):
 *
 *     adb shell am broadcast -n com.duobao.proxy/.debug.DebugReceiver -a <action> [extras]
 *
 * Actions (prefix `com.duobao.proxy.debug.`):
 * - `IMPORT --es text <links|url> [--es group <name>]` — import text or a subscription URL
 * - `SELECT --es name <name> | --ei id <id>` — select a node (exact name first, then substring)
 * - `SET_UI --es key <k> --es value <json-or-string>` — write `ui.<k>` (`core` goes through setCore)
 * - `CONNECT`, `DISCONNECT`, `RECONNECT`
 * - `URLTEST [--es url <u>]` — url test on the running engine (or a throw-away one when disconnected)
 * - `GEO` — exit IP / country through the running engine (geo probe, config "")
 * - `TEST --es kind <k> [--es name <node>]` — a TestEngine run, waits for and prints the result
 * - `STATUS` — state, session, traffic and the node list
 * - `LOGS [--ei n <count>]` — the last n translated log lines (default 60)
 */
class DebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action?.removePrefix(PREFIX) ?: return
        val pending = goAsync()
        scope.launch {
            try {
                withTimeoutOrNull(120_000) { handle(action, intent) } ?: out("$action: timeout")
            } catch (e: Exception) {
                out("$action: error ${e.message}")
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun handle(action: String, intent: Intent) {
        val c = AppController.instance
        GoCore.awaitReady()
        when (action) {
            "IMPORT" -> {
                val r = c.importText(intent.getStringExtra("text").orEmpty(), intent.getStringExtra("group") ?: "默认")
                out("IMPORT ok=${r.ok} group=${r.group} added=${r.added} errors=${r.errors.size} msg=${r.message}")
                for (e in r.errors) out("  $e")
            }
            "SELECT" -> {
                val nodes = c.store.nodes.value
                val id = intent.getIntExtra("id", -1).takeIf { it >= 0 } ?: intent.getStringExtra("name")?.let { n ->
                    (nodes.firstOrNull { it.title == n } ?: nodes.firstOrNull { n in it.title })?.id
                } ?: -1
                c.selectNode(id).join()
                out("SELECT id=$id selected=${c.currentSelected()} name=${c.store.node(c.currentSelected())?.title}")
            }
            "SET_UI" -> {
                val key = intent.getStringExtra("key").orEmpty()
                val raw = intent.getStringExtra("value").orEmpty()
                if (key == "core") {
                    c.setCore(raw).join()
                } else {
                    val v = runCatching { Json.parseToJsonElement(raw) }.getOrElse { JsonPrimitive(raw) }
                    c.store.setUi(key, v)
                }
                out("SET_UI $key=${c.store.ui.value[key]}")
            }
            "CONNECT" -> {
                c.connect().join()
                val ok = withTimeoutOrNull(60_000) {
                    c.busy.first { !it }
                    delay(200)
                    c.busy.first { !it }
                }
                out("CONNECT connected=${c.connected.value} status=${c.statusText.value} waited=${ok != null}")
            }
            "DISCONNECT" -> {
                c.disconnect().join()
                withTimeoutOrNull(30_000) { c.busy.first { !it } }
                out("DISCONNECT connected=${c.connected.value} status=${c.statusText.value} engine=${GoCore.call { Mobile.status() }}")
            }
            "RECONNECT" -> {
                c.reconnect().join()
                withTimeoutOrNull(60_000) { c.busy.first { !it } }
                out("RECONNECT connected=${c.connected.value} status=${c.statusText.value}")
            }
            "URLTEST" -> {
                val ui = c.store.ui.value
                val url = intent.getStringExtra("url") ?: TestKinds.optString(ui, "url", "url")
                val s = c.session.value
                val t0 = System.currentTimeMillis()
                val r = runCatching {
                    if (s != null) {
                        GoCore.call { Mobile.urlTest(s.coreId, "", url, 10_000) }
                    } else {
                        val settings = c.renderSettings()
                        val rendered = GoCore.call {
                            Mobile.renderConfig(c.currentCore(), "[" + c.store.nodeJson(c.currentSelected()) + "]", settings, 1)
                        }
                        GoCore.call { Mobile.urlTest(c.currentCore(), rendered.config, url, 10_000) }
                    }
                }
                out("URLTEST running=${s != null} url=$url result=${r.getOrNull() ?: ("error " + r.exceptionOrNull()?.message)} wall=${System.currentTimeMillis() - t0}ms")
            }
            "GEO" -> {
                val s = c.session.value ?: return out("GEO not connected")
                val url = TestKinds.optString(c.store.ui.value, "geo", "url")
                val r = runCatching { GoCore.call { Mobile.probe("geo", s.coreId, "", "", url, 0, 10_000) } }
                out("GEO core=${s.coreId} " + (r.getOrNull()?.let { "ms=${it.ms} report=${it.report}" } ?: ("error " + r.exceptionOrNull()?.message)))
            }
            "TEST" -> {
                val kind = intent.getStringExtra("kind") ?: "url"
                val name = intent.getStringExtra("name")
                val nodes = c.store.nodes.value
                val id = name?.let { n -> (nodes.firstOrNull { it.title == n } ?: nodes.firstOrNull { n in it.title })?.id } ?: c.currentSelected()
                c.tests.run(listOf(id), listOf(kind))
                delay(100)
                withTimeoutOrNull(110_000) { c.tests.running.first { "$id:$kind" !in it } }
                val e = c.store.node(id)
                out("TEST $kind node=${e?.title} cell=${TestFormat.cell(e, kind)} detail=${TestFormat.detail(e, kind).replace('\n', '|')} raw=${e?.res?.get(kind)}")
            }
            "STATUS" -> {
                val s = c.session.value
                out("STATUS connected=${c.connected.value} busy=${c.busy.value} status=${c.statusText.value} core=${c.currentCore()} engine=${GoCore.call { Mobile.status() }} selected=${c.currentSelected()} latency=${c.latency.value}")
                out("STATUS session=$s traffic=${c.traffic.value} cores=${GoCore.cores.value.map { it.id + " " + it.version }} version=${GoCore.version}")
                for (g in c.store.groups.value) out("  group ${g.name} sub=${g.subUrl} json=${g.json}")
                for (e in c.store.nodes.value) out("  node ${e.id} [${e.group}] ${e.protocol} ${e.title} lat=${e.lat} ok=${c.supports(e.node)}")
            }
            "LOGS" -> {
                val n = intent.getIntExtra("n", 60)
                for (l in LogRepository.snapshot().takeLast(n)) out("LOG $l")
            }
            "SETTING" -> {
                val key = intent.getStringExtra("key").orEmpty()
                val raw = intent.getStringExtra("value").orEmpty()
                val v = runCatching { Json.parseToJsonElement(raw) }.getOrElse { JsonPrimitive(raw) }
                c.store.setSetting(key, v)
                out("SETTING $key -> ${parseObject(c.store.settingsJson())}")
            }
            else -> out("unknown action $action")
        }
    }

    private fun out(line: String) {
        Log.i(LogRepository.TAG, "DBG $line")
    }

    private companion object {
        const val PREFIX = "com.duobao.proxy.debug."
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
