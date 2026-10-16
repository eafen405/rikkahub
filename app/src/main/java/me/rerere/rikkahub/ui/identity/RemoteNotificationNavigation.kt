package me.rerere.rikkahub.ui.identity

import me.rerere.rikkahub.data.remote.AccountSession

/** Intent coordinates identify a target; only the current account and backend may admit it. */
data class RemoteNotificationTarget(val endpoint: String, val userShortId: Int, val sessionId: String)

suspend fun openRemoteNotification(
    target: RemoteNotificationTarget,
    endpoint: String,
    account: AccountSession,
    openOwnedSession: suspend (String) -> Boolean,
): Boolean {
    val identity = account.identity.value ?: return false
    if (target.endpoint.trimEnd('/') != endpoint.trimEnd('/') || target.userShortId != identity.user.shortId) return false
    if (!openOwnedSession(target.sessionId)) return false
    return account.identity.value == identity
}
