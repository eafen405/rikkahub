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
import java.io.File

/** An unresolved client command, not authoritative message history. */
data class PendingChatSend(
    val requestId: String,
    val text: String,
    val acceptanceUnknown: Boolean = false,
    val uploadIds: List<String> = emptyList(),
)
data class RemoteAttachmentDraft(
    val id: String,
    val name: String,
    val mime: String,
    val bytes: Long,
    val uploading: Boolean,
    val uploadId: String? = null,
    val failure: String? = null,
)
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
    val attachmentEpoch: Long = 0,
    val attachments: List<RemoteAttachmentDraft> = emptyList(),
    val downloading: Boolean = false,
    val attachmentNotice: String? = null,
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
    private val attachmentsBackend: RemoteAttachmentBackend? = null,
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
    private var attachmentEpoch = 0L
    private val draftFiles = mutableMapOf<String, File>()
    private val uploads = mutableMapOf<String, Job>()
    private var download: Job? = null

    fun bind(credential: String?, selectedSessionId: String?, userShortId: Int?) {
        val selected = if (selectedSessionId != null && userShortId != null) {
            ChatSyncKey(endpoint.trimEnd('/'), userShortId, selectedSessionId)
        } else null
        if (credential == token && selected == key && observer?.isActive == true) return
        val same = selected == key && selected != null
        if (!same || credential != token) {
            attachmentEpoch++
            uploads.values.forEach { it.cancel() }
            uploads.clear()
            draftFiles.values.forEach { it.delete() }
            draftFiles.clear()
            download?.cancel()
            download = null
            mutable.value = mutable.value.copy(
                attachmentEpoch = attachmentEpoch, attachments = emptyList(),
                downloading = false, attachmentNotice = null,
            )
        }
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
            mutable.value = RemoteChatSnapshot(sessionId = selectedSessionId, attachmentEpoch = attachmentEpoch)
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
                        val newGeneration = previous != null && previous.generation != projection.generation
                        if (newGeneration) {
                            // A recovered Agent may replace an observed, unflushed tail at the same seq.
                            // Rebuild derived content; keep the saved consumption/pending record.
                            events.clear()
                            messages.clear()
                            mutable.value = mutable.value.copy(projection = null, batch = null)
                            synchronization?.cancel()
                            synchronization = null
                        }
                        if (first || newGeneration) {
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
        val generation = mutable.value.projection?.generation
        fun currentGeneration() = current(boundEpoch, opened) && mutable.value.projection?.generation == generation
        synchronization = scope.launch {
            try {
                mutable.value = mutable.value.copy(syncing = true, failure = null)
                while (before > 0 && !covered(before - 1)) {
                    val requestedBefore = before
                    val page = backend.page(credential, selected.sessionId, cut, requestedBefore)
                    if (!currentGeneration()) return@launch
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
                if (!currentGeneration()) return@launch
                val acknowledged = backend.checkpoint(credential, selected.sessionId)
                if (!currentGeneration()) return@launch
                val latest = mutable.value.projection ?: return@launch
                val target = minOf(latest.cursor, acknowledged)
                require(covered(target)) { "历史范围尚未完整补齐" }
                val old = load(selected)
                if (target > old.consumedThrough || !old.initialized) {
                    val saved = update(selected) { record ->
                        record.copy(consumedThrough = maxOf(record.consumedThrough, target), initialized = true)
                    }
                    if (!currentGeneration()) return@launch
                    mutable.value = mutable.value.copy(
                        consumedThrough = saved.consumedThrough, initialized = true,
                        batch = ChatConsumptionBatch(selected, latest.generation, old.consumedThrough, saved.consumedThrough, !old.initialized),
                    )
                }
                val pending = load(selected).pending
                if (pending != null && latest.messages.any { it.requestId == pending.requestId && it.seq <= acknowledged }) {
                    clearPending(selected, pending.requestId)
                    if (!currentGeneration()) return@launch
                    showPending(selected)
                    clearSentDrafts(pending.uploadIds)
                }
                if (currentGeneration()) mutable.value = mutable.value.copy(
                    syncing = mutable.value.consumedThrough < (mutable.value.projection?.cursor ?: -1),
                    projection = mutable.value.projection?.copy(durableThroughSeq = maxOf(latest.durableThroughSeq, acknowledged)),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (currentGeneration()) mutable.value = mutable.value.copy(syncing = true, failure = failure.description())
            } finally {
                // Live facts can arrive while the local commit is awaiting disk. Retry that remaining prefix.
                if (currentGeneration() && mutable.value.connected && mutable.value.syncing) {
                    scope.launch {
                        delay(retryDelayMillis)
                        if (currentGeneration() && mutable.value.connected && mutable.value.syncing) {
                            scheduleSync(boundEpoch, opened, credential, selected)
                        }
                    }
                }
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

    /** A picker result belongs to the binding captured before the picker opened. */
    fun addAttachment(expectedEpoch: Long, file: File, name: String, mime: String) {
        val state = mutable.value
        if (expectedEpoch != attachmentEpoch || key == null || token == null || attachmentsBackend == null ||
            state.pending != null || state.sending) {
            file.delete()
            return
        }
        if (state.attachments.size >= 8 || !file.isFile || file.length() > 32L * 1024 * 1024 ||
            state.attachments.sumOf { it.bytes } + file.length() > 64L * 1024 * 1024) {
            file.delete()
            mutable.value = state.copy(attachmentNotice = "最多8个附件，单件32MiB，总计64MiB")
            return
        }
        val id = UUID.randomUUID().toString()
        draftFiles[id] = file
        mutable.value = state.copy(attachments = state.attachments + RemoteAttachmentDraft(id, name, mime, file.length(), true), attachmentNotice = null)
        uploadAttachment(id)
    }

    fun removeAttachment(id: String) {
        if (mutable.value.pending != null || mutable.value.sending) return
        uploads.remove(id)?.cancel()
        draftFiles.remove(id)?.delete()
        mutable.value = mutable.value.copy(attachments = mutable.value.attachments.filterNot { it.id == id })
    }

    fun retryAttachment(id: String) {
        if (mutable.value.pending != null || mutable.value.sending || uploads[id]?.isActive == true) return
        if (draftFiles[id] == null) return
        mutable.value = mutable.value.copy(attachments = mutable.value.attachments.map {
            if (it.id == id) it.copy(uploading = true, failure = null) else it
        })
        uploadAttachment(id)
    }

    private fun uploadAttachment(id: String) {
        val backend = attachmentsBackend ?: return
        val credential = token ?: return
        val selected = key ?: return
        val started = attachmentEpoch
        val file = draftFiles[id] ?: return
        val draft = mutable.value.attachments.find { it.id == id } ?: return
        fun currentDraft() = started == attachmentEpoch && mutable.value.attachments.any { it.id == id }
        uploads[id] = scope.launch {
            try {
                val upload = backend.upload(credential, selected.sessionId, file, draft.name, draft.mime)
                if (!currentDraft()) return@launch
                require(upload.status == 1 && upload.size == draft.bytes && upload.offset == draft.bytes) { "附件上传尚未完成" }
                mutable.value = mutable.value.copy(attachments = mutable.value.attachments.map {
                    if (it.id == id) it.copy(name = upload.name, uploading = false, uploadId = upload.uploadId) else it
                })
                draftFiles.remove(id)?.delete()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (currentDraft()) mutable.value = mutable.value.copy(attachments = mutable.value.attachments.map {
                    if (it.id == id) it.copy(uploading = false, failure = failure.description()) else it
                })
            } finally {
                if (started == attachmentEpoch) uploads.remove(id)
            }
        }
    }

    private fun clearSentDrafts(uploadIds: List<String>) {
        mutable.value = mutable.value.copy(attachments = mutable.value.attachments.filterNot { it.uploadId in uploadIds })
    }

    /** Download privately first. A late response never reaches a new binding's SAF target. */
    fun downloadAttachment(
        expectedEpoch: Long,
        messageId: String,
        attachment: RemoteChatAttachment,
        destination: File,
        save: suspend (File) -> Unit,
    ) {
        val backend = attachmentsBackend
        val credential = token
        val selected = key
        if (expectedEpoch != attachmentEpoch || backend == null || credential == null || selected == null || mutable.value.downloading) {
            destination.delete()
            return
        }
        mutable.value = mutable.value.copy(downloading = true, attachmentNotice = null)
        download = scope.launch {
            try {
                backend.download(credential, selected.sessionId, messageId, attachment, destination)
                if (expectedEpoch != attachmentEpoch) return@launch
                save(destination)
                if (expectedEpoch == attachmentEpoch) mutable.value = mutable.value.copy(attachmentNotice = "原件已保存")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (expectedEpoch == attachmentEpoch) mutable.value = mutable.value.copy(attachmentNotice = failure.description())
            } finally {
                destination.delete()
                if (expectedEpoch == attachmentEpoch) mutable.value = mutable.value.copy(downloading = false)
            }
        }
    }

    fun send(text: String): Boolean {
        val selected = key ?: return false
        val credential = token ?: return false
        val drafts = mutable.value.attachments
        if ((text.isBlank() && drafts.isEmpty()) || drafts.any { it.uploadId == null || it.uploading } ||
            !mutable.value.storageReady || mutable.value.sending || mutable.value.pending != null) return false
        val command = StoredChatSend(UUID.randomUUID().toString(), text, drafts.map { it.uploadId!! })
        mutable.value = mutable.value.copy(sending = true, pending = command.pending().copy(acceptanceUnknown = false), failure = null)
        scope.launch {
            try {
                update(selected) { it.copy(pending = command) } // Save identity BEFORE any network side effect.
                showPending(selected)
                transmit(credential, selected, command)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { commandFailure(selected, command, failure) }
        }
        return true
    }
    fun retryPending() {
        val selected = key ?: return
        val credential = token ?: return
        val command = mutable.value.pending ?: return
        if (!command.acceptanceUnknown || mutable.value.sending) return
        mutable.value = mutable.value.copy(sending = true, failure = null)
        scope.launch {
            try {
                update(selected) { it.copy(pending = StoredChatSend(command.requestId, command.text, command.uploadIds)) }
                transmit(credential, selected, StoredChatSend(command.requestId, command.text, command.uploadIds))
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { commandFailure(selected, StoredChatSend(command.requestId, command.text, command.uploadIds), failure) }
        }
    }
    private suspend fun transmit(credential: String, selected: ChatSyncKey, command: StoredChatSend) {
        if (credential != token || selected != key) return
        backend.send(credential, selected.sessionId, command.requestId, command.text, command.uploadIds)
        clearPending(selected, command.requestId)
        showPending(selected)
        if (key == selected) {
            clearSentDrafts(command.uploadIds)
            mutable.value = mutable.value.copy(sending = false)
        }
    }
    private suspend fun commandFailure(selected: ChatSyncKey, command: StoredChatSend, failure: Exception) {
        var rejected = false
        if (failure is IdentityRefused && failure.status < 500) {
            try {
                clearPending(selected, command.requestId)
                rejected = true
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Keep the original identity when local clearing fails. */ }
        }
        if (selected == key) {
            val persisted = load(selected).pending
            val projection = mutable.value.projection
            val accepted = persisted == null && projection?.messages?.any {
                it.requestId == command.requestId && it.seq <= projection.durableThroughSeq
            } == true
            mutable.value = mutable.value.copy(
                sending = false,
                pending = if (rejected || accepted) null else persisted?.pending() ?: command.pending(),
                failure = failure.description(),
            )
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
                if (backend.receipt(credential, selected.sessionId, command.requestId) != null) {
                    clearPending(selected, command.requestId)
                    if (selected == key) clearSentDrafts(command.uploadIds)
                }
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
    private fun StoredChatSend.pending() = PendingChatSend(requestId, text, acceptanceUnknown = true, uploadIds = uploadIds)
    private fun Exception.description() = if (this is IdentityUnreachable) SessionsSnapshot.UNREACHABLE else message ?: "同步失败"
}
