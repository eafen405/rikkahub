package me.rerere.rikkahub.data.remote

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.util.UUID

/** An unresolved client command, not authoritative message history. */
data class PendingChatSend(val requestId: String, val text: String, val acceptanceUnknown: Boolean = false)
data class ChatConsumptionBatch(
    val key: ChatSyncKey,
    val generation: String,
    val fromExclusive: Long,
    val throughSeq: Long,
    val initialization: Boolean,
)
data class RemoteChatSnapshot(
    val sessionId: String? = null,
    val projection: RemoteChatProjection? = null,
    val connected: Boolean = false,
    val connecting: Boolean = false,
    val sending: Boolean = false,
    val pending: PendingChatSend? = null,
    val failure: String? = null,
    val syncing: Boolean = false,
    val consumedThrough: Long = -1,
    val initialized: Boolean = false,
    val batch: ChatConsumptionBatch? = null,
    val storageReady: Boolean = false,
)
enum class RemoteMessageFormat { PLAIN, MARKDOWN }
fun remoteMessageFormat(message: RemoteChatMessage) =
    if (message.role == 2) RemoteMessageFormat.MARKDOWN else RemoteMessageFormat.PLAIN
fun remoteAttemptFormat() = RemoteMessageFormat.PLAIN

/** One remote projection. Raw event coverage, writer ACK and local consumption remain separate. */
class RemoteChatState(
    private val backend: RemoteChatBackend,
    private val scope: CoroutineScope,
    private val store: ChatSyncStore,
    private val endpoint: String,
    private val retryDelayMillis: Long = 1_000,
) {
    private val mutable = MutableStateFlow(RemoteChatSnapshot())
    val snapshot: StateFlow<RemoteChatSnapshot> = mutable.asStateFlow()
    private var token: String? = null
    private var key: ChatSyncKey? = null
    private var epoch = 0L
    private var connection = 0L
    private var observer: Job? = null
    private var synchronization: Job? = null
    private val recordLock = Mutex()
    private val records = mutableMapOf<ChatSyncKey, ChatSyncRecord>()
    private val events = sortedMapOf<Long, JsonObject>()
    private val messages = mutableMapOf<String, RemoteChatMessage>()
    private var cut = -1L
    private var before = 0L

    fun bind(credential: String?, selectedSessionId: String?, userShortId: Int?) {
        val selected = if (selectedSessionId != null && userShortId != null) {
            ChatSyncKey(endpoint.trimEnd('/'), userShortId, selectedSessionId)
        } else null
        if (credential == token && selected == key && observer?.isActive == true) return
        val same = selected == key && selected != null
        token = credential
        key = selected
        epoch++
        connection++
        observer?.cancel()
        synchronization?.cancel()
        synchronization = null
        if (!same) {
            events.clear()
            messages.clear()
            mutable.value = RemoteChatSnapshot(sessionId = selectedSessionId)
        } else mutable.value = mutable.value.copy(connected = false, connecting = false)
        if (credential == null || selected == null) return
        val boundEpoch = epoch
        observer = scope.launch {
            while (boundEpoch == epoch) {
                val opened = ++connection
                try {
                    val record = load(selected)
                    if (!current(boundEpoch, opened)) return@launch
                    mutable.value = mutable.value.copy(
                        storageReady = true, consumedThrough = record.consumedThrough,
                        initialized = record.initialized, pending = record.pending?.pending(), connecting = true,
                    )
                    var first = true
                    backend.observe(credential, selected.sessionId).collect { projection ->
                        if (!current(boundEpoch, opened) || projection.sessionId != selected.sessionId) return@collect
                        val previous = mutable.value.projection
                        if (previous?.generation == projection.generation && projection.version < previous.version) return@collect
                        if (first) {
                            cut = projection.throughSeq
                            before = projection.fromSeq
                            first = false
                        }
                        require(projection.throughSeq == cut) { "恢复截点在同一连接中发生变化" }
                        mergeEvents(projection.events, projection.fromSeq, projection.cursor + 1)
                        mergeMessages(projection.messages)
                        mutable.value = mutable.value.copy(
                            projection = projection.copy(messages = orderedMessages()),
                            connected = true, connecting = false, failure = null, syncing = true,
                        )
                        scheduleSync(boundEpoch, opened, credential, selected)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    if (current(boundEpoch, opened)) mutable.value = mutable.value.copy(failure = failure.description())
                }
                if (!current(boundEpoch, opened)) return@launch
                connection++ // Retire pages from the closing connection before opening another cut.
                synchronization?.cancel()
                synchronization = null
                mutable.value = mutable.value.copy(connected = false, connecting = true)
                delay(retryDelayMillis)
            }
        }
    }

    /** Releases observation only; never stops the backend Agent. */
    fun detach() {
        epoch++
        connection++
        observer?.cancel()
        synchronization?.cancel()
        observer = null
        synchronization = null
        mutable.value = mutable.value.copy(connected = false, connecting = false)
    }

    fun retrySync() {
        val credential = token ?: return
        val selected = key ?: return
        if (mutable.value.connected) scheduleSync(epoch, connection, credential, selected)
        else bind(credential, selected.sessionId, selected.userShortId)
    }

    private fun scheduleSync(boundEpoch: Long, opened: Long, credential: String, selected: ChatSyncKey) {
        if (synchronization?.isActive == true) return
        synchronization = scope.launch {
            try {
                mutable.value = mutable.value.copy(syncing = true, failure = null)
                while (before > 0 && !covered(before - 1)) {
                    val requestedBefore = before
                    val page = backend.page(credential, selected.sessionId, cut, requestedBefore)
                    if (!current(boundEpoch, opened)) return@launch
                    require(page.sessionId == selected.sessionId && page.throughSeq == cut) { "历史页不属于当前恢复截点" }
                    require(page.fromSeq < requestedBefore && page.fromSeq >= 0) { "历史页没有向前推进" }
                    mergeEvents(page.events, page.fromSeq, requestedBefore)
                    require(page.hasMore == (page.fromSeq > 0) && page.beforeSeq == page.fromSeq.takeIf { it > 0 }) {
                        "历史页的范围不一致"
                    }
                    mergeMessages(page.messages)
                    before = page.fromSeq
                    mutable.value = mutable.value.copy(projection = mutable.value.projection?.copy(messages = orderedMessages()))
                }
                if (!current(boundEpoch, opened)) return@launch
                val acknowledged = backend.checkpoint(credential, selected.sessionId)
                if (!current(boundEpoch, opened)) return@launch
                val latest = mutable.value.projection ?: return@launch
                val target = minOf(latest.cursor, acknowledged)
                require(covered(target)) { "历史范围尚未完整补齐" }
                val old = load(selected)
                if (target > old.consumedThrough || !old.initialized) {
                    val saved = update(selected) { record ->
                        record.copy(consumedThrough = maxOf(record.consumedThrough, target), initialized = true)
                    }
                    if (!current(boundEpoch, opened)) return@launch
                    mutable.value = mutable.value.copy(
                        consumedThrough = saved.consumedThrough, initialized = true,
                        batch = ChatConsumptionBatch(selected, latest.generation, old.consumedThrough, saved.consumedThrough, !old.initialized),
                    )
                }
                val pending = load(selected).pending
                if (pending != null && latest.messages.any { it.requestId == pending.requestId && it.seq <= acknowledged }) {
                    clearPending(selected, pending.requestId)
                    if (!current(boundEpoch, opened)) return@launch
                    showPending(selected)
                }
                if (current(boundEpoch, opened)) mutable.value = mutable.value.copy(
                    syncing = mutable.value.consumedThrough < (mutable.value.projection?.cursor ?: -1),
                    projection = mutable.value.projection?.copy(durableThroughSeq = maxOf(latest.durableThroughSeq, acknowledged)),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (current(boundEpoch, opened)) mutable.value = mutable.value.copy(syncing = true, failure = failure.description())
            }
        }
    }

    private fun mergeEvents(incoming: List<JsonObject>, from: Long, until: Long) {
        require(from >= 0 && until >= from && incoming.size.toLong() == until - from) { "收到的事件范围不完整" }
        incoming.forEachIndexed { index, event ->
            val seq = event.getValue("seq").jsonPrimitive.long
            require(seq == from + index) { "收到的事件顺序不完整" }
            require(events[seq] == null || events[seq] == event) { "相同位置的事件内容不一致" }
        }
        incoming.forEach { event -> events[event.getValue("seq").jsonPrimitive.long] = event }
    }
    private fun covered(through: Long): Boolean = through < 0 ||
        (events.isNotEmpty() && events.firstKey() == 0L && events.headMap(through + 1).size.toLong() == through + 1)
    private fun mergeMessages(incoming: List<RemoteChatMessage>) {
        incoming.forEach { message ->
            val previous = messages[message.messageId]
            if (previous == null || message.seq >= previous.seq) messages[message.messageId] = message
        }
    }
    private fun orderedMessages() = messages.values.sortedWith(compareBy<RemoteChatMessage> { it.seq }.thenBy { it.messageId })
    private fun current(boundEpoch: Long, opened: Long) = boundEpoch == epoch && opened == connection

    fun send(text: String) {
        val selected = key ?: return
        val credential = token ?: return
        if (text.isBlank() || !mutable.value.storageReady || mutable.value.sending || mutable.value.pending != null) return
        val command = StoredChatSend(UUID.randomUUID().toString(), text)
        mutable.value = mutable.value.copy(sending = true, failure = null)
        scope.launch {
            try {
                update(selected) { it.copy(pending = command) } // Save identity BEFORE any network side effect.
                showPending(selected)
                transmit(credential, selected, command)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { commandFailure(selected, command, failure) }
        }
    }
    fun retryPending() {
        val selected = key ?: return
        val credential = token ?: return
        val command = mutable.value.pending ?: return
        if (!command.acceptanceUnknown || mutable.value.sending) return
        mutable.value = mutable.value.copy(sending = true, failure = null)
        scope.launch {
            try { transmit(credential, selected, StoredChatSend(command.requestId, command.text)) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { commandFailure(selected, StoredChatSend(command.requestId, command.text), failure) }
        }
    }
    private suspend fun transmit(credential: String, selected: ChatSyncKey, command: StoredChatSend) {
        backend.send(credential, selected.sessionId, command.requestId, command.text)
        clearPending(selected, command.requestId)
        showPending(selected)
        if (key == selected) mutable.value = mutable.value.copy(sending = false)
    }
    private suspend fun commandFailure(selected: ChatSyncKey, command: StoredChatSend, failure: Exception) {
        if (failure is IdentityRefused && failure.status < 500) {
            try { clearPending(selected, command.requestId) } catch (_: Exception) { /* Keep recoverable identity on a failed clear. */ }
        }
        if (selected == key) {
            showPending(selected)
            mutable.value = mutable.value.copy(sending = false, failure = failure.description())
        }
    }
    fun reconcile() {
        val credential = token ?: return
        val selected = key ?: return
        val command = mutable.value.pending ?: return
        if (mutable.value.sending) return
        mutable.value = mutable.value.copy(sending = true, failure = null)
        scope.launch {
            try {
                if (backend.receipt(credential, selected.sessionId, command.requestId) != null) clearPending(selected, command.requestId)
                showPending(selected)
                if (selected == key) mutable.value = mutable.value.copy(sending = false)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { if (selected == key) mutable.value = mutable.value.copy(sending = false, failure = failure.description()) }
        }
    }
    fun stop() {
        val credential = token ?: return
        val selected = key ?: return
        val started = epoch
        scope.launch {
            try { backend.stop(credential, selected.sessionId) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { if (started == epoch) mutable.value = mutable.value.copy(failure = failure.description()) }
        }
    }
    private suspend fun load(selected: ChatSyncKey): ChatSyncRecord = recordLock.withLock {
        records[selected] ?: store.load(selected).also { records[selected] = it }
    }
    private suspend fun update(selected: ChatSyncKey, change: (ChatSyncRecord) -> ChatSyncRecord): ChatSyncRecord = recordLock.withLock {
        val next = change(records[selected] ?: store.load(selected))
        store.save(selected, next)
        records[selected] = next
        next
    }
    private suspend fun clearPending(selected: ChatSyncKey, requestId: String) = update(selected) {
        if (it.pending?.requestId == requestId) it.copy(pending = null) else it
    }
    private suspend fun showPending(selected: ChatSyncKey) {
        val pending = load(selected).pending
        if (selected == key) mutable.value = mutable.value.copy(pending = pending?.pending())
    }
    private fun StoredChatSend.pending() = PendingChatSend(requestId, text, acceptanceUnknown = true)
    private fun Exception.description() = if (this is IdentityUnreachable) SessionsSnapshot.UNREACHABLE else message ?: "同步失败"
}
