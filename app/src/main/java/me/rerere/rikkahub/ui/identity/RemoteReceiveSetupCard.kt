package me.rerere.rikkahub.ui.identity

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 后台接收设置卡片。纯展示与回调：开关状态、接收状态、状态文案与通知权限都由调用方传入，
 * 本文件不读偏好、不查权限、不启停服务，也不主动请求通知权限。
 */
@Composable
internal fun RemoteReceiveSetupCard(
    enabled: Boolean,
    receiving: Boolean,
    status: String,
    notificationsAllowed: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onRequestPermission: () -> Unit,
    onSettings: () -> Unit,
    onBackgroundSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "后台接收",
                    style = MaterialTheme.typography.titleSmallEmphasized,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = enabled, onCheckedChange = onEnabledChange)
            }
            if (receiving) {
                Text("接收服务运行中", style = MaterialTheme.typography.bodySmall)
            }
            Text(status, style = MaterialTheme.typography.bodySmall)
            Text(
                "后台接收需用户开启；进程回收、Doze、强行停止可能中断，重新打开应用恢复；不承诺持续在线。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!notificationsAllowed) {
                Text(
                    "通知权限未开启，收到回复时无法及时提醒。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (!notificationsAllowed) {
                    OutlinedButton(onClick = onRequestPermission) {
                        Text("请求通知权限")
                    }
                }
                OutlinedButton(onClick = onSettings) {
                    Text("打开通知设置")
                }
                OutlinedButton(onClick = onBackgroundSettings) {
                    Text("后台与电池设置")
                }
            }
        }
    }
}
