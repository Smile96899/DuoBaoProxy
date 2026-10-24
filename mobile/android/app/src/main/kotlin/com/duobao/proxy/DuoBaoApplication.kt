package com.duobao.proxy

import android.app.Application
import com.duobao.proxy.data.Store

class DuoBaoApplication : Application() {
    val store: Store by lazy { Store(filesDir) }
}
