package com.duobao.proxy.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.duobao.proxy.DuoBaoApplication
import com.duobao.proxy.data.asString
import com.duobao.proxy.ui.theme.ThemeMode
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.json.JsonObject

class AppViewModel(app: Application) : AndroidViewModel(app) {
    val store = (app as DuoBaoApplication).store

    val themeMode: StateFlow<ThemeMode> = store.ui
        .map(::themeOf)
        .stateIn(viewModelScope, SharingStarted.Eagerly, themeOf(store.ui.value))

    private fun themeOf(ui: JsonObject) = ThemeMode.from(ui["theme"].asString())
}
