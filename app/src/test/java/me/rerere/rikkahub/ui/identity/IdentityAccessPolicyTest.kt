package me.rerere.rikkahub.ui.identity

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The navigation gate.
 *
 * Correction history (Lead), kept because a future reader will otherwise
 * re-derive the same two mistakes:
 *
 *  1. The first draft contradicted itself — one test required
 *     `mayCompose(GATEWAY, phaethon)` to be true while another required it
 *     false for every identity including Phaethon, so no implementation could
 *     satisfy both. The ruling is that being **undelivered** dominates: an area
 *     whose ticket has not landed is refused for everybody, the administrator
 *     included. Remote conversation is ticket #28 and the gateway was #43.
 *  2. The second draft then left the ticket's "按身份进入可用区域" unmet, because
 *     a signed-in agent had nowhere to land at all. That is what
 *     [IdentityDestination.ACCOUNT] is for: the identity area this release
 *     actually delivers, which is where each identity lands and where the
 *     administrator additionally finds the invite operation.
 *
 * Ticket #43 has since landed, so the gateway is now **delivered** — for the
 * administrator alone. The conversation stays undelivered: the delivered
 * conversation path is the sessions page, not the legacy local `Screen.Chat`.
 */
class IdentityAccessPolicyTest {
    private val signedOut = IdentityScope.SignedOut
    private val agent = IdentityScope(signedIn = true, isAdmin = false)
    private val phaethon = IdentityScope(signedIn = true, isAdmin = true)

    @Test
    fun `while signed out only the sign-in areas may be composed`() {
        assertTrue(IdentityAccessPolicy.mayCompose(IdentityDestination.SIGN_IN, signedOut))
        assertTrue(IdentityAccessPolicy.mayCompose(IdentityDestination.REGISTER, signedOut))
        assertFalse(IdentityAccessPolicy.mayCompose(IdentityDestination.ACCOUNT, signedOut))
        assertFalse(IdentityAccessPolicy.mayCompose(IdentityDestination.CONVERSATION, signedOut))
        assertFalse(IdentityAccessPolicy.mayCompose(IdentityDestination.GATEWAY, signedOut))
        assertFalse(IdentityAccessPolicy.mayCompose(IdentityDestination.INVITES, signedOut))
    }

    @Test
    fun `an agent lands on the account area and never on the invite operation`() {
        assertTrue(
            "a signed-in agent must have somewhere to land in this release",
            IdentityAccessPolicy.mayCompose(IdentityDestination.ACCOUNT, agent),
        )
        assertFalse(IdentityAccessPolicy.mayCompose(IdentityDestination.INVITES, agent))
        assertFalse(
            "ordinary agents do not configure the shared gateway",
            IdentityAccessPolicy.mayCompose(IdentityDestination.GATEWAY, agent),
        )
        assertFalse(
            "a signed-in identity has no business back on the credential pages",
            IdentityAccessPolicy.mayCompose(IdentityDestination.SIGN_IN, agent),
        )
        assertFalse(IdentityAccessPolicy.mayCompose(IdentityDestination.REGISTER, agent))
    }

    @Test
    fun `phaethon reaches the invite operation the gateway and the account area`() {
        assertTrue(IdentityAccessPolicy.mayCompose(IdentityDestination.ACCOUNT, phaethon))
        assertTrue(IdentityAccessPolicy.mayCompose(IdentityDestination.INVITES, phaethon))
        assertTrue(
            "the administrator configures the shared gateway from the same APK",
            IdentityAccessPolicy.mayCompose(IdentityDestination.GATEWAY, phaethon),
        )
        assertTrue(IdentityAccessPolicy.isDelivered(IdentityDestination.GATEWAY))
    }

    @Test
    fun `the gateway is delivered for the administrator alone`() {
        assertTrue(IdentityAccessPolicy.isDelivered(IdentityDestination.GATEWAY))
        assertTrue(IdentityAccessPolicy.mayCompose(IdentityDestination.GATEWAY, phaethon))
        assertFalse(IdentityAccessPolicy.mayCompose(IdentityDestination.GATEWAY, agent))
        assertFalse(IdentityAccessPolicy.mayCompose(IdentityDestination.GATEWAY, signedOut))
        assertTrue(IdentityAccessPolicy.isDelivered(IdentityDestination.ACCOUNT))
        assertTrue(IdentityAccessPolicy.isDelivered(IdentityDestination.INVITES))
    }

    @Test
    fun `the conversation is refused while it would still run models locally`() {
        // rikkahub already has a chat page, so it is tempting to call it
        // delivered because it exists. It is refused instead: that page drives
        // local model execution, which this release must not leave triggerable.
        // The delivered conversation path is the sessions page, not Screen.Chat.
        assertFalse(IdentityAccessPolicy.isDelivered(IdentityDestination.CONVERSATION))
        for (identity in listOf(signedOut, agent, phaethon)) {
            assertFalse(IdentityAccessPolicy.mayCompose(IdentityDestination.CONVERSATION, identity))
        }
    }

    @Test
    fun `an undelivered area is refused for every identity`() {
        // The conversation is still undelivered; checking it through the same
        // rule that refuses every unknown destination keeps the "delivered
        // dominates" rule honest after the gateway landed.
        for (identity in listOf(signedOut, agent, phaethon)) {
            assertFalse(
                "an undelivered area must not be reachable by any identity",
                IdentityAccessPolicy.mayCompose(IdentityDestination.CONVERSATION, identity),
            )
        }
        assertFalse(IdentityAccessPolicy.isDelivered(IdentityDestination.CONVERSATION))
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
