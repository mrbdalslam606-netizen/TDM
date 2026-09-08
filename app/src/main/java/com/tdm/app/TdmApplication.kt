package com.tdm.app

import android.app.Application
import com.tdm.app.di.AppContainer
import com.tdm.app.telegram.TdlibClient

class TdmApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer.get(this)
        // TDLib log noise reduction in release; keep errors visible
        TdlibClient.setTdlibLogLevel(2)
    }
}
