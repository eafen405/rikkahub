package me.rerere.rikkahub.data.remote

/** Android publication boundary. Consumption is committed before an attempt, never an outbox. */
interface RemoteNotificationPlatform : RemoteForegroundPlatform {
    fun publish(key: ChatSyncKey, preview: String)
}
