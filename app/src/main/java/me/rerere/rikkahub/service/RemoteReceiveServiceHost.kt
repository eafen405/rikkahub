package me.rerere.rikkahub.service

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * Asking Android for the reception service.
 *
 * Kept as its own boundary because reception ownership is decided from the
 * account, the foreground fact and the service's own reports; starting and
 * stopping the service is the only thing it cannot express as a value.
 */
interface RemoteReceiveServiceHost {
    fun start()
    fun stop()
}

/** The app's own start/stop of [RemoteReceiveService]. */
class RemoteReceiveServiceLauncher(private val context: Context) : RemoteReceiveServiceHost {
    override fun start() {
        ContextCompat.startForegroundService(context, Intent(context, RemoteReceiveService::class.java))
    }

    override fun stop() {
        context.stopService(Intent(context, RemoteReceiveService::class.java))
    }
}
