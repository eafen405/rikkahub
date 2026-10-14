package me.rerere.rikkahub.data.remote

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Raised when an answer arrives after the account it belonged to was replaced.
 *
 * Callers should treat this as "nothing happened": the answer describes an
 * attempt that is no longer the current one, so it was deliberately not
 * believed.
 */
class StaleAccountAnswer : Exception("the account changed before this answer arrived")

/**
 * Which identity this app is currently acting as.
 *
 * A phone is a hostile place for a single "current account" variable. Two
 * sign-ins can overlap, a sign-out can land between them, and the network
 * answers in whatever order it likes — so the rule enforced here is that the
 * last *decision* decides the account, never the last *answer*. Every attempt
 * takes a generation number, and an answer is adopted only while its attempt is
 * still the current one; anything else is discarded rather than applied.
 *
 * The credential is written through [CredentialStore] only on adoption, so a
 * discarded answer cannot leave a stale credential on the device either.
 */
class AccountSession(
    private val backend: IdentityBackend,
    private val store: CredentialStore,
    private val onAccountChanged: suspend () -> Unit = {},
) {
    private val mutex = Mutex()
    private var generation = 0L

    @Volatile
    var user: IdentityUser? = null
        private set

    @Volatile
    var token: String? = null
        private set

    val isSignedIn: Boolean get() = token != null

    /** Sign in as an existing identity. */
    suspend fun signIn(name: String, password: String): IdentityUser {
        val attempt = begin()
        return adopt(attempt, backend.signIn(name, password))
    }

    /** Redeem an invite and become a new identity. */
    suspend fun register(inviteCode: String, name: String, password: String): IdentityUser {
        val attempt = begin()
        return adopt(attempt, backend.register(inviteCode, name, password))
    }

    /**
     * Re-establish the credential left from a previous run.
     *
     * A credential the backend rejects is dropped: leaving it on the device
     * would keep presenting something already known to be dead. A credential
     * that cannot be *checked* is kept, because being offline is not evidence
     * that the account is gone.
     *
     * @return the restored identity, or null when nobody could be established.
     */
    suspend fun restore(): IdentityUser? {
        val stored = store.load() ?: return null
        val attempt = begin()
        val restored = try {
            backend.session(stored)
        } catch (refused: IdentityRefused) {
            mutex.withLock { if (generation == attempt) store.clear() }
            return null
        } catch (unreachable: IdentityUnreachable) {
            return null
        }
        return mutex.withLock {
            if (generation != attempt) return@withLock null
            user = restored
            token = stored
            restored
        }
    }

    /**
     * End this account and revoke its credential.
     *
     * The local account is cleared first and unconditionally: a sign-out that
     * only took effect when the network cooperated would leave the device
     * holding a live credential after the user asked for it to be gone.
     */
    suspend fun signOut() {
        val revoked = mutex.withLock {
            generation += 1
            val held = token
            user = null
            token = null
            store.clear()
            onAccountChanged()
            held
        }
        if (revoked != null) {
            runCatching { backend.signOut(revoked) }
        }
    }

    /** Take the next generation, which retires every attempt already in flight. */
    private suspend fun begin(): Long = mutex.withLock {
        generation += 1
        generation
    }

    private suspend fun adopt(attempt: Long, signedIn: SignedIn): IdentityUser = mutex.withLock {
        if (generation != attempt) throw StaleAccountAnswer()
        user = signedIn.user
        token = signedIn.token
        store.save(signedIn.token)
        onAccountChanged()
        signedIn.user
    }
}
