package me.rerere.rikkahub.service

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.remote.AccountSession
import me.rerere.rikkahub.data.remote.RemoteReceiveState

data class RemoteReceiveServiceStatus(val running: Boolean = false, val message: String = "后台接收未开启")

/** App/account ownership, independent of whichever Compose page is visible. */
class RemoteReceiveController(
    private val context: Context,
    private val account: AccountSession,
    private val receiver: RemoteReceiveState,
    private val notifications: AndroidRemoteNotifications,
    scope: CoroutineScope,
) {
    private val mutableStatus = MutableStateFlow(RemoteReceiveServiceStatus())
    val status = mutableStatus.asStateFlow()
    private var foreground = notifications.isForeground()
    private var starting = false

    init {
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                foreground = true
                if (notifications.enabled.value && account.isSignedIn) requestService()
                reconcile()
            }
            override fun onStop(owner: LifecycleOwner) { foreground = false; reconcile() }
        })
        scope.launch {
            account.identity.collect { identity ->
                receiver.bind(identity?.token, identity?.user?.shortId)
                if (identity == null) stopService()
                else if (notifications.enabled.value && foreground) requestService()
                reconcile()
            }
        }
    }

    suspend fun openNotification(sessionId: String): Boolean {
        val identity = account.identity.value ?: return false
        // A cold click may arrive before the collector resumes after restore.
        receiver.bind(identity.token, identity.user.shortId)
        val opened = receiver.openNotification(sessionId)
        return opened && account.identity.value == identity
    }

    fun setEnabled(value: Boolean) {
        try { notifications.setEnabled(value) }
        catch (failure: Exception) { mutableStatus.value = mutableStatus.value.copy(message = failure.message ?: "设置保存失败"); return }
        if (value && account.isSignedIn) requestService() else stopService()
        reconcile()
    }

    private fun requestService() {
        if (starting || mutableStatus.value.running) return
        starting = true
        mutableStatus.value = RemoteReceiveServiceStatus(message = "正在启动后台接收…")
        try { ContextCompat.startForegroundService(context, Intent(context, RemoteReceiveService::class.java)) }
        catch (failure: RuntimeException) { serviceStopped("系统拒绝后台接收：${failure.message}") }
    }

    private fun stopService() {
        context.stopService(Intent(context, RemoteReceiveService::class.java))
        serviceStopped(if (notifications.enabled.value) "登录后可恢复后台接收" else "后台接收未开启")
    }

    fun serviceStarted() {
        starting = false
        mutableStatus.value = RemoteReceiveServiceStatus(true, "接收已启动；连接断开后会尝试重连")
        reconcile()
    }

    fun serviceStopped(reason: String? = null) {
        starting = false
        mutableStatus.value = RemoteReceiveServiceStatus(message = reason ?: "接收服务已停止；重新打开应用可恢复")
        reconcile()
    }

    private fun reconcile() {
        if (account.isSignedIn && (foreground || mutableStatus.value.running)) receiver.resumeObservation()
        else receiver.stopObservation()
    }
}
