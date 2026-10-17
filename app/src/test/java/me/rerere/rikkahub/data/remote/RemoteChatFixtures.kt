package me.rerere.rikkahub.data.remote

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Raw-event fixtures for the state tests.
 *
 * The sync owner accepts a prefix with exactly one event per sequence and refuses a gap, so a
 * fixture declares the range it covers through [eventCoverage] instead of re-deriving the
 * continuity rule in every projection builder.
 */
fun remoteEvent(seq: Long): JsonObject =
    Json.parseToJsonElement("""{"seq":$seq,"type":"fact"}""") as JsonObject

/** One raw event for every sequence from [from] through [through]. */
fun eventCoverage(from: Long, through: Long): List<JsonObject> = (from..through).map(::remoteEvent)

/** A projected Fairy delivery at [seq], with the reminder facts the notification tests expect. */
fun deliveryProjection(seq: Long, version: Long = 1) = RemoteChatProjection(
    sessionId = "s", generation = "g", version = version, cursor = seq,
    messages = listOf(RemoteChatMessage("delivery$seq", seq, role = 2, status = 2, text = "delivered$seq")),
    events = eventCoverage(0, seq),
    activity = RemoteActivity(status = 1, episodeId = "s/$seq", throughSeq = seq,
        completed = RemoteCompletedActivity("s/$seq", seq, seq, seq),
        delivery = RemoteActivityDelivery("s/$seq", seq)),
)
