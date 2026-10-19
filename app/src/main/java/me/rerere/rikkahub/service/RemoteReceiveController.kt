package me.rerere.rikkahub.service

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.remote.AccountSession
import me.rerere.rikkahub.data.remote.RemoteReceivePlatform
import me.rerere.rikkahub.data.remote.RemoteReceiveState

data class RemoteReceiveServiceStatus(val running: Boolean = false, val message: String = "后台接收未开启")

/** App/account ownership, independent of whichever Compose page is visible. */
class RemoteReceiveController(
    private val account: AccountSession,
    private val receiver: RemoteReceiveState,
    private val platform: RemoteReceivePlatform,
    private val service: RemoteReceiveServiceHost,
    lifecycle: Lifecycle,
    scope: CoroutineScope,
) {
    private val mutableStatus = MutableStateFlow(RemoteReceiveServiceStatus())
    val status = mutableStatus.asStateFlow()
    private var starting = false

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                if (platform.enabled.value && account.isSignedIn) requestService()
                reconcile()
            }

            override fun onStop(owner: LifecycleOwner) = reconcile()
        })
        scope.launch {
            account.identity.collect { identity ->
                // Reception itself is bound by the identity publisher in the turn it publishes the
                // account; this collector owns the service/observation boundary only.
                if (identity == null) stopService()
                else if (platform.enabled.value && platform.isForeground()) requestService()
                reconcile()
            }
        }
    }

    suspend fun openNotification(sessionId: String): Boolean {
        val identity = account.identity.value ?: return false
        val opened = receiver.openSession(sessionId)
        return opened && account.identity.value == identity
    }

    fun setEnabled(value: Boolean) {
        try { platform.setEnabled(value) }
        catch (failure: Exception) { mutableStatus.value = mutableStatus.value.copy(message = failure.message ?: "设置保存失败"); return }
        if (value && account.isSignedIn) requestService() else stopService()
        reconcile()
    }

    private fun requestService() {
        if (starting || mutableStatus.value.running) return
        starting = true
        mutableStatus.value = RemoteReceiveServiceStatus(message = "正在启动后台接收…")
        try { service.start() }
        catch (failure: RuntimeException) { serviceStopped("系统拒绝后台接收：${failure.message}") }
    }

    private fun stopService() {
        service.stop()
        serviceStopped(if (platform.enabled.value) "登录后可恢复后台接收" else "后台接收未开启")
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

    /** Ask the platform now, never a remembered answer: publication asks the same object. */
    private fun reconcile() {
        if (account.isSignedIn && (platform.isForeground() || mutableStatus.value.running)) receiver.resumeObservation()
        else receiver.stopObservation()
    }
}
