package me.rerere.rikkahub.ui.identity

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The navigation gate.
 *
 * Correction (Lead): the first draft of this file contradicted itself — one
 * test required `mayCompose(GATEWAY, phaethon)` to be true while another
 * required it false for every identity including Phaethon, so no
 * implementation could satisfy both. The ruling is that being **undelivered**
 * dominates: an area whose ticket has not landed is refused for everybody, the
 * administrator included. Remote conversation is ticket #28 and the gateway is
 * ticket #43, so both are undelivered here and the positive assertions about
 * them were removed rather than the delivery rule.
 */
class IdentityAccessPolicyTest {
    private val signedOut = IdentityScope.SignedOut
    private val agent = IdentityScope(signedIn = true, isAdmin = false)
    private val phaethon = IdentityScope(signedIn = true, isAdmin = true)

    @Test
    fun `while signed out only the sign-in areas may be composed`() {
        assertTrue(IdentityAccessPolicy.mayCompose(IdentityDestination.SIGN_IN, signedOut))
        assertTrue(IdentityAccessPolicy.mayCompose(IdentityDestination.REGISTER, signedOut))
        assertFalse(IdentityAccessPolicy.mayCompose(IdentityDestination.CONVERSATION, signedOut))
        assertFalse(IdentityAccessPolicy.mayCompose(IdentityDestination.GATEWAY, signedOut))
        assertFalse(IdentityAccessPolicy.mayCompose(IdentityDestination.INVITES, signedOut))
    }

    @Test
    fun `an agent never reaches the gateway or the invite operation`() {
        assertFalse(IdentityAccessPolicy.mayCompose(IdentityDestination.GATEWAY, agent))
        assertFalse(IdentityAccessPolicy.mayCompose(IdentityDestination.INVITES, agent))
        assertFalse(
            "a signed-in identity has no business back on the credential pages",
            IdentityAccessPolicy.mayCompose(IdentityDestination.SIGN_IN, agent),
        )
        assertFalse(IdentityAccessPolicy.mayCompose(IdentityDestination.REGISTER, agent))
    }

    @Test
    fun `phaethon mints invites and still reaches no undelivered area`() {
        assertTrue(IdentityAccessPolicy.mayCompose(IdentityDestination.INVITES, phaethon))
        assertFalse(
            "the gateway is ticket #43, not this one",
            IdentityAccessPolicy.mayCompose(IdentityDestination.GATEWAY, phaethon),
        )
        assertFalse(
            "remote conversation is ticket #28, not this one",
            IdentityAccessPolicy.mayCompose(IdentityDestination.CONVERSATION, phaethon),
        )
    }

    @Test
    fun `an undelivered area is refused for every identity`() {
        for (identity in listOf(signedOut, agent, phaethon)) {
            assertFalse(
                "an undelivered area must not be reachable by any identity",
                IdentityAccessPolicy.mayCompose(IdentityDestination.GATEWAY, identity),
            )
        }
        assertFalse(IdentityAccessPolicy.isDelivered(IdentityDestination.GATEWAY))
        assertFalse(IdentityAccessPolicy.isDelivered(IdentityDestination.CONVERSATION))
        assertTrue(IdentityAccessPolicy.isDelivered(IdentityDestination.INVITES))
    }

    @Test
    fun `a legacy destination stays impassable even for the administrator`() {
        // Every rikkahub screen the first version does not deliver maps to no
        // destination, so the gate never composes its page — local model
        // execution and provider/system management are unreachable, not hidden.
        for (identity in listOf(signedOut, agent, phaethon)) {
            assertFalse(IdentityAccessPolicy.mayCompose(destination = null, identity = identity))
        }
    }
}
