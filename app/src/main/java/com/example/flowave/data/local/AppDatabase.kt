package com.example.flowave.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.flowave.data.model.DownloadEntry
import com.example.flowave.data.model.ListeningStat
import com.example.flowave.data.model.Playlist
import com.example.flowave.data.model.PlaylistTrackCrossRef
import com.example.flowave.data.model.Track
import com.example.flowave.data.model.QueueItem
import com.example.flowave.data.model.QueueState

@Database(
    entities = [
        Track::class,
        Playlist::class,
        PlaylistTrackCrossRef::class,
        ListeningStat::class,
        DownloadEntry::class,
        QueueItem::class,
        QueueState::class
    ],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun statDao(): StatDao
    abstract fun downloadDao(): DownloadDao
    abstract fun queueDao(): QueueDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "flowave_db"
                )
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
