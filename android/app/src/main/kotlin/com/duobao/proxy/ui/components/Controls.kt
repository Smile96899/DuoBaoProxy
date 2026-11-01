package com.duobao.proxy.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.duobao.proxy.ui.theme.DbpTheme

val CardShape = RoundedCornerShape(20.dp)

/** A line icon from [DbpIcons]. */
@Composable
fun DbpIcon(name: String, tint: Color, modifier: Modifier = Modifier, size: Dp = 20.dp) {
    Icon(DbpIcons.get(name), contentDescription = null, tint = tint, modifier = modifier.size(size))
}

/** The switch of the design: 44×26, accent when on; [onColor] overrides it (无网络 = alarm red). */
@Composable
fun DbpToggle(checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true, onColor: Color? = null) {
    val c = DbpTheme.colors
    val track by animateColorAsState(if (checked) onColor ?: c.accent else c.panel2, tween(150), label = "track")
    val x by animateDpAsState(if (checked) 20.dp else 3.dp, tween(150), label = "knob")
    Box(
        Modifier
            .alpha(if (enabled) 1f else 0.5f)
            .size(44.dp, 26.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(track)
            .clickable(enabled = enabled) { onChange(!checked) },
    ) {
        Box(Modifier.offset(x = x, y = 3.dp).size(20.dp).clip(CircleShape).background(Color.White))
    }
}

/** Big page title (首页 excepted) with an optional subtitle and a trailing action. */
@Composable
fun PageTitle(title: String, subtitle: String? = null, action: @Composable () -> Unit = {}) {
    val c = DbpTheme.colors
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = c.fg, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            if (subtitle != null) Text(subtitle, color = c.fg2, fontSize = 13.sp)
        }
        action()
    }
}

/** Header of a sub-page: `‹` and the title, optional trailing action. */
@Composable
fun SubPageHeader(title: String, onBack: () -> Unit, action: @Composable () -> Unit = {}) {
    val c = DbpTheme.colors
    Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 20.dp, top = 16.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onBack), contentAlignment = Alignment.Center) {
            DbpIcon("back", c.fg, size = 22.dp)
        }
        Text(title, color = c.fg, fontSize = 21.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        action()
    }
}

/** Small grey section label above a card (连接, 网络, …). */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, action: @Composable () -> Unit = {}) {
    Row(modifier.fillMaxWidth().padding(start = 24.dp, end = 20.dp, top = 14.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, color = DbpTheme.colors.fg2, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        action()
    }
}

/** Footnote under a card. */
@Composable
fun Footnote(text: String) {
    Text(text, color = DbpTheme.colors.fg2, fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp))
}

/** A bordered white card holding rows (rows draw their own dividers). */
@Composable
fun Card(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val c = DbpTheme.colors
    Column(
        modifier.padding(horizontal = 20.dp).fillMaxWidth().clip(CardShape).background(c.panel).border(1.dp, c.line, CardShape),
        content = content,
    )
}

/**
 * One row of a [Card]: optional [leading] tile, title + subtitle, [trailing] control. [divider]
 * draws the line above (every row but the first).
 */
@Composable
fun CardRow(
    title: String,
    subtitle: String? = null,
    divider: Boolean = false,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    titleColor: Color? = null,
    subtitleColor: Color? = null,
    mono: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val c = DbpTheme.colors
    if (divider) HorizontalDivider(thickness = 1.dp, color = c.line)
    Row(
        Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.5f)
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier)
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        leading?.invoke()
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = titleColor ?: c.fg,
                fontSize = if (mono) 15.sp else 16.sp,
                fontWeight = if (mono) FontWeight.Bold else FontWeight.Medium,
                fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrEmpty()) Text(subtitle, color = subtitleColor ?: c.fg2, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        trailing()
    }
}

