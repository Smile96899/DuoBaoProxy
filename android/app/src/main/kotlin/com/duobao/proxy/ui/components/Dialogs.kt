package com.duobao.proxy.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.duobao.proxy.ui.theme.DbpTheme
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * One entry of an [ActionSheet]. An item with [children] opens a sub-list; a null [label] is a
 * divider. [icon] shows in an accent tile like the 添加 panel.
 */
data class SheetItem(
    val label: String?,
    val icon: String? = null,
    val enabled: Boolean = true,
    val danger: Boolean = false,
    val checked: Boolean = false,
    val detail: String? = null,
    val children: List<SheetItem>? = null,
    val onClick: () -> Unit = {},
) {
    companion object {
        val Divider = SheetItem(null)
    }
}

/** The bottom sheet of the design (添加面板): title, rows, and a grey 取消 bar. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActionSheet(title: String, items: List<SheetItem>, onDismiss: () -> Unit) {
    val c = DbpTheme.colors
    val stack = remember { mutableStateListOf<Pair<String, List<SheetItem>>>() }
    val (curTitle, curItems) = stack.lastOrNull() ?: (title to items)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.panel,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (stack.isNotEmpty()) {
                    Box(Modifier.clip(RoundedCornerShape(50)).clickable { stack.removeAt(stack.lastIndex) }.padding(end = 6.dp)) {
                        DbpIcon("back", c.fg, size = 20.dp)
                    }
                }
                Text(curTitle, color = c.fg, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
                for (it in curItems) {
                    if (it.label == null) {
                        HorizontalDivider(Modifier.padding(vertical = 2.dp, horizontal = 10.dp), color = c.line)
                        continue
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .alpha(if (it.enabled) 1f else 0.45f)
                            .clickable(enabled = it.enabled) {
                                if (it.children != null) {
                                    stack.add(it.label to it.children)
                                } else {
                                    onDismiss()
                                    it.onClick()
                                }
                            }
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        if (it.icon != null) IconTile(it.icon, if (it.danger) c.badSoft else c.accentSoft, if (it.danger) c.bad else c.accent, size = 48.dp, round = true)
                        Column(Modifier.weight(1f)) {
                            Text(
                                it.label,
                                color = if (it.danger) c.bad else c.fg,
                                fontSize = 16.sp,
                                fontWeight = if (it.checked) FontWeight.SemiBold else FontWeight.Medium,
                            )
                            if (!it.detail.isNullOrEmpty()) Text(it.detail, color = c.fg2, fontSize = 12.sp)
                        }
                        if (it.checked) DbpIcon("check", c.accent, size = 18.dp)
                        if (it.children != null) DbpIcon("chevronRight", c.fg3, size = 18.dp)
                    }
                }
            }
            Box(
                Modifier
                    .padding(start = 16.dp, end = 16.dp, top = 12.dp)
                    .fillMaxWidth()
                    .height(52.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(c.panel2)
                    .clickable(onClick = onDismiss),
                contentAlignment = Alignment.Center,
            ) {
                Text("取消", color = c.fg, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun DialogButton(text: String, onClick: () -> Unit, enabled: Boolean = true, danger: Boolean = false, primary: Boolean = false) {
    val c = DbpTheme.colors
    TextButton(onClick = onClick, enabled = enabled) {
        Text(
            text,
            color = when {
                !enabled -> c.fg3
                danger -> c.bad
                primary -> c.accent
                else -> c.fg2
            },
            fontSize = 15.sp,
            fontWeight = if (primary || danger) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

/** Confirm popup: title, body, [取消] [confirm]. */
@Composable
fun ConfirmDialog(title: String, body: String?, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit, danger: Boolean = true) {
    val c = DbpTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.panel,
        shape = RoundedCornerShape(24.dp),
        title = { Text(title, color = c.fg, fontSize = 18.sp, fontWeight = FontWeight.Bold) },
        text = body?.let { { Text(it, color = c.fg2, fontSize = 14.sp) } },
        confirmButton = { DialogButton(confirm, { onDismiss(); onConfirm() }, danger = danger, primary = !danger) },
        dismissButton = { DialogButton("取消", onDismiss) },
    )
}

/** A dialog with free [content]; [confirm] null shows only 关闭. */
@Composable
fun CardDialog(
    title: String,
    onDismiss: () -> Unit,
    confirm: String? = null,
    canConfirm: Boolean = true,
    onConfirm: () -> Unit = {},
    dismiss: String = "取消",
    content: @Composable () -> Unit,
) {
    val c = DbpTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.panel,
        shape = RoundedCornerShape(24.dp),
        title = { Text(title, color = c.fg, fontSize = 18.sp, fontWeight = FontWeight.Bold) },
        text = { content() },
        confirmButton = { if (confirm != null) DialogButton(confirm, onConfirm, enabled = canConfirm, primary = true) },
        dismissButton = { DialogButton(if (confirm == null) "关闭" else dismiss, onDismiss) },
    )
}

/** A one-field prompt; [error] shows in red under the field. */
@Composable
fun PromptDialog(
    title: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    confirm: String,
    canConfirm: Boolean,
    error: String?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    numeric: Boolean = false,
    mono: Boolean = false,
) {
    val c = DbpTheme.colors
    CardDialog(title, onDismiss, confirm = confirm, canConfirm = canConfirm, onConfirm = onConfirm) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            TextBox(value, onValueChange, placeholder = placeholder, numeric = numeric, mono = mono, onDone = { if (canConfirm) onConfirm() })
            if (!error.isNullOrEmpty()) Text(error, color = c.bad, fontSize = 12.sp)
        }
    }
}

/** Short messages for the shell's snackbar. */
object UiMessages {
    private val _flow = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val flow: SharedFlow<String> = _flow.asSharedFlow()

    fun show(text: String) {
        _flow.tryEmit(text)
    }
}
