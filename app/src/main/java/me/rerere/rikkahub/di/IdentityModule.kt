package me.rerere.rikkahub.di

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import me.rerere.rikkahub.data.remote.RemoteChatApi
import me.rerere.rikkahub.data.remote.RemoteChatBackend
import me.rerere.rikkahub.data.remote.RemoteChatState
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.data.remote.AndroidChatSyncStore
import me.rerere.rikkahub.data.remote.ChatSyncStore
import me.rerere.rikkahub.data.remote.AccountSession
import me.rerere.rikkahub.data.remote.AndroidKeystoreCredentialStore
import me.rerere.rikkahub.data.remote.CredentialStore
import me.rerere.rikkahub.data.remote.IdentityApi
import me.rerere.rikkahub.data.remote.IdentityBackend
import me.rerere.rikkahub.data.remote.SessionApi
import me.rerere.rikkahub.data.remote.SessionBackend
import okhttp3.OkHttpClient
import org.koin.dsl.module

/** Build-time deployment address; release requires an explicit Gradle property. */
private val identityEndpoint: String = BuildConfig.IDENTITY_ENDPOINT

/**
 * The identity wiring: one credential store, one API over the app's shared HTTP
 * client, and the single session that says who the app is acting as.
 *
 * Deliberately reuses the existing [OkHttpClient] singleton so the account
 * request obeys the same proxy, timeout and interceptor configuration as every
 * other request the app makes, rather than growing a second network stack.
 */
val identityModule = module {
    single<CredentialStore> {
        AndroidKeystoreCredentialStore(context = get<Context>())
    }

    single<IdentityBackend> {
        IdentityApi(baseUrl = identityEndpoint, client = get<OkHttpClient>())
    }

    single<SessionBackend> {
        SessionApi(baseUrl = identityEndpoint, client = get<OkHttpClient>())
    }

    single<RemoteChatBackend> {
        RemoteChatApi(baseUrl = identityEndpoint, client = get<OkHttpClient>())
    }

    single<ChatSyncStore> { AndroidChatSyncStore(get<Context>()) }

    single {
        RemoteChatState(
            get<RemoteChatBackend>(), CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            get<ChatSyncStore>(), identityEndpoint,
        )
    }

    single {
        AccountSession(
            backend = get<IdentityBackend>(),
            store = get<CredentialStore>(),
        )
    }
}
