package com.example.flowave.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "tracks")
data class Track(
    @PrimaryKey val id: String,
    val title: String,
    val artist: String,
    val album: String = "Unknown Album",
    val durationMs: Long = 0L,
    val mediaUri: String,
    val artworkUri: String? = null,
    val isOnline: Boolean = false,
    val source: String = "LOCAL", // LOCAL, YOUTUBE, DOWNLOADED
    /** Stable source identifier used to refresh expiring online URLs. */
    val sourceId: String? = null,
    val genre: String? = null,
    val year: Int? = null,
    val lyrics: String? = null,
    val folderPath: String? = null,
    val playCount: Int = 0,
    val lastPlayedTimestamp: Long = 0L,
    val isFavorite: Boolean = false
)

@Entity(tableName = "playlists")
data class Playlist(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val description: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val iconName: String = "playlist"
)

@Entity(tableName = "playlist_track_cross_ref", primaryKeys = ["playlistId", "trackId"])
data class PlaylistTrackCrossRef(
    val playlistId: Long,
    val trackId: String,
    val addedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "listening_stats")
data class ListeningStat(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trackId: String,
    val trackTitle: String,
    val artistName: String,
    val durationPlayedMs: Long,
    val timestamp: Long = System.currentTimeMillis()
)

data class LrcLine(
    val timestampMs: Long,
    val text: String
)

data class InnerTubeTrack(
    val id: String,
    val title: String,
    val artist: String,
    val durationText: String,
    val thumbnailUrl: String,
    val album: String = ""
)

data class UserProfile(
    val username: String = "Audio Enthusiast",
    val bio: String = "Loving ultra-fidelity sound with FloWave",
    val avatarUrl: String? = null,
    val preferredTheme: String = "GLASS", // GLASS, AMOLED, CYBER_NEON, MIDNIGHT
    val preferredQuality: String = "Ultra FLAC (24-bit)",
    val customBgUrl: String? = null,
    val backgroundPreset: String = "LIQUID_GLASS", // LIQUID_GLASS, AMOLED_DARK, CYBER_CYAN, NEON_PURPLE
    val totalTimeMs: Long = 0L,
    val playCountTotal: Int = 0,
    val streakDays: Int = 5
)

enum class DownloadStatus {
    QUEUED, DOWNLOADING, DONE, FAILED
}

@Entity(tableName = "download_entries")
data class DownloadEntry(
    @PrimaryKey val id: String,
    val trackTitle: String,
    val artistName: String,
    val thumbnailUrl: String? = null,
    val downloadUrl: String,
    val filePath: String? = null,
    val progress: Float = 0f,
    val bytesDownloaded: Long = 0L,
    val totalBytes: Long = 0L,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val errorMessage: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null
)
