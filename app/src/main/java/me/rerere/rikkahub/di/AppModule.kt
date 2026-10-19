package me.rerere.rikkahub.di

import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.data.datastore.DisplayPreferencesStore
import okhttp3.OkHttpClient
import org.koin.dsl.module
import java.util.Locale
import java.util.concurrent.TimeUnit

val appModule = module {
    single { AppScope() }
    single { DisplayPreferencesStore(get()) }
    single {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.MINUTES)
            .writeTimeout(120, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder()
                    .header("Accept-Language", Locale.getDefault().toLanguageTag())
                    .header("User-Agent", "OurFairy-Android/${BuildConfig.VERSION_NAME}")
                    .build())
            }
            .build()
    }
}
