package me.rerere.rikkahub.ui.identity

import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.remote.ChatHistoryPage
import me.rerere.rikkahub.data.remote.ChatReceipt
import me.rerere.rikkahub.data.remote.ChatSyncKey
import me.rerere.rikkahub.data.remote.ChatSyncRecord
import me.rerere.rikkahub.data.remote.ChatSyncStore
import me.rerere.rikkahub.data.remote.RemoteActivity
import me.rerere.rikkahub.data.remote.RemoteChatAttempt
import me.rerere.rikkahub.data.remote.RemoteChatBackend
import me.rerere.rikkahub.data.remote.RemoteChatMessage
import me.rerere.rikkahub.data.remote.RemoteChatProjection
import me.rerere.rikkahub.data.remote.RemoteChatSettlement

/**
 * The five fixed workloads and their parameters.
 *
 * These numbers are the measurement protocol: changing one produces a new baseline, so they live
 * here as the single declaration that the activity, the evidence and the scripts all read. The
 * 232,703-character reply and the 128-character step are the #42 values; the earlier activity ran
 * a 20,000-character reply in 32-character steps, which is not the workload the evidence records.
 */
internal data class PresentationWorkload(
    val name: String,
    val historyMessages: Int,
    val replyChars: Int,
    val chunkChars: Int,
    val intervalMillis: Long,
) {
    companion object {
        const val WORKLOAD_FILE = "presentation/workload.md"
        const val LONG_REPLY_REPEATS = 64

        /** One synthetic history message body: the fixed prose, truncated to 240 characters. */
        const val HISTORY_MESSAGE_CHARS = 240

        fun of(name: String, unit: String): PresentationWorkload {
            val longReply = List(LONG_REPLY_REPEATS) { unit }.joinToString("\n").length
            return when (name) {
                "history" -> PresentationWorkload(name, historyMessages = 200, replyChars = 0, chunkChars = 0, intervalMillis = 0)
                "single" -> PresentationWorkload(name, historyMessages = 0, replyChars = longReply, chunkChars = longReply, intervalMillis = 0)
                "format" -> PresentationWorkload(name, historyMessages = 0, replyChars = unit.length, chunkChars = unit.length, intervalMillis = 0)
                "stream" -> PresentationWorkload(name, historyMessages = 0, replyChars = longReply, chunkChars = 128, intervalMillis = 20)
                "burst" -> PresentationWorkload(name, historyMessages = 0, replyChars = longReply, chunkChars = 128, intervalMillis = 0)
                else -> throw IllegalArgumentException("unknown scenario $name")
            }
        }

        val names = listOf("history", "single", "format", "stream", "burst")
    }
}

/**
 * A replayable backend for presentation measurements. It emits the same projection shape the SSE
 * route emits: an opening tail window, then cumulative snapshots whose cursor and attempt revision
 * keep growing, with older history that [me.rerere.rikkahub.data.remote.RemoteChatState] pages back
 * through. Raw events cover every sequence from 0, because the state refuses a prefix with a gap.
 *
 * Timestamps are taken where a projection would leave the backend, so the activity can separate
 * "the receive state accepted this snapshot" from "a frame presented it".
 */
