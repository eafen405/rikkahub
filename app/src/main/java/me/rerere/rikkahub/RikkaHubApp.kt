package me.rerere.rikkahub

import android.app.Application
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import me.rerere.rikkahub.di.appModule
import me.rerere.rikkahub.di.identityModule
import org.koin.android.ext.android.get
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class RikkaHubApp : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@RikkaHubApp)
            modules(appModule, identityModule)
        }
    }

    override fun onTerminate() {
        get<AppScope>().cancel()
        super.onTerminate()
    }
}

class AppScope : CoroutineScope by CoroutineScope(
    SupervisorJob() + Dispatchers.Main + CoroutineName("OurFairy") +
        CoroutineExceptionHandler { _, error -> Log.e("OurFairy", "App task failed", error) }
)
