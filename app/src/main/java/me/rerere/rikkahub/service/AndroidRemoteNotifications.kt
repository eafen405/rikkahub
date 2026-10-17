package me.rerere.rikkahub.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.data.remote.ChatSyncKey
import me.rerere.rikkahub.data.remote.RemoteNotificationPlatform
import me.rerere.rikkahub.data.remote.RemoteReceivePlatform
import me.rerere.rikkahub.ui.identity.RemoteNotificationTarget

/**
 * Android's notification and reception platform: it publishes reminders and answers the
 * foreground/enabled facts reception reads. Eligibility and durable consumption belong to shared
 * session sync, never here.
 */
class AndroidRemoteNotifications(private val context: Context) : RemoteNotificationPlatform, RemoteReceivePlatform {
    private val preferences = context.getSharedPreferences("ourfairy.remote-receive", Context.MODE_PRIVATE)
    private val mutableEnabled = MutableStateFlow(preferences.getBoolean("enabled", false))
    override val enabled = mutableEnabled.asStateFlow()
    private val mutableFailure = MutableStateFlow<String?>(null)
    val failure = mutableFailure.asStateFlow()

    init {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(MESSAGES_CHANNEL, "Fairy 会话提醒", NotificationManager.IMPORTANCE_DEFAULT))
        manager.createNotificationChannel(NotificationChannel(RECEIVE_CHANNEL, "Fairy 后台接收", NotificationManager.IMPORTANCE_LOW))
    }

    override fun setEnabled(value: Boolean) {
        check(preferences.edit().putBoolean("enabled", value).commit()) { "无法保存后台接收设置" }
        mutableEnabled.value = value
    }

    fun notificationsAllowed(): Boolean {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        return context.getSystemService(NotificationManager::class.java).getNotificationChannel(MESSAGES_CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    override fun isForeground() = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    @SuppressLint("MissingPermission")
    override fun publish(key: ChatSyncKey, preview: String) {
        if (!notificationsAllowed()) {
            mutableFailure.value = "通知权限或会话提醒渠道已关闭；内容仍会同步"
            return
        }
        val target = RemoteNotificationTarget(key.endpoint, key.userShortId, key.sessionId)
        val intent = Intent(context, RouteActivity::class.java).apply {
            action = ACTION_OPEN_REMOTE
            data = Uri.Builder().scheme("ourfairy").authority("conversation")
                .appendPath(key.endpoint).appendPath(key.userShortId.toString()).appendPath(key.sessionId).build()
            putExtra(EXTRA_ENDPOINT, target.endpoint)
            putExtra(EXTRA_ACCOUNT, target.userShortId)
            putExtra(EXTRA_SESSION, target.sessionId)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val content = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(context, MESSAGES_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_rikkahub).setContentTitle("Fairy")
            .setContentText(preview).setStyle(NotificationCompat.BigTextStyle().bigText(preview))
            .setContentIntent(content).setAutoCancel(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build()
        try {
            // The URI/tag is the complete tuple; unrelated sessions never share an integer hash.
            NotificationManagerCompat.from(context).notify(intent.data.toString(), MESSAGE_ID, notification)
            mutableFailure.value = null
        } catch (failure: RuntimeException) {
            mutableFailure.value = "系统未能发布提醒：${failure.message}"
            throw failure
        }
    }

    companion object {
        const val MESSAGES_CHANNEL = "ourfairy.remote.messages"
        const val RECEIVE_CHANNEL = "ourfairy.remote.receive"
        const val ACTION_OPEN_REMOTE = "me.rerere.rikkahub.OPEN_REMOTE_SESSION"
        const val EXTRA_ENDPOINT = "remote_endpoint"
        const val EXTRA_ACCOUNT = "remote_account"
        const val EXTRA_SESSION = "remote_session"
        private const val MESSAGE_ID = 1

        fun target(intent: Intent): RemoteNotificationTarget? {
            val endpoint = intent.getStringExtra(EXTRA_ENDPOINT) ?: return null
            val session = intent.getStringExtra(EXTRA_SESSION) ?: return null
            val account = intent.getIntExtra(EXTRA_ACCOUNT, 0)
            return if (account > 0) RemoteNotificationTarget(endpoint, account, session) else null
        }
    }
}
