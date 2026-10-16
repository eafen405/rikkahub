package me.rerere.rikkahub.ui.identity

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.service.AndroidRemoteNotifications
import me.rerere.rikkahub.service.RemoteReceiveController
import org.koin.compose.koinInject

/** Permission requests are explicit; denial leaves the same settings entry available. */
@Composable
internal fun RemoteReceiveSetup(
    notifications: AndroidRemoteNotifications = koinInject(),
    controller: RemoteReceiveController = koinInject(),
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val enabled by notifications.enabled.collectAsStateWithLifecycle()
    val service by controller.status.collectAsStateWithLifecycle()
    val failure by notifications.failure.collectAsStateWithLifecycle()
    var allowed by remember { mutableStateOf(notifications.notificationsAllowed()) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        allowed = notifications.notificationsAllowed()
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) allowed = notifications.notificationsAllowed()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    RemoteReceiveSetupCard(
        enabled, service.running, failure ?: service.message, allowed,
        onEnabledChange = controller::setEnabled,
        onRequestPermission = {
            if (Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            else context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
        },
        onSettings = { context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) },
        onBackgroundSettings = { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) },
    )
}
