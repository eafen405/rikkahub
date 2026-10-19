package me.rerere.rikkahub

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.network.cachecontrol.CacheControlCacheStrategy
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import coil3.svg.SvgDecoder
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import me.rerere.rikkahub.data.datastore.DisplayPreferencesStore
import me.rerere.rikkahub.data.datastore.LocalDisplayPreferences
import me.rerere.rikkahub.data.remote.AccountSession
import me.rerere.rikkahub.service.AndroidRemoteNotifications
import me.rerere.rikkahub.service.RemoteReceiveController
import me.rerere.rikkahub.ui.fairy.FairyAppTheme
import me.rerere.rikkahub.ui.fairy.FairyConsolePage
import me.rerere.rikkahub.ui.fairy.FairyConversationPage
import me.rerere.rikkahub.ui.fairy.FairyLastLocation
import me.rerere.rikkahub.ui.fairy.FairySettingsPage
import me.rerere.rikkahub.ui.identity.GatewayPage
import me.rerere.rikkahub.ui.identity.IdentityAccessPolicy
import me.rerere.rikkahub.ui.identity.IdentityDestination
import me.rerere.rikkahub.ui.identity.IdentityNotReadyPage
import me.rerere.rikkahub.ui.identity.IdentityRegisterPage
import me.rerere.rikkahub.ui.identity.IdentityScope
import me.rerere.rikkahub.ui.identity.IdentitySignInPage
import me.rerere.rikkahub.ui.identity.openRemoteNotification
import me.rerere.rikkahub.ui.identity.restoreIdentitySession
import okhttp3.OkHttpClient
import org.koin.android.ext.android.inject

class RouteActivity : ComponentActivity() {
    private val okHttpClient by inject<OkHttpClient>()
    private val displayPreferences by inject<DisplayPreferencesStore>()
    private val remoteReceiveController by inject<RemoteReceiveController>()
    private val accountSession by inject<AccountSession>()
    private var navStack: MutableList<NavKey>? = null
    private var identityReady = false
    private val pendingIntents = ArrayDeque<Intent>()

