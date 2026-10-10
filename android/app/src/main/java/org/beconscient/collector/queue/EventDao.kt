package org.beconscient.collector.queue

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
abstract class EventDao {

    // ---- key/value --------------------------------------------------------

    @Query("SELECT value FROM kv WHERE `key` = :key")
    abstract suspend fun kv(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun putKv(row: KvRow)

    @Query("DELETE FROM kv WHERE `key` = :key")
    abstract suspend fun deleteKv(key: String)

    suspend fun long(key: String): Long? = kv(key)?.toLongOrNull()

    suspend fun putLong(key: String, value: Long) = putKv(KvRow(key, value.toString()))

    suspend fun putString(key: String, value: String) = putKv(KvRow(key, value))

    // ---- writing events ---------------------------------------------------

    /** Returns the row id per input, -1 for rows skipped by the unique natural key. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertIgnore(rows: List<EventRow>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertIgnore(row: EventRow): Long

    /** Writes usage events and the read watermark in one transaction. Returns how many were new. */
    @Transaction
    open suspend fun insertUsage(rows: List<EventRow>, watermark: Long, readAt: Long): Int {
        val inserted = if (rows.isEmpty()) emptyList() else insertIgnore(rows)
        putLong(Kv.USAGE_WATERMARK, watermark)
        putLong(Kv.USAGE_LAST_READ_AT, readAt)
        return inserted.count { it != -1L }
    }

    /** Writes one event and stamps a "last event" key in the same transaction. */
    @Transaction
    open suspend fun insertWithStamp(row: EventRow, stampKey: String, stamp: Long): Boolean {
        val id = insertIgnore(row)
        putLong(stampKey, stamp)
        return id != -1L
    }

    // ---- freezing and sending batches --------------------------------------

    @Query("SELECT * FROM batches ORDER BY seq LIMIT 1")
    abstract suspend fun openBatch(): BatchRow?

    @Query("SELECT * FROM batch_items WHERE seq = :seq ORDER BY position")
    abstract suspend fun batchItems(seq: Long): List<BatchItemRow>

    @Query(
        "SELECT e.* FROM batch_items b JOIN events e ON e.id = b.event_id " +
            "WHERE b.seq = :seq ORDER BY b.position",
    )
    abstract suspend fun batchEvents(seq: Long): List<EventRow>

    @Query("SELECT * FROM events WHERE state = 0 ORDER BY time, id LIMIT :limit")
    abstract suspend fun pendingOldestFirst(limit: Int): List<EventRow>

    @Insert
    protected abstract suspend fun insertBatch(row: BatchRow)

    @Insert
    protected abstract suspend fun insertBatchItems(rows: List<BatchItemRow>)

    @Query(
        "UPDATE events SET state = 1, batch_seq = :seq " +
            "WHERE id IN (SELECT event_id FROM batch_items WHERE seq = :seq)",
    )
    protected abstract suspend fun markInBatch(seq: Long)

    @Query("UPDATE batches SET attempts = attempts + 1, last_attempt_at = :at WHERE seq = :seq")
    abstract suspend fun recordAttempt(seq: Long, at: Long)

    /**
     * Freezes the oldest pending events into batch [seq], at most [maxEvents]
     * and at most [maxPayloadBytes] of event JSON. The id list, its order and
     * the next sequence number are stored in the same transaction.
     */
    @Transaction
    open suspend fun freeze(
        seq: Long,
        installationId: String,
        now: Long,
        maxEvents: Int,
        maxPayloadBytes: Int,
    ): BatchRow? {
        if (openBatch() != null) return null
        val candidates = pendingOldestFirst(maxEvents)
        if (candidates.isEmpty()) return null
        var bytes = 0
        val chosen = ArrayList<EventRow>(candidates.size)
        for (row in candidates) {
            val size = row.payload.length + 1
            if (chosen.isNotEmpty() && bytes + size > maxPayloadBytes) break
            chosen += row
            bytes += size
        }
        val batch = BatchRow(seq = seq, installationId = installationId, frozenAt = now)
        insertBatch(batch)
        insertBatchItems(chosen.mapIndexed { i, e -> BatchItemRow(seq, i, e.id, e.rev) })
        markInBatch(seq)
        putLong(Kv.NEXT_SEQ, seq + 1)
        return batch
    }

    @Query("UPDATE events SET state = 2, acked_at = :at, batch_seq = NULL WHERE batch_seq = :seq AND id IN (:ids)")
    protected abstract suspend fun ackChunk(seq: Long, ids: List<String>, at: Long)

    @Query(
        "INSERT OR REPLACE INTO rejected (id, type, time, reason, seq, rejected_at, payload) " +
            "SELECT id, type, time, :reason, :seq, :at, payload FROM events WHERE batch_seq = :seq AND id = :id",
    )
    protected abstract suspend fun copyToRejected(seq: Long, id: String, reason: String?, at: Long)

    @Query("DELETE FROM events WHERE batch_seq = :seq AND id = :id")
    protected abstract suspend fun deleteFromBatch(seq: Long, id: String)

    @Query("UPDATE events SET state = 0, batch_seq = NULL WHERE batch_seq = :seq")
    protected abstract suspend fun releaseBatch(seq: Long)

    @Query("DELETE FROM batch_items WHERE seq = :seq")
    protected abstract suspend fun deleteBatchItems(seq: Long)

    @Query("DELETE FROM batches WHERE seq = :seq")
    protected abstract suspend fun deleteBatch(seq: Long)

    /**
     * Applies a verified receipt for batch [seq]. The caller passes only ids
     * that belong to this batch; the SQL also requires `batch_seq = seq`.
     * Events the receipt does not mention go back to pending.
     */
    @Transaction
    open suspend fun applyReceipt(seq: Long, acked: List<String>, rejected: Map<String, String?>, at: Long) {
        acked.chunked(500).forEach { ackChunk(seq, it, at) }
        for ((id, reason) in rejected) {
            copyToRejected(seq, id, reason, at)
            deleteFromBatch(seq, id)
        }
        releaseBatch(seq)
        deleteBatchItems(seq)
        deleteBatch(seq)
        putLong(Kv.UPLOAD_LAST_OK_AT, at)
        deleteKv(Kv.UPLOAD_LAST_ERROR)
    }

    /** Drops a batch the server never logged (413) so its events are re-frozen smaller. */
    @Transaction
    open suspend fun dropUnsentBatch(seq: Long) {
        releaseBatch(seq)
        deleteBatchItems(seq)
        deleteBatch(seq)
        putLong(Kv.NEXT_SEQ, seq)
    }

    @Query("UPDATE events SET state = 0, batch_seq = NULL WHERE state = 1")
    protected abstract suspend fun releaseAllBatches()

    @Query("DELETE FROM batch_items")
    protected abstract suspend fun deleteAllBatchItems()

    @Query("DELETE FROM batches")
    protected abstract suspend fun deleteAllBatches()

    /** A new pairing starts a new installation: its sequence restarts at 1. */
    @Transaction
    open suspend fun resetForNewInstallation() {
        releaseAllBatches()
        deleteAllBatchItems()
        deleteAllBatches()
        putLong(Kv.NEXT_SEQ, 1)
        deleteKv(Kv.UPLOAD_LAST_ERROR)
    }

    // ---- retention and resend ----------------------------------------------

    @Query("DELETE FROM events WHERE state = 2 AND acked_at < :before")
    abstract suspend fun purgeAcked(before: Long): Int

    @Query("DELETE FROM rejected WHERE rejected_at < :before")
    abstract suspend fun purgeRejected(before: Long): Int

    @Query("DELETE FROM sensor_gaps WHERE to_ms < :before")
    abstract suspend fun purgeGaps(before: Long): Int

    @Query("SELECT COUNT(*) FROM events WHERE state = 2 AND time >= :since")
    abstract suspend fun countAckedSince(since: Long): Int

    /** "Resend since T": acknowledged events from T onward go back to the queue as they are. */
    @Query("UPDATE events SET state = 0, acked_at = NULL WHERE state = 2 AND time >= :since")
    abstract suspend fun requeueAckedSince(since: Long): Int

    // ---- status -----------------------------------------------------------

    @Query("SELECT COUNT(*) FROM events WHERE state = :state")
    abstract suspend fun countByState(state: Int): Int

    @Query("SELECT COUNT(*) FROM events WHERE state IN (0, 1)")
    abstract suspend fun countUnsent(): Int

    @Query("SELECT MIN(queued_at) FROM events WHERE state IN (0, 1)")
    abstract suspend fun oldestUnsentQueuedAt(): Long?

    @Query("SELECT COUNT(*) FROM rejected")
    abstract suspend fun countRejected(): Int

    @Query("SELECT * FROM rejected ORDER BY rejected_at DESC LIMIT :limit")
    abstract suspend fun recentRejected(limit: Int): List<RejectedRow>

    @Query("SELECT * FROM events ORDER BY time, id")
    abstract suspend fun allEvents(): List<EventRow>

    @Query("SELECT * FROM events WHERE id = :id")
    abstract suspend fun event(id: String): EventRow?

    @Query("SELECT * FROM events WHERE type = :type ORDER BY time, id")
    abstract suspend fun eventsOfType(type: String): List<EventRow>

    @Query("SELECT * FROM rejected")
    abstract suspend fun allRejected(): List<RejectedRow>

    // ---- notification refs (local only) -------------------------------------

    @Query("SELECT * FROM notification_refs WHERE `key` = :key")
    abstract suspend fun notificationRef(key: String): NotificationRefRow?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun putNotificationRef(row: NotificationRefRow)

    @Query("DELETE FROM notification_refs WHERE `key` = :key")
    abstract suspend fun deleteNotificationRef(key: String)

    @Query("SELECT * FROM notification_refs")
    abstract suspend fun allNotificationRefs(): List<NotificationRefRow>

    @Query("DELETE FROM notification_refs WHERE seen_at < :before")
    abstract suspend fun purgeNotificationRefs(before: Long): Int

    // ---- sensor gaps --------------------------------------------------------

    @Insert
    abstract suspend fun insertGap(row: SensorGapRow): Long

    @Query("SELECT * FROM sensor_gaps WHERE sensor = :sensor ORDER BY to_ms DESC LIMIT 1")
    abstract suspend fun lastGap(sensor: String): SensorGapRow?

    @Query("SELECT * FROM sensor_gaps ORDER BY to_ms DESC LIMIT :limit")
    abstract suspend fun recentGaps(limit: Int): List<SensorGapRow>
}
