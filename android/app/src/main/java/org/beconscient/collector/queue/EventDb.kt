package org.beconscient.collector.queue

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        EventRow::class,
        BatchRow::class,
        BatchItemRow::class,
        RejectedRow::class,
        KvRow::class,
        NotificationRefRow::class,
        SensorGapRow::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class EventDb : RoomDatabase() {
    abstract fun dao(): EventDao

    companion object {
        const val FILE_NAME = "queue.db"

        /** The queue lives in app-private, credential-encrypted storage. */
        fun open(context: Context): EventDb =
            Room.databaseBuilder(context.applicationContext, EventDb::class.java, FILE_NAME).build()
    }
}
