package org.beconscient.collector.queue

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Queue states of an event row. */
object EventState {
    const val PENDING = 0
    const val IN_BATCH = 1
    const val ACKED = 2
}

/**
 * One event as it will travel, with its full JSON in [payload]. Point events
 * never change, so a frozen batch can be rebuilt byte for byte from these rows.
 */
@Entity(
    tableName = "events",
    indices = [
        Index(value = ["natural_key"], unique = true),
        Index(value = ["state", "time"]),
        Index(value = ["batch_seq"]),
    ],
)
data class EventRow(
    @PrimaryKey val id: String,
    val type: String,
    /** `at` of a point event, `start` of an interval. */
    val time: Long,
    val rev: Int? = null,
    /** Local duplicate guard for re-read facts; never sent. Null when the type has none. */
    @ColumnInfo(name = "natural_key") val naturalKey: String? = null,
    val payload: String,
    val state: Int = EventState.PENDING,
    @ColumnInfo(name = "batch_seq") val batchSeq: Long? = null,
    @ColumnInfo(name = "queued_at") val queuedAt: Long,
    @ColumnInfo(name = "acked_at") val ackedAt: Long? = null,
)

/** A frozen batch: the sequence number is fixed before the first send. */
@Entity(tableName = "batches")
data class BatchRow(
    @PrimaryKey val seq: Long,
    @ColumnInfo(name = "installation_id") val installationId: String,
    @ColumnInfo(name = "frozen_at") val frozenAt: Long,
    val attempts: Int = 0,
    @ColumnInfo(name = "last_attempt_at") val lastAttemptAt: Long? = null,
)

/** The exact, ordered id-and-revision list of a frozen batch. */
@Entity(
    tableName = "batch_items",
    primaryKeys = ["seq", "position"],
    indices = [Index(value = ["event_id"])],
)
data class BatchItemRow(
    val seq: Long,
    val position: Int,
    @ColumnInfo(name = "event_id") val eventId: String,
    val rev: Int? = null,
)

/** Events the server rejected. Kept locally for inspection and never resent. */
@Entity(tableName = "rejected")
data class RejectedRow(
    @PrimaryKey val id: String,
    val type: String,
    val time: Long,
    val reason: String?,
    val seq: Long,
    @ColumnInfo(name = "rejected_at") val rejectedAt: Long,
    val payload: String,
)

/** Small persistent settings: watermark, next sequence number, last upload. */
@Entity(tableName = "kv")
data class KvRow(
    @PrimaryKey val key: String,
    val value: String,
)

/**
 * Maps a notification key to the random ref that travels instead of it.
 * The key identifies a conversation, so this table never leaves the device.
 */
@Entity(tableName = "notification_refs")
data class NotificationRefRow(
    @PrimaryKey val key: String,
    val ref: String,
    @ColumnInfo(name = "package") val packageName: String,
    @ColumnInfo(name = "posted_at") val postedAt: Long,
    @ColumnInfo(name = "last_update_sent_at") val lastUpdateSentAt: Long,
    @ColumnInfo(name = "seen_at") val seenAt: Long,
)

/** A stretch in which a sensor could not observe, for the status screen and health detail. */
@Entity(tableName = "sensor_gaps")
data class SensorGapRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sensor: String,
    @ColumnInfo(name = "from_ms") val fromMs: Long,
    @ColumnInfo(name = "to_ms") val toMs: Long,
    val recovered: Int,
)
