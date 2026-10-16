package me.rerere.rikkahub.di

import android.content.Context
import me.rerere.rikkahub.data.remote.RemoteAttachmentApi
import me.rerere.rikkahub.data.remote.RemoteSpeechApi
import me.rerere.rikkahub.data.remote.RemoteSpeechBackend
import me.rerere.rikkahub.data.remote.RemoteAttachmentBackend
import me.rerere.rikkahub.data.remote.RemoteChatApi
import me.rerere.rikkahub.data.remote.RemoteChatBackend
import me.rerere.rikkahub.data.remote.RemoteJobApi
import me.rerere.rikkahub.data.remote.RemoteJobBackend
import me.rerere.rikkahub.data.remote.RemoteChatState
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.data.remote.RemoteReceiveState
import me.rerere.rikkahub.service.AndroidRemoteNotifications
import me.rerere.rikkahub.service.RemoteReceiveController
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

    single<RemoteAttachmentBackend> { RemoteAttachmentApi(identityEndpoint, get<OkHttpClient>()) }

    single<RemoteSpeechBackend> { RemoteSpeechApi(identityEndpoint, get<OkHttpClient>()) }

    single<RemoteJobBackend> { RemoteJobApi(identityEndpoint, get<OkHttpClient>()) }

    single<ChatSyncStore> { AndroidChatSyncStore(get<Context>()) }

    single { AndroidRemoteNotifications(get<Context>()) }

    single {
        val runtimeScope = get<AppScope>()
        val notifications = get<AndroidRemoteNotifications>()
        val chatBackend = get<RemoteChatBackend>()
        val syncStore = get<ChatSyncStore>()
        val files = get<RemoteAttachmentBackend>()
        val jobs = get<RemoteJobBackend>()
        RemoteReceiveState(get<SessionBackend>(), runtimeScope) {
            RemoteChatState(
                chatBackend, runtimeScope, syncStore, identityEndpoint,
                attachmentsBackend = files, jobsBackend = jobs,
                speechBackend = get<RemoteSpeechBackend>(),
                notifications = notifications, notificationsEnabled = { notifications.enabled.value },
            )
        }
    }

    single {
        RemoteReceiveController(get<Context>(), get<AccountSession>(), get<RemoteReceiveState>(),
            get<AndroidRemoteNotifications>(), get<AppScope>())
    }

    single {
        AccountSession(
            backend = get<IdentityBackend>(),
            store = get<CredentialStore>(),
        )
    }
}
