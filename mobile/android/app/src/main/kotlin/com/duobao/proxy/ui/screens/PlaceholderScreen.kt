package com.duobao.proxy.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.duobao.proxy.ui.Destination
import com.duobao.proxy.ui.theme.DbpTheme

@Composable
fun PlaceholderScreen(destination: Destination) {
    val c = DbpTheme.colors
    Box(Modifier.fillMaxSize().background(c.bg), contentAlignment = Alignment.Center) {
        Text(stringResource(destination.label), color = c.fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}