    @OptIn(coil3.annotation.ExperimentalCoilApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) window.isNavigationBarContrastEnforced = false
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            setSingletonImageLoaderFactory { context ->
                ImageLoader.Builder(context).crossfade(true).components {
                    add(OkHttpNetworkFetcherFactory(
                        callFactory = { okHttpClient },
                        cacheStrategy = { CacheControlCacheStrategy() },
                    ))
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) add(AnimatedImageDecoder.Factory())
                    else add(GifDecoder.Factory())
                    add(SvgDecoder.Factory(scaleToDensity = true))
                }.build()
            }
            FairyAppTheme { AppRoutes() }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        if (intent.action != AndroidRemoteNotifications.ACTION_OPEN_REMOTE) return
        val backStack = navStack?.takeIf { identityReady } ?: run {
            pendingIntents.addLast(intent)
            return
        }
        val target = AndroidRemoteNotifications.target(intent) ?: return
        lifecycleScope.launch {
            if (openRemoteNotification(target, BuildConfig.IDENTITY_ENDPOINT, accountSession, remoteReceiveController::openNotification)) {
                backStack.clear()
                backStack.add(Screen.Home)
                backStack.add(Screen.Conversation(target.sessionId))
            }
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Composable
    fun AppRoutes() {
        val preferences by displayPreferences.state.collectAsStateWithLifecycle()
        val session = accountSession
        // Reception must own the account before restoration publishes its identity.
        remember { remoteReceiveController }
        val currentIdentity by session.identity.collectAsStateWithLifecycle()
        val user = currentIdentity?.user
        var identitySettled by remember { mutableStateOf(false) }
        val identityScope = IdentityScope(signedIn = user != null, isAdmin = user?.isAdmin == true)
        val backStack = rememberNavBackStack(Screen.SignIn)
        val lastLocation = remember { FairyLastLocation(this@RouteActivity) }
        LaunchedEffect(backStack.lastOrNull()) {
            (backStack.lastOrNull() as? Screen)?.let(lastLocation::record)
        }
        SideEffect {
            navStack = backStack
            if (identitySettled) {
                while (pendingIntents.isNotEmpty()) handleIntent(pendingIntents.removeFirst())
            }
        }
        LaunchedEffect(Unit) {
            try {
                restoreIdentitySession(session, backStack, lastLocation)
            } finally {
                identityReady = true
                identitySettled = true
            }
        }
        CompositionLocalProvider(LocalDisplayPreferences provides preferences) {
            Box(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }.background(MaterialTheme.colorScheme.background)) {
                val destination = (backStack.lastOrNull() as? Screen)?.toIdentityDestination()
                when {
                    !identitySettled -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    IdentityAccessPolicy.mayCompose(destination, identityScope) -> NavDisplay(
                        backStack = backStack,
                        entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator()),
                        modifier = Modifier.fillMaxSize(),
                        onBack = { backStack.removeLastOrNull() },
                        transitionSpec = {
                            if (backStack.size == 1) fadeIn() togetherWith fadeOut()
                            else slideInHorizontally { it } togetherWith
                                slideOutHorizontally { -it / 2 } + scaleOut(targetScale = 0.7f) + fadeOut()
                        },
                        popTransitionSpec = {
                            slideInHorizontally { -it / 2 } + scaleIn(initialScale = 0.7f) + fadeIn() togetherWith slideOutHorizontally { it }
                        },
                        predictivePopTransitionSpec = {
                            slideInHorizontally { -it / 2 } + scaleIn(initialScale = 0.7f) + fadeIn() togetherWith slideOutHorizontally { it }
                        },
                        entryProvider = entryProvider {
                            entry<Screen.SignIn> {
                                IdentitySignInPage(
                                    onSignedIn = {
                                        backStack.clear()
                                        backStack.add(Screen.Home)
                                    },
                                    onRegister = {
                                        backStack.clear()
                                        backStack.add(Screen.Register)
                                    },
                                )
                            }

                            entry<Screen.Register> {
                                IdentityRegisterPage(
                                    onRegistered = {
                                        backStack.clear()
                                        backStack.add(Screen.Home)
                                    },
                                    onBackToSignIn = {
                                        backStack.clear()
                                        backStack.add(Screen.SignIn)
                                    },
                                )
                            }

                            entry<Screen.Home> {
                                FairyConsolePage(
                                    onOpen = { backStack.add(it) },
                                )
                            }

                            entry<Screen.Settings> {
                                FairySettingsPage(
                                    onOpenGateway = { backStack.add(Screen.Gateway) },
                                    onSignedOut = {
                                        backStack.clear()
                                        backStack.add(Screen.SignIn)
                                    },
                                    onBack = { backStack.removeLastOrNull() },
                                )
                            }

                            entry<Screen.Conversation> { key ->
                                FairyConversationPage(
                                    sessionId = key.sessionId,
                                    onBack = {
                                        backStack.clear()
                                        backStack.add(Screen.Home)
                                    },
                                    onOpenConversation = { id -> backStack[backStack.lastIndex] = Screen.Conversation(id) },
                                    onSettings = { backStack.add(Screen.Settings) },
                                )
                            }

                            entry<Screen.Sessions> {
                                FairyConversationPage(
                                    sessionId = null,
                                    onBack = {
                                        backStack.clear()
                                        backStack.add(Screen.Home)
                                    },
                                    onOpenConversation = { id -> backStack[backStack.lastIndex] = Screen.Conversation(id) },
                                    onSettings = { backStack.add(Screen.Settings) },
                                )
                            }

                            entry<Screen.Gateway> {
                                GatewayPage(
                                    onBack = { backStack.removeLastOrNull() },
                                )
                            }

                        },
                    )
                    else -> IdentityNotReadyPage(identity = identityScope, onSignIn = {
                        backStack.clear()
                        backStack.add(Screen.SignIn)
                    })
                }
            }
        }
    }
}

internal fun Screen.toIdentityDestination(): IdentityDestination = when (this) {
    Screen.SignIn -> IdentityDestination.SIGN_IN
    Screen.Register -> IdentityDestination.REGISTER
    Screen.Home, Screen.Settings -> IdentityDestination.ACCOUNT
    Screen.Sessions -> IdentityDestination.SESSIONS
    is Screen.Conversation -> IdentityDestination.CONVERSATION
    Screen.Gateway -> IdentityDestination.GATEWAY
}

sealed interface Screen : NavKey {
    @Serializable data object SignIn : Screen
    @Serializable data object Register : Screen
    @Serializable data object Home : Screen
    @Serializable data object Sessions : Screen
    @Serializable data class Conversation(val sessionId: String) : Screen
    @Serializable data object Settings : Screen
    @Serializable data object Gateway : Screen
}
