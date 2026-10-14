package me.rerere.rikkahub.ui.identity

/**
 * Who the app is acting as, as far as navigation is concerned.
 *
 * Deliberately not the session object itself: the gate must be answerable in a
 * unit test without a network, a key store or an Android framework.
 */
data class IdentityScope(
    val signedIn: Boolean,
    val isAdmin: Boolean,
) {
    companion object {
        val SignedOut = IdentityScope(signedIn = false, isAdmin = false)
    }
}

/**
 * The navigation destinations that are gated by identity.
 *
 * This is not the full set of destinations in the product — it is the set the
 * first version actually delivers. Every other screen rikkahub already has
 * (provider and model configuration, extensions, local tool execution, system
 * management) maps to no destination at all, so it has no key in the gate and
 * therefore no page to compose.
 */
enum class IdentityDestination {
    SIGN_IN,
    REGISTER,
    ACCOUNT,
    CONVERSATION,
    GATEWAY,
    INVITES,
}

/**
 * Which areas the app may actually compose for a given identity.
 *
 * Two independent questions, both required:
 *
 *  1. **Is the area delivered at all?** An area whose ticket has not landed is
 *     not "permission-gated" — it must be impossible to compose for anybody,
 *     including the administrator. Gating it by identity alone would leave a
 *     reachable screen behind a flag, which is hiding, not closing.
 *  2. **May this identity reach it?** Sign-in areas belong to signed-out
 *     visitors; the account area and invite operation belong to a signed-in
 *     identity, and minting invites to the administrator alone.
 *
 * A destination of `null` means "a screen this gate does not know", and it is
 * refused for everyone. That is what makes the legacy local-execution and
 * system-management paths impassable rather than merely hidden: they are
 * composed through destinations that do not exist here.
 *
 * **Delivered by this ticket:** [IdentityDestination.SIGN_IN],
 * [IdentityDestination.REGISTER], [IdentityDestination.ACCOUNT] and
 * [IdentityDestination.INVITES]. Remote conversation is ticket #28 and the
 * gateway is ticket #43; until those land their destinations are deliberately
 * undelivered, so they compose the explicit not-ready page and never a working
 * feature.
 *
 * The conversation destination deserves a note: rikkahub already *has* a chat
 * page, and it is tempting to call it delivered because it exists. It is not,
 * because that page drives local model execution — exactly the path the first
 * version must not leave triggerable. It becomes composable when ticket #28
 * makes it a remote conversation.
 */
object IdentityAccessPolicy {

    /** Areas this release delivers. Everything else is refused for every identity. */
    private val DELIVERED = setOf(
        IdentityDestination.SIGN_IN,
        IdentityDestination.REGISTER,
        IdentityDestination.ACCOUNT,
        IdentityDestination.INVITES,
    )

    /** Whether this release has an implementation behind the destination. */
    fun isDelivered(destination: IdentityDestination): Boolean = destination in DELIVERED

    /**
     * Whether [destination] may be composed for [identity].
     *
     * @param destination the gated destination, or null for a screen the gate
     *   does not know — always refused.
     */
    fun mayCompose(destination: IdentityDestination?, identity: IdentityScope): Boolean {
        if (destination == null || !isDelivered(destination)) return false
        return when (destination) {
            // Credential entry belongs to visitors; a signed-in identity has no
            // business back on the sign-in pages.
            IdentityDestination.SIGN_IN,
            IdentityDestination.REGISTER,
            -> !identity.signedIn

            // The identity area this release actually delivers: who you are,
            // and the operations your identity is allowed to perform.
            IdentityDestination.ACCOUNT -> identity.signedIn

            // Minting invites is the administrator's own operation. An ordinary
            // agent is refused here by the policy as well as by the backend.
            IdentityDestination.INVITES -> identity.isAdmin

            // Not delivered by this ticket; the rule above already refused it.
            // Listed so that landing the implementation makes the compiler
            // revisit this decision instead of silently picking a default.
            IdentityDestination.CONVERSATION,
            IdentityDestination.GATEWAY,
            -> identity.signedIn
        }
    }
}