/** The centred `＋ label` row at the bottom of a list card. */
@Composable
fun AddRow(label: String, onClick: () -> Unit) {
    val c = DbpTheme.colors
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DbpIcon("plus", c.accent, size = 20.dp)
        Spacer(Modifier.width(6.dp))
        Text(label, color = c.accent, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** `value ›` at the end of a navigation row. */
@Composable
fun ValueChevron(value: String = "") {
    val c = DbpTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (value.isNotEmpty()) Text(value, color = c.fg2, fontSize = 14.sp, maxLines = 1)
        DbpIcon("chevronRight", c.fg3, size = 18.dp)
    }
}

/** A rounded square icon tile (44 dp) for leading icons. */
@Composable
fun IconTile(icon: String, bg: Color, tint: Color, size: Dp = 44.dp, round: Boolean = false) {
    Box(
        Modifier.size(size).clip(if (round) CircleShape else RoundedCornerShape(14.dp)).background(bg),
        contentAlignment = Alignment.Center,
    ) {
        DbpIcon(icon, tint, size = size * 0.48f)
    }
}

/** A letter avatar (分应用代理 rows). */
@Composable
fun LetterTile(text: String, bg: Color) {
    Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(bg), contentAlignment = Alignment.Center) {
        Text(text, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
    }
}

/** Small pill badge (默认 / 自动更新 / 已过期 / 代理 / 直连 / 拦截). */
@Composable
fun Badge(text: String, bg: Color, fg: Color, onClick: (() -> Unit)? = null) {
    Text(
        text,
        color = fg,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = 3.dp),
    )
}

/** Outlined pill button with an optional icon (更新全部, 测试, 清空, 检查更新). [accent] tints it. */
@Composable
fun OutlinePill(text: String, onClick: () -> Unit, icon: String? = null, accent: Boolean = false, enabled: Boolean = true, danger: Boolean = false) {
    val c = DbpTheme.colors
    val fg = when {
        danger -> c.bad
        accent -> c.accent
        else -> c.fg
    }
    val border = if (accent) c.accent.copy(alpha = 0.45f) else c.line
    Row(
        Modifier
            .alpha(if (enabled) 1f else 0.45f)
            .height(36.dp)
            .clip(RoundedCornerShape(50))
            .background(c.panel)
            .border(1.dp, border, RoundedCornerShape(50))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) DbpIcon(icon, fg, size = 16.dp)
        Text(text, color = fg, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

/** The wide primary button at the bottom of forms (添加订阅, 保存并应用, 重新诊断). */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = DbpTheme.colors
    Box(
        modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth()
            .height(52.dp)
            .alpha(if (enabled) 1f else 0.45f)
            .clip(RoundedCornerShape(18.dp))
            .background(c.accent)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = c.accentFg, fontSize = 17.sp, fontWeight = FontWeight.Bold)
    }
}

/** Full-width segmented tabs: grey track, white selected tab with accent text. */
@Composable
fun <T> SegTabs(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    val c = DbpTheme.colors
    Row(
        modifier.padding(horizontal = 20.dp).fillMaxWidth().height(44.dp).clip(RoundedCornerShape(16.dp)).background(c.panel2).padding(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for ((v, label) in options) {
            val on = v == selected
            Box(
                Modifier
                    .weight(1f)
                    .height(38.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(if (on) c.panel else Color.Transparent)
                    .clickable { onSelect(v) },
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = if (on) c.accent else c.fg2, fontSize = 15.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}

/** Filter chips row item (连接日志: 全部 / 代理 / 直连 / 拦截). */
@Composable
fun FilterChip(text: String, on: Boolean, onClick: () -> Unit) {
    val c = DbpTheme.colors
    Text(
        text,
        color = if (on) c.accentFg else c.fg,
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (on) c.accent else c.panel)
            .then(if (on) Modifier else Modifier.border(1.dp, c.line, RoundedCornerShape(50)))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    )
}

/** Field label above a [TextBox]. */
@Composable
fun FieldLabel(text: String) {
    Text(text, color = DbpTheme.colors.fg2, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 14.dp, bottom = 8.dp))
}

/**
 * The white bordered input of the forms. [singleLine] false gives a text area of [minHeight];
 * [trailing] sits bottom-right inside it (the 粘贴 chip).
 */
@Composable
fun TextBox(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    singleLine: Boolean = true,
    mono: Boolean = false,
    numeric: Boolean = false,
    minHeight: Dp = 48.dp,
    leadingIcon: String? = null,
    onDone: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val c = DbpTheme.colors
    val shape = RoundedCornerShape(16.dp)
    val style = TextStyle(color = c.fg, fontSize = if (mono) 14.sp else 16.sp, fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default)
    CompositionLocalProvider(LocalTextSelectionColors provides TextSelectionColors(handleColor = c.accent, backgroundColor = c.accent.copy(alpha = 0.3f))) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            textStyle = style,
            cursorBrush = SolidColor(c.accent),
            keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text),
            keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
            modifier = modifier.fillMaxWidth().heightIn(min = minHeight).clip(shape).background(c.panel).border(1.dp, c.line, shape),
            decorationBox = { inner ->
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (leadingIcon != null) {
                            DbpIcon(leadingIcon, c.fg2, size = 20.dp)
                            Spacer(Modifier.width(12.dp))
                        }
                        Box(Modifier.weight(1f)) {
                            if (value.isEmpty() && placeholder.isNotEmpty()) Text(placeholder, style = style.copy(color = c.fg3))
                            inner()
                        }
                    }
                    if (trailing != null) {
                        Spacer(Modifier.weight(1f, fill = false))
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.BottomEnd) { trailing() }
                    }
                }
            },
        )
    }
}

