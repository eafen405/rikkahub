package me.rerere.rikkahub.ui.identity

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.data.remote.RemoteActivity

/** The one status the backend reports as running. */
private const val STATUS_ACTIVE = 2

/**
 * Shows the activity the backend already reported, and nothing else: no event scanning, no
 * network, no local derivation, no notification behavior or setting.
 *
 * Reasons are shown only for an active status. A quiet session that still carries a delivery fact
 * stays quiet — a finished delivery is not running work. Unknown stays unknown and is never worded
 * as silence.
 */
@Composable
internal fun RemoteActivityCard(
    activity: RemoteActivity,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(activityStatus(activity.status), style = MaterialTheme.typography.titleSmallEmphasized)
            if (activity.status == STATUS_ACTIVE) {
                activityReasons(activity.reasons).forEach { reason ->
                    Text(reason, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

private fun activityStatus(status: Int) = when (status) {
    1 -> "会话静默"
    STATUS_ACTIVE -> "Fairy 正在工作"
    else -> "活动状态未知"
}

/** Known reasons in a stable order; codes the backend may add later are simply not shown. */
private val REASON_LABELS = listOf(
    1 to "正在执行指令",
    2 to "后台任务进行中",
    3 to "正在整理结果报告",
    4 to "正在投递回复",
)

private fun activityReasons(reasons: List<Int>) =
    REASON_LABELS.filter { (code, _) -> code in reasons }.map { (_, label) -> label }
