package com.byanr.veena.android

import android.app.Application
import com.byanr.veena.android.audio.MicRecorder
import com.byanr.veena.android.data.HistoryDatabase
import com.byanr.veena.android.data.ModelRepository
import com.byanr.veena.android.data.Prefs
import com.byanr.veena.android.engine.EngineManager
import okhttp3.OkHttpClient
import java.io.File

/** Small manual DI container; everything the app needs lives here. */
class AppContainer(app: Application) {
    val prefs = Prefs(app)
    val models = ModelRepository(app, OkHttpClient())
    val engine = EngineManager(models)
    val recorder = MicRecorder()
    val history = HistoryDatabase.build(app).historyDao()
    val recordingsDir: File = File(app.filesDir, "recordings").apply { mkdirs() }
}

class VeenaApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
