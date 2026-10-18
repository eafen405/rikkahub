package me.rerere.rikkahub.data.remote

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Why the page must not offer to edit or delete a route right now, or null when
 * it may.
 *
 * [IN_USE] is the backend's own reason — a running reply still uses the route —
 * and it wins over read-only deployment policy because it is the fact that would
 * answer a write with `gateway/route-in-use`. The page disables the controls and
 * shows the reason instead of inviting a request the backend will refuse.
 */
enum class GatewayRouteLock { IN_USE, READ_ONLY }

fun GatewayRoute.lock(editable: GatewayEditable): GatewayRouteLock? = when {
    inUse -> GatewayRouteLock.IN_USE
    !editable.routes -> GatewayRouteLock.READ_ONLY
    else -> null
}

/**
 * A failure the gateway page must report as itself.
 *
 * [unreachable] has no code and no message of its own: the page renders it with
 * the same localized "backend could not be reached" text the sign-in pages use.
 * A refusal keeps the backend's code and message verbatim, which is the only
 * way the user can tell "not the administrator" from "a running reply still
 * uses this route".
 */
data class GatewayFailure(
    val unreachable: Boolean = false,
    val code: String = "",
    val message: String = "",
)

/** What the gateway page currently has to show. */
data class GatewaySnapshot(
    val loading: Boolean = false,
    val working: Boolean = false,
    val config: GatewayConfig? = null,
    val failure: GatewayFailure? = null,
)

/**
 * The gateway configuration as one page knows it.
 *
 * One authority: every read and every write goes through here and every answer
 * becomes [snapshot]. Writes are serialized, so two taps cannot race each other
 * into contradictory configurations, and a successful write is followed by a
 * fresh read rather than by trusting the echoed value — the page then shows what
 * the backend actually stored, including the `saved` flags and the current
 * `inUse` state.
 *
 * A failed write never replaces what was already read: the configuration stays
 * on screen with the refusal beside it, which is what lets the user retry after
 * a running reply ends.
 */
class GatewayState(
    private val backend: GatewayBackend,
    private val scope: CoroutineScope,
) {
    private val mutable = MutableStateFlow(GatewaySnapshot())
    val snapshot: StateFlow<GatewaySnapshot> = mutable.asStateFlow()

    /** One write at a time; the page may tap while a previous write is landing. */
    private val writes = Mutex()

    fun load(token: String) {
        mutable.value = mutable.value.copy(loading = true, failure = null)
        scope.launch {
            // A read takes the same lock as a write: a reload issued while a
            // write is landing must not publish a snapshot older than it.
            writes.withLock {
                try {
                    val config = backend.read(token)
                    mutable.value = GatewaySnapshot(config = config)
                } catch (refused: IdentityRefused) {
                    mutable.value = mutable.value.copy(loading = false, failure = refused.failure())
                } catch (unreachable: IdentityUnreachable) {
                    mutable.value = mutable.value.copy(loading = false, failure = GatewayFailure(unreachable = true))
                }
            }
        }
    }

    fun select(token: String, selection: GatewaySelection) = write(token) {
        backend.select(token, selection)
    }

    fun saveRoute(token: String, route: GatewayRouteDraft, onSaved: () -> Unit = {}) =
        write(token, onSaved) {
            backend.saveRoute(token, route)
        }

    fun deleteRoute(token: String, provider: String) = write(token) {
        backend.deleteRoute(token, provider)
    }

    fun saveAsr(token: String, asr: GatewayAsrDraft) = write(token) {
        backend.saveAsr(token, asr)
    }

    private fun write(
        token: String,
        onSaved: () -> Unit = {},
        operation: suspend () -> Unit,
    ) {
        scope.launch {
            writes.withLock {
                mutable.value = mutable.value.copy(working = true, failure = null)
                var accepted = false
                try {
                    operation()
                    accepted = true
                    freshRead(token)
                } catch (refused: IdentityRefused) {
                    mutable.value = mutable.value.copy(
                        working = false,
                        failure = refused.failure(),
                    )
                } catch (unreachable: IdentityUnreachable) {
                    mutable.value = mutable.value.copy(
                        working = false,
                        failure = GatewayFailure(unreachable = true),
                    )
                }
                // The write itself was accepted; a read failure afterwards is a
                // reporting problem, not a reason to keep the editor open over a
                // route the backend has already stored.
                if (accepted) onSaved()
            }
        }
    }

    /**
     * Re-read after a write. A read that fails here still clears [working]; the
     * previous configuration stays with its failure beside it.
     */
    private suspend fun freshRead(token: String) {
        try {
            val config = backend.read(token)
            mutable.value = GatewaySnapshot(config = config)
        } catch (refused: IdentityRefused) {
            mutable.value = mutable.value.copy(working = false, failure = refused.failure())
        } catch (unreachable: IdentityUnreachable) {
            mutable.value = mutable.value.copy(
                working = false,
                failure = GatewayFailure(unreachable = true),
            )
        }
    }

    private fun IdentityRefused.failure() = GatewayFailure(
        code = code,
        message = message.orEmpty(),
    )
}
