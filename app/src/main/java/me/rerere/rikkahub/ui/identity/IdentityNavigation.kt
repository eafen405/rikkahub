package me.rerere.rikkahub.ui.identity

import androidx.navigation3.runtime.NavKey
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.remote.AccountSession

/** Restore once, then replace the provisional stack using the current session identity. */
suspend fun restoreIdentitySession(
    session: AccountSession,
    backStack: MutableList<NavKey>,
) {
    session.restore()
    backStack.clear()
    backStack.add(if (session.user != null) Screen.Account else Screen.SignIn)
}
