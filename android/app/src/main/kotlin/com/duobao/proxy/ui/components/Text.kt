package com.duobao.proxy.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import com.duobao.proxy.core.tr
import androidx.compose.material3.Text as M3Text

/** material3 `Text` with the string run through [tr] (设置 › 语言); every screen uses this one. */
@Composable
fun Text(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    maxLines: Int = Int.MAX_VALUE,
    style: TextStyle? = null,
) {
    val t = tr(text)
    if (style != null) {
        M3Text(t, modifier = modifier, color = color, fontSize = fontSize, fontWeight = fontWeight, fontFamily = fontFamily, textAlign = textAlign, lineHeight = lineHeight, overflow = overflow, maxLines = maxLines, style = style)
    } else {
        M3Text(t, modifier = modifier, color = color, fontSize = fontSize, fontWeight = fontWeight, fontFamily = fontFamily, textAlign = textAlign, lineHeight = lineHeight, overflow = overflow, maxLines = maxLines)
    }
}
