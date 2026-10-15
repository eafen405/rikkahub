package me.rerere.rikkahub.di

import android.content.Context
import me.rerere.rikkahub.BuildConfig
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
 * The address is a deployment concern, not a code one, so it arrives from the
 * build: `app/build.gradle.kts` reads the `ourfairy.identityEndpoint` Gradle
 * property and writes [BuildConfig.IDENTITY_ENDPOINT], which is what
 * [identityBackend] below is constructed from. The emulator loopback default is
 * a debug convenience only — a release build without an explicit endpoint fails
 * rather than shipping a machine address that resolves nowhere.
 *
 * Build-time, because there is exactly one backend per build and no reason to
 * let a device be pointed somewhere a release was not signed to expect.
 */
private val identityEndpoint: String = resolveIdentityEndpoint(BuildConfig.IDENTITY_ENDPOINT)

/**
 * The single place the build's endpoint is accepted.
 *
 * Only enough is required to fail loudly: a blank value means the build was
 * never told where the backend is, and anything that is not an absolute
 * http(s) URL with a host cannot be dialled. The value is trimmed and the
 * trailing slash removed so URL joining stays the caller's job.
 *
 * @throws IllegalStateException when the build carries no usable endpoint.
 */
internal fun resolveIdentityEndpoint(configured: String): String {
    val endpoint = configured.trim()
    if (endpoint.isEmpty()) {
        throw IllegalStateException(
            "This build carries no OurFairy backend address. Build it with " +
                "-Pourfairy.identityEndpoint=https://<your-backend-host> (see README, " +
                "\"Pointing a build at a backend\")."
        )
    }
    val scheme = endpoint.substringBefore("://", missingDelimiterValue = "")
    val host = endpoint.substringAfter("://", missingDelimiterValue = "").substringBefore('/')
    if (scheme != "http" && scheme != "https") {
        throw IllegalStateException("The OurFairy backend address must be http:// or https://, but was '$configured'.")
    }
    if (host.isEmpty() || host.any { it.isWhitespace() }) {
        throw IllegalStateException("The OurFairy backend address '$configured' names no host.")
    }
    return endpoint.trimEnd('/')
}

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

    single {
        AccountSession(
            backend = get<IdentityBackend>(),
            store = get<CredentialStore>(),
        )
    }
}
