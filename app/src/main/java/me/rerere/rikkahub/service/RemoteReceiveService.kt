package me.rerere.rikkahub.service

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.data.remote.AccountSession
import org.koin.android.ext.android.inject

/** A client observation lifetime only. Teardown never stops remote replies or jobs. */
class RemoteReceiveService : Service() {
    private val controller: RemoteReceiveController by inject()
    private val account: AccountSession by inject()
    private val notifications: AndroidRemoteNotifications by inject()
    override fun onBind(intent: Intent?): IBinder? = null
    private var stoppedReason: String? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A queued start may arrive after disable or sign-out.
        if (!notifications.enabled.value || !account.isSignedIn) {
            stopSelf()
            return START_NOT_STICKY
        }
        val launch = PendingIntent.getActivity(this, 0, Intent(this, RouteActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(this, AndroidRemoteNotifications.RECEIVE_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_rikkahub).setContentTitle("Fairy 后台接收")
            .setContentText("保持会话同步；系统可能限制后台连接")
            .setContentIntent(launch).setOngoing(true).build()
        try {
            if (Build.VERSION.SDK_INT >= 34) ServiceCompat.startForeground(this, 3101, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(3101, notification)
            controller.serviceStarted()
        } catch (failure: RuntimeException) {
            stoppedReason = "系统拒绝接收服务：${failure.message}"
            controller.serviceStopped(stoppedReason)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        stoppedReason = "系统已停止后台接收；重新打开应用可恢复"
        stopSelf()
    }

    override fun onDestroy() {
        controller.serviceStopped(stoppedReason)
        super.onDestroy()
    }
}
