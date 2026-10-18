package me.rerere.rikkahub.ui.identity

import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.toIdentityDestination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which navigation keys the gate knows.
 *
 * The gate in `RouteActivity` asks this mapping before it composes anything, so
 * this is the seam that decides whether a screen is even subject to a policy.
 * A key this mapping does not know is refused for everybody — that is what keeps
 * every legacy local-execution and system-management screen impassable.
 *
 * These run as plain JVM tests: [Screen] is a sealed interface of serializable
 * values, so naming one needs no Android framework.
 */
class ScreenIdentityDestinationTest {

    @Test
    fun `the delivered identity screens map to their destinations`() {
        assertEquals(IdentityDestination.SIGN_IN, Screen.SignIn.toIdentityDestination())
        assertEquals(IdentityDestination.REGISTER, Screen.Register.toIdentityDestination())
        assertEquals(IdentityDestination.ACCOUNT, Screen.Account.toIdentityDestination())
        assertEquals(IdentityDestination.SESSIONS, Screen.Sessions.toIdentityDestination())
        assertEquals(
            "the gateway page is the delivered gateway entry",
            IdentityDestination.GATEWAY,
            Screen.Gateway.toIdentityDestination(),
        )
    }

    @Test
    fun `the legacy local chat is not the delivered conversation`() {
        assertNull(
            "Screen.Chat still drives local model execution",
            Screen.Chat(id = "local").toIdentityDestination(),
        )
    }

    @Test
    fun `every screen this release does not deliver maps to no destination`() {
        val legacy = listOf(
            Screen.Setting,
            Screen.SettingProvider,
            Screen.SettingModels,
            Screen.SettingWeb,
            Screen.SettingMcp,
            Screen.Backup,
            Screen.Extensions,
            Screen.Assistant,
            Screen.History,
            Screen.Favorite,
            Screen.WebView(),
        )

        for (screen in legacy) {
            assertNull(
                "a screen with no destination must stay refused for every identity",
                screen.toIdentityDestination(),
            )
        }
    }
}