internal class SyntheticChatBackend(
    private val workload: PresentationWorkload,
    private val unit: String,
) : RemoteChatBackend {
    val sessionId = "perf-${workload.name}"

    private val generation = "perf-generation"
    private val output = Channel<RemoteChatProjection>(Channel.UNLIMITED)
    private val events = mutableListOf<JsonObject>()
    private val messages = mutableListOf<RemoteChatMessage>()
    private val payload = buildPayload()
    private val emitNanos = ConcurrentHashMap<Long, Long>()
    private val completion = CompletableDeferred<Unit>()

    private var cursor = -1L
    private var cut = -1L
    private var openingFrom = 0L
    private var version = 0L
    private var revision = 0L
    private var attemptText = ""
    private val attemptId = "attempt-1"
    private var settled: RemoteChatSettlement? = null
    private var replyStatus = 0

    @Volatile var emitCount = 0
        private set

    /** Wall clock at the projection that first carried the finished reply. */
    @Volatile var terminalEmitNanos = 0L
        private set

    suspend fun awaitCompletion() = completion.await()

    fun emittedAt(version: Long): Long? = emitNanos[version]

    override fun observe(token: String, sessionId: String): Flow<RemoteChatProjection> = flow {
        val producer = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        producer.launch { runScenario() }
        try {
            for (projection in output) emit(projection)
        } finally {
            producer.cancel()
        }
    }

    override suspend fun read(token: String, sessionId: String): RemoteChatProjection = buildProjection()

    override suspend fun send(
        token: String,
        sessionId: String,
        requestId: String,
        text: String,
        uploadIds: List<String>,
    ): ChatReceipt = ChatReceipt(sessionId, requestId, "sent-$requestId", cursor)

    override suspend fun receipt(token: String, sessionId: String, requestId: String): ChatReceipt? = null

    override suspend fun stop(token: String, sessionId: String) = Unit

    override suspend fun page(
        token: String,
        sessionId: String,
        throughSeq: Long,
        beforeSeq: Long,
    ): ChatHistoryPage {
        require(throughSeq == cut) { "page belongs to the opening cut" }
        val from = maxOf(0L, beforeSeq - PAGE_EVENTS)
        val pageEvents = events.subList(from.toInt(), beforeSeq.toInt()).toList()
        val pageMessages = messages.filter { it.seq >= from && it.seq < beforeSeq }
        return ChatHistoryPage(
            sessionId = sessionId,
            throughSeq = throughSeq,
            fromSeq = from,
            beforeSeq = from.takeIf { it > 0 },
            hasMore = from > 0,
            events = pageEvents,
            messages = pageMessages,
        )
    }

    override suspend fun checkpoint(token: String, sessionId: String): Long = cursor

    private suspend fun runScenario() {
        seedHistory(workload.historyMessages)
        cut = cursor
        // The opening snapshot carries the newest window; older history is paged back through
        // [page], which is how a real reconnect fills a long session.
        openingFrom = maxOf(0L, cut - OPENING_EVENTS + 1).takeIf { workload.historyMessages > 0 } ?: 0
        publish()
        if (workload.replyChars <= 0) {
            finish()
            return
        }
        replyStatus = 1
        while (appendChunk(workload.chunkChars)) {
            publish()
            if (workload.intervalMillis > 0) delay(workload.intervalMillis)
        }
        finishReply()
    }

    private suspend fun publish(): Long {
        version++
        val projection = buildProjection()
        emitNanos[projection.version] = SystemClock.elapsedRealtimeNanos()
        emitCount++
        output.send(projection)
        return projection.version
    }

    private fun buildProjection(): RemoteChatProjection {
        val from = openingFrom
        val includedEvents = events.subList(from.toInt(), (cursor + 1).toInt()).toList()
        val includedMessages = messages.filter { it.seq >= from }
        return RemoteChatProjection(
            sessionId = sessionId,
            generation = generation,
            version = version,
            cursor = cursor,
            durableThroughSeq = cursor,
            messages = includedMessages,
            attempt = if (replyStatus == 1) RemoteChatAttempt(attemptId, revision, attemptText) else null,
            replyStatus = replyStatus,
            throughSeq = cut,
            fromSeq = from,
            events = includedEvents,
            hasMore = false,
            beforeSeq = null,
            tasks = emptyList(),
            activity = RemoteActivity(
                status = if (replyStatus == 1) 2 else 1,
                reasons = if (replyStatus == 1) listOf(1) else emptyList(),
                episodeId = "episode-$attemptId",
                startedSeq = 0,
                throughSeq = cursor,
            ),
            settledAttempt = settled,
        )
    }

    private fun seedHistory(count: Int) {
        repeat(count) { index ->
            val seq = ++cursor
            events += buildJsonObject { put("seq", seq) }
            messages += RemoteChatMessage(
                messageId = "history-$index",
                seq = seq,
                role = if (index % 2 == 0) 1 else 2,
                status = 2,
                text = "历史 ${index + 1}\n" + unit.take(PresentationWorkload.HISTORY_MESSAGE_CHARS),
            )
        }
    }

    private fun appendChunk(size: Int): Boolean {
        if (attemptText.length >= payload.length) return false
        val end = minOf(attemptText.length + size, payload.length)
        attemptText += payload.substring(attemptText.length, end)
        revision++
        val seq = ++cursor
        events += buildJsonObject { put("seq", seq) }
        return true
    }

    private suspend fun finishReply() {
        replyStatus = 0
        val seq = ++cursor
        events += buildJsonObject { put("seq", seq) }
        messages += RemoteChatMessage("final", seq, role = 2, status = 2, text = attemptText)
        settled = RemoteChatSettlement(attemptId, seq)
        // publish() returns the version it sent; the terminal timestamp is that version's emit time.
        terminalEmitNanos = emitNanos.getValue(publish())
        finish()
    }

    private fun finish() {
        if (!completion.isCompleted) completion.complete(Unit)
    }

    private fun buildPayload(): String {
        if (workload.replyChars <= 0) return ""
        val builder = StringBuilder(workload.replyChars + unit.length)
        while (builder.length < workload.replyChars) {
            builder.append(unit)
            builder.append('\n')
        }
        return builder.take(workload.replyChars).toString()
    }

    private companion object {
        const val PAGE_EVENTS = 256L
        const val OPENING_EVENTS = 64L
    }
}

/** The measurement never crosses a process boundary; bookkeeping only has to be stable in memory. */
internal class InMemoryChatSyncStore : ChatSyncStore {
    private val records = HashMap<ChatSyncKey, ChatSyncRecord>()

    override suspend fun load(key: ChatSyncKey): ChatSyncRecord = records[key] ?: ChatSyncRecord()

    override suspend fun save(key: ChatSyncKey, record: ChatSyncRecord) {
        records[key] = record
    }
}
