package me.rerere.rikkahub.data.remote

import kotlinx.serialization.Serializable

data class ChatSyncKey(val endpoint: String, val userShortId: Int, val sessionId: String)

@Serializable
data class StoredChatSend(val requestId: String, val text: String, val uploadIds: List<String> = emptyList())

/** Consumption bookkeeping only; history must still reload after process recreation. */
@Serializable
data class ChatSyncRecord(
    val consumedThrough: Long = -1,
    val initialized: Boolean = false,
    val pending: StoredChatSend? = null,
    /** Null establishes a notification baseline; separate from sync and read positions. */
    val notificationThrough: Long? = null,
    /** The episode this record has already claimed, so a later fact never claims it twice. */
    val notificationEpisodeId: String? = null,
    /**
     * A claimed episode the reminder has not published yet. The session only records what it
     * owes; reception sees every session and publishes the backlog once.
     */
    val notificationOwed: Boolean = false,
)

interface ChatSyncStore {
    suspend fun load(key: ChatSyncKey): ChatSyncRecord

    suspend fun save(key: ChatSyncKey, record: ChatSyncRecord)
}
