package me.rerere.rikkahub.data.remote

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.security.MessageDigest

/** Small per-deployment/account/session bookkeeping, with awaited local commits. */
class AndroidChatSyncStore(context: Context) : ChatSyncStore {

    private val preferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun load(key: ChatSyncKey): ChatSyncRecord = withContext(Dispatchers.IO) {
        try {
            val stored = preferences.getString(key.preferenceKey(), null)
                ?: return@withContext ChatSyncRecord()
            json.decodeFromString<ChatSyncRecord>(stored)
        } catch (unreadable: SerializationException) {
            ChatSyncRecord()
        } catch (mistyped: ClassCastException) {
            ChatSyncRecord()
        }
    }

    override suspend fun save(key: ChatSyncKey, record: ChatSyncRecord) {
        withContext(Dispatchers.IO) {
            val written = json.encodeToString(record)
            check(preferences.edit().putString(key.preferenceKey(), written).commit()) {
                "the chat sync record could not be written"
            }
        }
    }

        private fun ChatSyncKey.preferenceKey(): String {
        val canonical = json.encodeToString(KeyTuple(endpoint.trimEnd('/'), userShortId, sessionId))
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return buildString(digest.size * 2) {
            for (byte in digest) {
                val value = byte.toInt() and 0xFF
                append(HEX[value ushr 4])
                append(HEX[value and 0x0F])
            }
        }
    }

    @Serializable
    private data class KeyTuple(val endpoint: String, val userShortId: Int, val sessionId: String)

    private companion object {
        const val PREFERENCES_NAME = "ourfairy.chat-sync"
        const val HEX = "0123456789abcdef"
    }
}
