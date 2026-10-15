package me.rerere.rikkahub.data.remote

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
)

/**
 * The sessions one account is allowed to see, held only as long as that account
 * is the current one.
 *
 * The rule enforced here is the same one [AccountSession] enforces for the
 * account itself, and it exists because the phone is a hostile place for it: a
 * list that was asked for as Alice must never appear after the app has become
 * Bob's, even if Alice's answer was already on its way. So every attempt carries
 * a generation taken when the credential was bound, and an answer is applied
 * only while its generation is still current.
 *
 * Two independent mechanisms, because they fail differently:
 *
 *  * **Cancellation** — rebinding drops the work in flight, so the usual answer
 *    never arrives at all.
 *  * **A generation check** — the answer still arrives, because the backend is
 *    outside our control and may already have written the bytes. Cancellation is
 *    an optimisation; the generation is the guarantee, and it is what the tests
 *    exercise against a backend that deliberately ignores cancellation.
 *
 * This is deliberately page-scoped state and nothing more. There is no cache to
 * be authoritative, no second identity to disagree with [AccountSession], and
 * no framework: [bind] takes the credential it is allowed to use, and it takes
 * none of anything else.
 */
class OwnedSessionState(private val backend: SessionBackend) {

    private val mutex = Mutex()

    @Volatile
    private var generation = 0L

    @Volatile
    private var token: String? = null

    private var work: Job? = null

    var snapshot: SessionsSnapshot = SessionsSnapshot()
        private set

    /**
     * Attach the credential this state may act as, discarding whatever the
     * previous one was allowed to see.
     *
     * Called on first composition and again whenever the account changes — a new
     * sign-in, a sign-out, a restore that lands on a different identity — so the
     * list and the selection are gone the instant the account is, not when the
     * next list happens to arrive.
     */
    fun bind(credential: String?) {
        if (credential == token) return
        work?.cancel()
        work = null
        token = credential
        generation += 1
        snapshot = SessionsSnapshot()
    }

    /** The credential currently bound, if any. */
    val credential: String? get() = token

    fun refresh(scope: CoroutineScope) = start(scope) { attempt, token ->
        val listed = backend.list(token)
        attempt {
            snapshot = snapshot.copy(
                sessions = listed.sessions,
                primarySessionId = listed.primarySessionId,
                loading = false,
                failure = null,
                // A selection that the backend's list does not contain is not a
                // selection this account may keep.
                selectedId = snapshot.selectedId?.takeIf { it in listed.sessions.map(OwnedSession::id) }
                    ?: listed.primarySessionId,
            )
        }
    }

    fun create(scope: CoroutineScope) = start(scope) { attempt, token ->
        val created = backend.create(token)
        attempt {
            snapshot = snapshot.copy(sessions = snapshot.sessions + created, loading = false, failure = null)
        }
    }

    fun select(scope: CoroutineScope, sessionId: String) = start(scope) { attempt, token ->
        val chosen = backend.select(token, sessionId)
        attempt {
            snapshot = snapshot.copy(selectedId = chosen.id, loading = false, failure = null)
        }
    }

    /** Return to the account's permanent main session. */
    fun returnToPrimary(scope: CoroutineScope) = start(scope) { attempt, token ->
        val primary = backend.primary(token)
        attempt {
            snapshot = snapshot.copy(selectedId = primary.id, loading = false, failure = null)
        }
    }

    fun read(scope: CoroutineScope, sessionId: String) = start(scope) { attempt, token ->
        val read = backend.read(token, sessionId)
        attempt {
            snapshot = snapshot.copy(
                selectedId = read.id,
                primarySessionId = read.primary.takeIf { it } ?: snapshot.primarySessionId,
                loading = false,
                failure = null,
            )
        }
    }

    /**
     * Run one request as the currently bound account, if there still is one.
     *
     * [onStale] is what a late answer from a replaced account must NOT do. It is
     * given to the caller so the answer can be discarded without touching state,
     * which is the whole point: applying it would publish one account's sessions
     * under another's name.
     */
    private fun start(
        scope: CoroutineScope,
        operation: suspend (attempt: ((() -> Unit)?) -> Unit, token: String) -> Unit,
    ) {
        val token = token ?: return
        val attemptGeneration = generation
        scope.launch {
            val loading = mutex.withLock {
                if (attemptGeneration != generation) return@launch
                work = coroutineContext[Job]
                snapshot = snapshot.copy(loading = true, failure = null)
            }
            try {
                operation(
                    { change ->
                        mutex.withLock {
                            if (attemptGeneration == generation) change()
                        }
                    },
                    token,
                )
            } catch (refused: IdentityRefused) {
                attemptGeneration.whileCurrent {
                    snapshot = snapshot.copy(loading = false, failure = refused.message ?: refused.code)
                }
            } catch (unreachable: IdentityUnreachable) {
                attemptGeneration.whileCurrent { snapshot = snapshot.copy(loading = false) }
            }
        }
    }

    private inline fun Long.whileCurrent(change: () -> Unit) {
        if (this == generation) change()
    }
}
