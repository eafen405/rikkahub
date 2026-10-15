package me.rerere.rikkahub.data.remote

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * What the sessions page is currently showing.
 *
 * [selectedId] is this phone's own choice of which session the user is standing
 * in. It is not ownership and not history: it is a convenience pointer that the
 * backend is free to disagree with, and it is worthless without the credential
 * that fetched it.
 */
data class SessionsSnapshot(
    val sessions: List<OwnedSession> = emptyList(),
    val primarySessionId: String? = null,
    val selectedId: String? = null,
    val loading: Boolean = false,
    val failure: String? = null,
) {
    companion object {
        /**
         * Marks "the backend could not be reached", which carries no message of
         * its own to show. The page renders it with the same localized
         * `identity_unreachable` text the sign-in pages already use, so the state
         * stays a plain value with no dependency on resources.
         */
        const val UNREACHABLE: String = "identity-unreachable"
    }
}

/**
 * The sessions one account is allowed to see, held only as long as that account
 * is the current one.
 *
 * The rule enforced here is the same one [AccountSession] enforces for the
 * account itself: a list that was asked for as Alice must never appear after the
 * app has become Bob's, even if Alice's answer was already on its way. So
 * [bind] retires every attempt made under the previous credential by moving an
 * epoch forward, and an answer is adopted only while its own epoch is still
 * current. Cancellation is not the guarantee — the bytes may already be on the
 * way — the epoch is.
 *
 * [snapshot] is the single authoritative view of this state and it is a
 * [StateFlow], so the page that renders it recomposes on its own; there is no
 * second copy and no polling.
 *
 * There is no second identity here: [bind] takes the credential it is allowed
 * to act as, and it takes no identity of its own to disagree with
 * [AccountSession].
 */
class OwnedSessionState(
    private val backend: SessionBackend,
    private val scope: CoroutineScope,
) {

    private val _snapshot = MutableStateFlow(SessionsSnapshot())

    /** The one authoritative snapshot; the page collects this and nothing else. */
    val snapshot: StateFlow<SessionsSnapshot> = _snapshot.asStateFlow()

    /**
     * Retired by every [bind], so an answer asked for under a previous
     * credential is recognisable when it lands. Read and written only on the
     * scope's own dispatcher, which is why no lock is needed.
     */
    private var epoch = 0L
    private var bound: String? = null

    /**
     * Attach the credential this state may act as, discarding whatever the
     * previous one was allowed to see.
     *
     * Called whenever the account changes — a new sign-in, a sign-out, a restore
     * that lands on a different identity — so the list and the selection are
     * gone the instant the account is, not when the next list happens to arrive.
     */
    fun bind(credential: String?) {
        if (credential == bound) return
        bound = credential
        epoch += 1
        _snapshot.value = SessionsSnapshot()
    }

    fun refresh() = request { token, current ->
        val listed = backend.list(token)
        current.copy(
            sessions = listed.sessions,
            primarySessionId = listed.primarySessionId ?: current.primarySessionId,
            // A selection this account's own list does not contain is not a
            // selection it may keep; main is the standing default.
            selectedId = current.selectedId?.takeIf { chosen ->
                listed.sessions.any { owned -> owned.id == chosen }
            } ?: listed.primarySessionId,
        )
    }

    fun create() = request { token, current ->
        current.joined(backend.create(token), stand = true)
    }

    fun select(sessionId: String) = request { token, current ->
        current.joined(backend.select(token, sessionId), stand = true)
    }

    /** Return to the account's permanent main session. */
    fun returnToPrimary() = request { token, current ->
        current.joined(backend.primary(token), stand = true)
    }

    fun read(sessionId: String) = request { token, current ->
        current.joined(backend.read(token, sessionId), stand = true)
    }

    /**
     * Fold one session the backend named into the snapshot.
     *
     * A session the backend has just named is one this account owns, so it joins
     * the list even when no list has arrived yet — that is what makes a first
     * create or a first return-to-main show up instead of leaving the page
     * looking empty. [stand] marks it as the session the user is now in, and
     * main is recorded only the first time one is seen: an ordinary create or
     * choice must not move the account's permanent main.
     */
    private fun SessionsSnapshot.joined(named: OwnedSession, stand: Boolean) = copy(
        sessions = if (sessions.any { it.id == named.id }) {
            sessions.map { if (it.id == named.id) named else it }
        } else {
            sessions + named
        },
        primarySessionId = primarySessionId ?: named.id.takeIf { named.primary },
        selectedId = if (stand) named.id else selectedId,
    )

    /**
     * Run one request as the currently bound account, if there still is one.
     *
     * The answer is folded into the snapshot at the moment it lands rather than
     * when it was asked for, so the page always shows the newest settled state.
     *
     * One action at a time. A button that is disabled by [SessionsSnapshot.loading]
     * can still be tapped again before the page has recomposed, so the guard is
     * also checked here, where it cannot be raced. [bind] installs a fresh
     * snapshot with `loading = false`, so a new account is never blocked by a
     * request that belonged to the previous one.
     */
    private fun request(operation: suspend (String, SessionsSnapshot) -> SessionsSnapshot) {
        val token = bound ?: return
        if (_snapshot.value.loading) return
        val started = epoch
        _snapshot.value = _snapshot.value.copy(loading = true, failure = null)
        scope.launch {
            val settled = try {
                operation(token, _snapshot.value)
            } catch (refused: IdentityRefused) {
                if (started != epoch) return@launch
                _snapshot.value = _snapshot.value.settled(failure = refused.message ?: refused.code)
                return@launch
            } catch (unreachable: IdentityUnreachable) {
                if (started != epoch) return@launch
                // Being unable to reach the backend is still something to say:
                // clearing the loading flag alone left the page looking as if
                // the tap had done nothing at all.
                _snapshot.value = _snapshot.value.settled(failure = SessionsSnapshot.UNREACHABLE)
                return@launch
            }
            if (started == epoch) _snapshot.value = settled.settled()
        }
    }

    private fun SessionsSnapshot.settled(failure: String? = null) =
        copy(loading = false, failure = failure)
}
