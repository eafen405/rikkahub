package me.rerere.rikkahub.ui.identity

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.data.remote.RemoteTask
import me.rerere.rikkahub.data.remote.RemoteTaskDetail

/** Task facts come from the shared projection; this view never derives activity or notifications. */
@Composable
internal fun RemoteTaskCards(
    tasks: List<RemoteTask>,
    detail: RemoteTaskDetail?,
    connected: Boolean,
    onOpen: (String) -> Unit,
    onClose: () -> Unit,
    onStop: (String) -> Unit,
) {
    tasks.forEach { task ->
        key(task.taskId) {
            Spacer(Modifier.height(12.dp))
            Card(onClick = { onOpen(task.taskId) }, colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            )) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(task.label, style = MaterialTheme.typography.titleSmallEmphasized)
                    Text(taskStatus(task.status), style = MaterialTheme.typography.labelLarge)
                    if (!connected) Text("连接已断开，显示上次已知状态", style = MaterialTheme.typography.bodySmall)
                    if (task.availability == 0) Text("当前服务器无法提供该任务；不会自动续跑", style = MaterialTheme.typography.bodySmall)
                    task.progress?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    task.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    Text(reportStatus(task.report.status), style = MaterialTheme.typography.bodySmall)
                    Text("查看任务与输出", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
    detail?.let { output ->
        Spacer(Modifier.height(12.dp))
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("任务详情", style = MaterialTheme.typography.titleSmallEmphasized)
                Text(taskStatus(output.status))
                Text("保留输出：字节 ${output.earliest}–${output.total}", style = MaterialTheme.typography.bodySmall)
                if (output.hasGap) Text("部分输出已丢失或不在保留窗口内；以下不是完整日志", color = MaterialTheme.colorScheme.error)
                if (output.availability == 0) Text("输出当前不可用；已知终态不代表日志完整")
                if (output.loading) Text("正在读取输出…")
                output.failure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                output.chunks.forEach { chunk ->
                    if (chunk.gapBefore) Text("…输出缺口…", color = MaterialTheme.colorScheme.error)
                    Text(chunk.text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (output.availability == 1 && output.status in 1..2) {
                        OutlinedButton(onClick = { onStop(output.taskId) }, enabled = !output.stopRequested) {
                            Text(if (output.stopRequested) "已请求停止任务" else "停止任务")
                        }
                    }
                    TextButton(onClick = onClose) { Text("关闭详情") }
                }
            }
        }
    }
}

private fun taskStatus(status: Int) = when (status) {
    1 -> "任务运行中"
    2 -> "任务停止中"
    3 -> "任务已完成"
    4 -> "任务已停止"
    5 -> "任务失败"
    else -> "任务状态未知"
}

private fun reportStatus(status: Int) = when (status) {
    1 -> "结果报告待处理"
    2 -> "正在报告结果"
    3 -> "结果已回到原会话"
    4 -> "结果报告已取消"
    5 -> "结果报告失败"
    6 -> "结果报告状态未知"
    else -> "未安排独立结果报告"
}
