package com.duobao.proxy.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import com.duobao.proxy.ui.components.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.duobao.proxy.data.Defaults
import com.duobao.proxy.data.asString
import com.duobao.proxy.state.AppController
import com.duobao.proxy.state.NodeField
import com.duobao.proxy.state.NodeSpecs
import com.duobao.proxy.ui.NodeEditRoute
import com.duobao.proxy.ui.components.Badge
import com.duobao.proxy.ui.components.Card
import com.duobao.proxy.ui.components.CardRow
import com.duobao.proxy.ui.components.DbpToggle
import com.duobao.proxy.ui.components.MenuRow
import com.duobao.proxy.ui.components.PrimaryButton
import com.duobao.proxy.ui.components.SectionLabel
import com.duobao.proxy.ui.components.SegTabs
import com.duobao.proxy.ui.components.SubPageHeader
import com.duobao.proxy.ui.components.TextBox
import com.duobao.proxy.ui.theme.DbpTheme
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private val PrettyJson = Json { prettyPrint = true }

/** `NodeEditor.qml` as a page: 常用 form or JSON, 分组, 保存 (spec/gui-nodes.md §2). */
@Composable
fun NodeEditorScreen(route: NodeEditRoute, onBack: () -> Unit) {
    val c = AppController.instance
    val colors = DbpTheme.colors
    val scope = rememberCoroutineScope()
    val groups by c.store.groups.collectAsStateWithLifecycle()
    val existing = remember(route) { if (route.id >= 0) c.store.node(route.id) else null }
    var draft by remember(route) { mutableStateOf(existing?.node ?: NodeSpecs.newDraft(route.protocol.ifEmpty { "vless" })) }
    var group by remember(route) { mutableStateOf(existing?.group?.ifEmpty { null } ?: Defaults.GROUP) }
    var json by remember { mutableStateOf(false) }
    var jsonText by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val protocol = draft["protocol"].asString()

    fun parseJson(): JsonObject? = try {
        Json.parseToJsonElement(jsonText) as? JsonObject ?: error("不是 JSON 对象")
    } catch (e: Exception) {
        error = "JSON 格式错误：" + (e.message ?: e.toString())
        null
    }

    Column(Modifier.fillMaxSize().background(colors.bg).imePadding()) {
        SubPageHeader(if (existing == null) "添加节点" else "编辑节点", onBack) {
            Badge(protocolLabel(protocol), colors.accentSoft, colors.accent)
        }
        SegTabs(listOf(false to "常用", true to "JSON"), json, { toJson ->
            if (toJson == json) return@SegTabs
            if (toJson) {
                jsonText = PrettyJson.encodeToString(JsonObject.serializer(), draft)
                json = true
                error = null
            } else {
                parseJson()?.let { draft = it; json = false; error = null }
            }
        })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(8.dp))
            if (json) {
                TextBox(jsonText, { jsonText = it }, Modifier.padding(horizontal = 20.dp), singleLine = false, mono = true, minHeight = 360.dp)
            } else {
                SectionLabel("分组")
                Card {
                    MenuRow("分组", groups.map { it.name to it.name }.ifEmpty { listOf(group to group) }, group, { group = it })
                }
                val fields = NodeSpecs.fields(draft)
                SectionLabel("连接")
                Card {
                    fields.forEachIndexed { i, f -> FieldRow(f, draft, divider = i > 0) { draft = it } }
                }
                Text(
                    "未列出的字段（如 xhttp 额外参数、TUIC 设置、自定义配置）请在 JSON 里编辑。",
                    color = colors.fg2, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp),
                )
            }
            error?.let { Text(it, color = colors.bad, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) }
            Spacer(Modifier.height(16.dp))
        }
        PrimaryButton("保存", {
            val d = if (json) parseJson() ?: return@PrimaryButton else draft
            NodeSpecs.validate(d).onFailure { error = it.message }.onSuccess { clean ->
                scope.launch {
                    val err = c.saveNode(existing?.id, clean, group)
                    if (err != null) error = err else onBack()
                }
            }
        }, Modifier.padding(vertical = 12.dp))
    }
}

/** One editor row: label on the left, an inline input / switch / menu on the right. */
@Composable
private fun FieldRow(f: NodeField, draft: JsonObject, divider: Boolean, onChange: (JsonObject) -> Unit) {
    val colors = DbpTheme.colors
    when (f.type) {
        "bool" -> CardRow(f.label, divider = divider) {
            DbpToggle(NodeSpecs.bool(draft, f.path), { onChange(NodeSpecs.setAt(draft, f.path, JsonPrimitive(it))) })
        }
        "choice" -> MenuRow(
            f.label, f.choices, NodeSpecs.text(draft, f.path).ifEmpty { f.default },
            { onChange(NodeSpecs.setAt(draft, f.path, JsonPrimitive(it))) }, divider = divider,
        )
        else -> {
            if (divider) HorizontalDivider(thickness = 1.dp, color = colors.line)
            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(f.label, color = colors.fg, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.width(104.dp))
                val v = NodeSpecs.text(draft, f.path)
                val style = TextStyle(color = colors.fg, fontSize = 15.sp)
                BasicTextField(
                    value = v,
                    onValueChange = { t ->
                        val nv = if (f.type == "int") {
                            val digits = t.filter(Char::isDigit).take(5)
                            if (digits.isNotEmpty() && digits.toLong() > 65535) return@BasicTextField
                            NodeSpecs.intValue(digits)
                        } else {
                            JsonPrimitive(t)
                        }
                        onChange(NodeSpecs.setAt(draft, f.path, nv))
                    },
                    singleLine = true,
                    textStyle = style,
                    cursorBrush = SolidColor(colors.accent),
                    keyboardOptions = KeyboardOptions(keyboardType = if (f.type == "int") KeyboardType.Number else KeyboardType.Text),
                    modifier = Modifier.weight(1f).padding(vertical = 14.dp),
                    decorationBox = { inner ->
                        if (v.isEmpty()) Text(f.hint, style = style.copy(color = colors.fg3), maxLines = 1)
                        inner()
                    },
                )
            }
        }
    }
}
