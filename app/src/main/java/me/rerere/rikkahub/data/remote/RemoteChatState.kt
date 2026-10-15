package me.rerere.rikkahub.data.remote

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/** A command whose durable acceptance has not yet been confirmed. It is not message history. */
data class PendingChatSend(val requestId: String, val text: String, val acceptanceUnknown: Boolean = false)

data class RemoteChatSnapshot(
    val sessionId: String? = null,
    val projection: RemoteChatProjection? = null,
    val connected: Boolean = false,
    val connecting: Boolean = false,
    val sending: Boolean = false,
    val pending: PendingChatSend? = null,
    val failure: String? = null,
)

enum class RemoteMessageFormat { PLAIN, MARKDOWN }
fun remoteMessageFormat(message: RemoteChatMessage): RemoteMessageFormat =
    if (message.role == 2) RemoteMessageFormat.MARKDOWN else RemoteMessageFormat.PLAIN
fun remoteAttemptFormat(): RemoteMessageFormat = RemoteMessageFormat.PLAIN

/** One account-scoped remote projection shared by callers. The backend owns all messages. */
class RemoteChatState(private val backend: RemoteChatBackend, private val scope: CoroutineScope) {
    private val mutable = MutableStateFlow(RemoteChatSnapshot())
    val snapshot: StateFlow<RemoteChatSnapshot> = mutable.asStateFlow()
    private var token: String? = null
    private var sessionId: String? = null
    private var epoch = 0L
    private var observer: Job? = null
    // Only unresolved commands, retained when moving between this account's sessions.
    private val pending = mutableMapOf<String, PendingChatSend>()

    fun bind(credential: String?, selectedSessionId: String?) {
        val sameBinding = credential == token && selectedSessionId == sessionId
        if (sameBinding && observer?.isActive == true) return
        if (credential != token) pending.clear()
        token = credential
        sessionId = selectedSessionId
        epoch++
        observer?.cancel()
        mutable.value = if (sameBinding) {
            mutable.value.copy(connected = false, connecting = false, pending = pending[selectedSessionId])
        } else {
            RemoteChatSnapshot(sessionId = selectedSessionId, pending = pending[selectedSessionId])
        }
        if (credential == null || selectedSessionId == null) return
        val started = epoch
        mutable.value = mutable.value.copy(connecting = true)
        observer = scope.launch {
            try {
                backend.observe(credential, selectedSessionId).collect { projection ->
                    if (started != epoch || projection.sessionId != selectedSessionId) return@collect
                    val current = mutable.value.projection
                    if (current?.generation == projection.generation && projection.version < current.version) return@collect
                    val outstanding = pending[selectedSessionId]
                    if (outstanding != null && projection.messages.any { it.requestId == outstanding.requestId && it.seq <= projection.durableThroughSeq }) {
                        pending.remove(selectedSessionId)
                    }
                    mutable.value = mutable.value.copy(
                        projection = projection, connected = true, connecting = false,
                        pending = pending[selectedSessionId], failure = null,
                    )
                }
                if (started == epoch) mutable.value = mutable.value.copy(connected = false, connecting = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (started == epoch) mutable.value = mutable.value.copy(
                    connected = false, connecting = false, failure = failure.description(),
                )
            }
        }
    }

    /** Drop the observation lease; explicit stop is the only operation that cancels execution. */
    fun detach() {
        epoch++
        observer?.cancel()
        observer = null
        mutable.value = mutable.value.copy(connected = false, connecting = false)
    }

    fun send(text: String) {
        val id = sessionId ?: return
        if (token == null || text.isBlank() || mutable.value.sending || pending[id] != null) return
        val command = PendingChatSend(UUID.randomUUID().toString(), text)
        pending[id] = command
        transmit(command)
    }

    /** An explicit retry always reuses the original request identity and exact text. */
    fun retryPending() {
        val command = pending[sessionId] ?: return
        if (!command.acceptanceUnknown || mutable.value.sending) return
        transmit(command)
    }

    private fun transmit(command: PendingChatSend) {
        val credential = token ?: return
        val id = sessionId ?: return
        mutable.value = mutable.value.copy(sending = true, pending = command, failure = null)
        scope.launch {
            try {
                backend.send(credential, id, command.requestId, command.text)
                if (credential == token && pending[id]?.requestId == command.requestId) pending.remove(id)
                if (isCurrentCommand(credential, id, command.requestId)) {
                    mutable.value = mutable.value.copy(sending = false, pending = pending[id])
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                // Only an explicit client refusal is a known rejection; 5xx and lost responses are unknown.
                val unknown = failure !is IdentityRefused || failure.status >= 500
                if (credential == token && pending[id]?.requestId == command.requestId) {
                    if (unknown) pending[id] = command.copy(acceptanceUnknown = true) else pending.remove(id)
                }
                if (isCurrentCommand(credential, id, command.requestId)) mutable.value = mutable.value.copy(
                    sending = false, pending = pending[id], failure = failure.description(),
                )
            }
        }
    }

    /** Query durable acceptance without submitting another command. A null receipt preserves the command. */
    fun reconcile() {
        val credential = token ?: return
        val id = sessionId ?: return
        val command = pending[id] ?: return
        if (mutable.value.sending) return
        mutable.value = mutable.value.copy(sending = true, failure = null)
        scope.launch {
            try {
                val receipt = backend.receipt(credential, id, command.requestId)
                if (receipt != null && credential == token && pending[id]?.requestId == command.requestId) pending.remove(id)
                if (isCurrentCommand(credential, id, command.requestId)) {
                    mutable.value = mutable.value.copy(sending = false, pending = pending[id])
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (isCurrentCommand(credential, id, command.requestId)) {
                    mutable.value = mutable.value.copy(sending = false, failure = failure.description())
                }
            }
        }
    }

    fun stop() {
        val credential = token ?: return
        val id = sessionId ?: return
        val started = epoch
        scope.launch {
            try {
                backend.stop(credential, id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (started == epoch) mutable.value = mutable.value.copy(failure = failure.description())
            }
        }
    }

    // A connection can reopen while a command is still in flight. Match the command, not its old observer.
    private fun isCurrentCommand(credential: String, id: String, requestId: String): Boolean =
        credential == token && id == sessionId &&
            (mutable.value.pending?.requestId == requestId ||
                (mutable.value.pending == null && mutable.value.sending))

    private fun Exception.description() = if (this is IdentityUnreachable) {
        SessionsSnapshot.UNREACHABLE
    } else message ?: "请求失败"
}
