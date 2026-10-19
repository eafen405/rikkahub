package me.rerere.rikkahub.ui.identity

data class IdentityScope(val signedIn: Boolean, val isAdmin: Boolean) {
    companion object {
        val SignedOut = IdentityScope(signedIn = false, isAdmin = false)
    }
}

enum class IdentityDestination { SIGN_IN, REGISTER, ACCOUNT, SESSIONS, CONVERSATION, GATEWAY }

object IdentityAccessPolicy {
    fun mayCompose(destination: IdentityDestination?, identity: IdentityScope): Boolean = when (destination) {
        IdentityDestination.SIGN_IN, IdentityDestination.REGISTER -> !identity.signedIn
        IdentityDestination.ACCOUNT, IdentityDestination.SESSIONS, IdentityDestination.CONVERSATION -> identity.signedIn
        IdentityDestination.GATEWAY -> identity.signedIn && identity.isAdmin
        null -> false
    }
}
