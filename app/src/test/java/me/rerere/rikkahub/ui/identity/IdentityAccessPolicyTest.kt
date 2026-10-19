package me.rerere.rikkahub.ui.identity

import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.toIdentityDestination
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentityAccessPolicyTest {
    private val agent = IdentityScope(signedIn = true, isAdmin = false)
    private val phaethon = IdentityScope(signedIn = true, isAdmin = true)

    @Test
    fun `signed in users can enter the conversation destination used by notifications and home`() {
        val destination = Screen.Conversation("main").toIdentityDestination()
        assertTrue(IdentityAccessPolicy.mayCompose(destination, agent))
        assertTrue(IdentityAccessPolicy.mayCompose(destination, phaethon))
        assertFalse(IdentityAccessPolicy.mayCompose(destination, IdentityScope.SignedOut))
    }

    @Test
    fun `visitors enter credentials and signed in users enter their pages`() {
        for (screen in listOf(Screen.SignIn, Screen.Register)) {
            assertTrue(IdentityAccessPolicy.mayCompose(screen.toIdentityDestination(), IdentityScope.SignedOut))
            assertFalse(IdentityAccessPolicy.mayCompose(screen.toIdentityDestination(), agent))
        }
        for (screen in listOf(Screen.Home, Screen.Settings, Screen.Sessions)) {
            assertTrue(IdentityAccessPolicy.mayCompose(screen.toIdentityDestination(), agent))
            assertTrue(IdentityAccessPolicy.mayCompose(screen.toIdentityDestination(), phaethon))
            assertFalse(IdentityAccessPolicy.mayCompose(screen.toIdentityDestination(), IdentityScope.SignedOut))
        }
    }

    @Test
    fun `only signed in phaethon can open the gateway`() {
        val destination = Screen.Gateway.toIdentityDestination()
        assertTrue(IdentityAccessPolicy.mayCompose(destination, phaethon))
        assertFalse(IdentityAccessPolicy.mayCompose(destination, agent))
        assertFalse(IdentityAccessPolicy.mayCompose(destination, IdentityScope.SignedOut))
        assertFalse(IdentityAccessPolicy.mayCompose(destination, IdentityScope(false, true)))
    }
}
