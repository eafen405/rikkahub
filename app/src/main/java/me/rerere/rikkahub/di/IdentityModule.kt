package me.rerere.rikkahub.di

import android.content.Context
import me.rerere.rikkahub.data.remote.AccountSession
import me.rerere.rikkahub.data.remote.AndroidKeystoreCredentialStore
import me.rerere.rikkahub.data.remote.CredentialStore
import me.rerere.rikkahub.data.remote.IdentityApi
import me.rerere.rikkahub.data.remote.IdentityBackend
import okhttp3.OkHttpClient
import org.koin.dsl.module

/**
 * Where this build reaches the OurFairy backend.
 *
 * `10.0.2.2` is the host machine as seen from an Android emulator. A build for a
 * phone has to point at wherever that backend actually is, which is a
 * deployment concern rather than a code one — note it as such instead of
 * pretending a hardcoded host ships.
 */
const val IDENTITY_BASE_URL: String = "http://10.0.2.2:3080"

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
        IdentityApi(baseUrl = IDENTITY_BASE_URL, client = get<OkHttpClient>())
    }

    single {
        AccountSession(
            backend = get<IdentityBackend>(),
            store = get<CredentialStore>(),
        )
    }
}