/** An option row with an accent ✓ when chosen (内核 / 订阅更新 pickers). */
@Composable
fun CheckRow(title: String, checked: Boolean, divider: Boolean, subtitle: String? = null, onClick: () -> Unit) {
    val c = DbpTheme.colors
    CardRow(title, subtitle = subtitle, divider = divider, onClick = onClick) {
        if (checked) DbpIcon("check", c.accent, size = 20.dp)
    }
}

/** A row whose trailing value opens a small menu of [options]. */
@Composable
fun <T> MenuRow(title: String, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, divider: Boolean = false, subtitle: String? = null) {
    val c = DbpTheme.colors
    var open by remember { mutableStateOf(false) }
    val label = options.firstOrNull { it.first == selected }?.second ?: selected.toString()
    Box {
        CardRow(title, subtitle = subtitle, divider = divider, onClick = { open = true }) { ValueChevron(label) }
        // A zero-width anchor at the row's right end, so the menu drops under the value, right-aligned.
        Box(Modifier.align(Alignment.BottomEnd).padding(end = 12.dp).size(0.dp)) {
            DropdownMenu(
                expanded = open, onDismissRequest = { open = false }, containerColor = c.panel,
                shape = RoundedCornerShape(16.dp), modifier = Modifier.widthIn(min = 150.dp),
            ) {
                for ((v, l) in options) {
                    DropdownMenuItem(
                        text = { Text(l, color = if (v == selected) c.accent else c.fg, fontSize = 15.sp, fontWeight = if (v == selected) FontWeight.SemiBold else FontWeight.Normal) },
                        trailingIcon = if (v == selected) ({ DbpIcon("check", c.accent, size = 18.dp) }) else null,
                        onClick = { open = false; onSelect(v) },
                    )
                }
            }
        }
    }
}

/** A compact dropdown pill (首页 规则 ▾). [dot] draws the coloured status dot before the label. */
@Composable
fun <T> PillDropdown(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, dot: Color? = null) {
    val c = DbpTheme.colors
    var open by remember { mutableStateOf(false) }
    val label = options.firstOrNull { it.first == selected }?.second ?: selected.toString()
    Box {
        Row(
            Modifier
                .height(44.dp)
                .clip(RoundedCornerShape(50))
                .background(c.panel)
                .border(1.dp, c.line, RoundedCornerShape(50))
                .clickable { open = true }
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (dot != null) Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
            Text(label, color = c.fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            DbpIcon("chevronDown", c.fg2, size = 16.dp)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = c.panel, shape = RoundedCornerShape(16.dp)) {
            for ((v, l) in options) {
                DropdownMenuItem(
                    text = { Text(l, color = if (v == selected) c.accent else c.fg, fontSize = 15.sp, fontWeight = if (v == selected) FontWeight.SemiBold else FontWeight.Normal) },
                    onClick = { open = false; onSelect(v) },
                )
            }
        }
    }
}

/** A round 44 dp bordered icon button (首页 +). */
@Composable
fun RoundIconButton(icon: String, onClick: () -> Unit) {
    val c = DbpTheme.colors
    Box(
        Modifier.size(44.dp).clip(CircleShape).background(c.panel).border(1.dp, c.line, CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        DbpIcon(icon, c.fg, size = 22.dp)
    }
}
