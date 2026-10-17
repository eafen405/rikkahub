package me.rerere.rikkahub.data.remote

/**
 * The one foreground fact the app reads, declared once.
 *
 * Publication ([RemoteNotificationPlatform]) and reception ([RemoteReceivePlatform]) both ask the
 * same platform object, so the fact is not restated per boundary: two declarations of `isForeground`
 * are how the boundaries drift apart. Android's implementation answers from `ProcessLifecycleOwner`
 * at the moment of the read, never from a remembered value.
 */
interface RemoteForegroundPlatform {
    /** Read when a decision is taken, across the entire application, not the page. */
    fun isForeground(): Boolean
}
