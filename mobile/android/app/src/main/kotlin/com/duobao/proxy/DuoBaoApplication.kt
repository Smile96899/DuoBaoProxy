package com.duobao.proxy

import android.app.Application
import com.duobao.proxy.core.GoCore
import com.duobao.proxy.data.Store
import com.duobao.proxy.state.AppController

class DuoBaoApplication : Application() {
    val store: Store by lazy { Store(filesDir) }

    /** The process-wide app state (`App.qml`). */
    val controller: AppController get() = AppController.instance

    override fun onCreate() {
        super.onCreate()
        // Setup (and the native library load) runs on GoCore's own thread; the Store loads here
        // because the controller, the tile and the receivers need it right away.
        GoCore.init(this)
        AppController.init(this, store)
    }
}
