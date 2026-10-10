package org.beconscient.collector.queue

/** Keys of the [KvRow] table. */
object Kv {
    const val USAGE_WATERMARK = "usage.watermark"
    const val USAGE_LAST_READ_AT = "usage.last_read_at"
    const val NEXT_SEQ = "upload.next_seq"
    const val UPLOAD_LAST_OK_AT = "upload.last_ok_at"
    const val UPLOAD_LAST_ATTEMPT_AT = "upload.last_attempt_at"
    const val UPLOAD_LAST_ERROR = "upload.last_error"
    const val UPLOAD_MAX_EVENTS = "upload.max_events"
    const val NOTIFICATIONS_LAST_EVENT_AT = "notifications.last_event_at"
    const val NOTIFICATIONS_LAST_ALIVE_AT = "notifications.last_alive_at"
    const val NOTIFICATIONS_DISCONNECTED_AT = "notifications.disconnected_at"
    const val SHORTS_LAST_EVENT_AT = "shorts.last_event_at"
    const val HEALTH_LAST_AT = "health.last_at"
}
