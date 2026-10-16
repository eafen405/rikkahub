package me.rerere.rikkahub.data.remote

import kotlinx.serialization.Serializable

/**
 * Backend-owned activity reasons and episode facts. The shared snapshot separately marks
 * incomplete observation/recovery as unknown; pages never derive activity from raw events.
 *
 * [status]: 0 unknown, 1 quiet, 2 active. Unknown is not quiet: it means the backend did not say,
 * so nothing may be presented as silence.
 * [reasons]: why it is active, 1 execution, 2 jobs, 3 report, 4 delivery. Only meaningful while
 * [status] is 2.
 * [episodeId]: which run this describes, so a later episode never reuses an earlier one.
 * [startedSeq]: first sequence the run covers, null when not started.
 * [throughSeq]: last sequence the run has covered, -1 when none.
 *
 * A quiet run may still carry [delivery] or a finished [completed]; that is a fact, not running
 * work, and it must not be shown as activity.
 */
@Serializable
data class RemoteActivity(
    val status: Int = 0,
    val reasons: List<Int> = emptyList(),
    val episodeId: String? = null,
    val startedSeq: Long? = null,
    val throughSeq: Long = -1,
    val completed: RemoteCompletedActivity? = null,
    val delivery: RemoteActivityDelivery? = null,
)

/** One finished run: what it covered, and where its output landed. */
@Serializable
data class RemoteCompletedActivity(
    val episodeId: String,
    val startedSeq: Long,
    val finishedSeq: Long,
    val outputSeq: Long? = null,
)

/** Output that reached the session at [seq], whether or not the run is still active. */
@Serializable
data class RemoteActivityDelivery(
    val episodeId: String,
    val seq: Long,
)
