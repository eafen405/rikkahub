package me.rerere.rikkahub.data.remote

/** Android publication boundary. Consumption is committed before an attempt, never an outbox. */
interface RemoteNotificationPlatform {
    /** Read at publication, across the entire application, not the chat page. */
    fun isForeground(): Boolean
    fun publish(key: ChatSyncKey, preview: String)
}
