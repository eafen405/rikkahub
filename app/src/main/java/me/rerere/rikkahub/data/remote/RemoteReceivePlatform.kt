package me.rerere.rikkahub.data.remote

import kotlinx.coroutines.flow.StateFlow

/**
 * What reception ownership needs from the Android notification boundary.
 *
 * Publication ([RemoteNotificationPlatform]) and reception (the controller that
 * decides whether the account is observed and whether the receive service runs)
 * ask this same object, so there is one foreground fact in the app rather than a
 * remembered copy that can drift from it.
 */
interface RemoteReceivePlatform : RemoteForegroundPlatform {
    /** The receive switch the user set; reception follows it. */
    val enabled: StateFlow<Boolean>

    fun setEnabled(value: Boolean)
}
