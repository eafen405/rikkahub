package me.rerere.rikkahub.ui.identity

import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.toIdentityDestination
import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenIdentityDestinationTest {

    @Test
    fun `the delivered identity screens map to their destinations`() {
        assertEquals(IdentityDestination.SIGN_IN, Screen.SignIn.toIdentityDestination())
        assertEquals(IdentityDestination.REGISTER, Screen.Register.toIdentityDestination())
        assertEquals(IdentityDestination.ACCOUNT, Screen.Home.toIdentityDestination())
        assertEquals(
            "the console is the signed-in landing page",
            IdentityDestination.ACCOUNT,
            Screen.Home.toIdentityDestination(),
        )
        assertEquals(IdentityDestination.ACCOUNT, Screen.Settings.toIdentityDestination())
        assertEquals(
            "a conversation is the delivered remote chat path",
            IdentityDestination.CONVERSATION,
            Screen.Conversation(sessionId = "s-1").toIdentityDestination(),
        )
        assertEquals(IdentityDestination.SESSIONS, Screen.Sessions.toIdentityDestination())
        assertEquals(
            "the gateway page is the delivered gateway entry",
            IdentityDestination.GATEWAY,
            Screen.Gateway.toIdentityDestination(),
        )
    }

}
