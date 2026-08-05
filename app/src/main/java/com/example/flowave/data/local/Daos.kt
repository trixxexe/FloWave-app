package com.example.flowave.data.local

import androidx.room.*
import com.example.flowave.data.model.DownloadEntry
import com.example.flowave.data.model.DownloadStatus
import com.example.flowave.data.model.ListeningStat
import com.example.flowave.data.model.Playlist
import com.example.flowave.data.model.PlaylistTrackCrossRef
import com.example.flowave.data.model.Track
import com.example.flowave.data.model.QueueItem
import com.example.flowave.data.model.QueueState
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {
    @Query("SELECT * FROM tracks ORDER BY title ASC")
    fun getAllTracks(): Flow<List<Track>>

    @Query("SELECT * FROM tracks WHERE isFavorite = 1 ORDER BY title ASC")
    fun getFavoriteTracks(): Flow<List<Track>>

    @Query("SELECT * FROM tracks WHERE source = 'DOWNLOADED' ORDER BY lastPlayedTimestamp DESC")
    fun getDownloadedTracks(): Flow<List<Track>>

    @Query("SELECT * FROM tracks WHERE id = :id LIMIT 1")
    suspend fun getTrackById(id: String): Track?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrack(track: Track)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTracks(tracks: List<Track>)

    @Update
    suspend fun updateTrack(track: Track)

    @Delete
    suspend fun deleteTrack(track: Track)

    @Query("SELECT * FROM tracks WHERE title LIKE '%' || :query || '%' OR artist LIKE '%' || :query || '%' OR album LIKE '%' || :query || '%'")
    fun searchTracks(query: String): Flow<List<Track>>

    @Query("SELECT * FROM tracks WHERE playCount > 0 ORDER BY playCount DESC LIMIT 50")
    fun getMostPlayedTracks(): Flow<List<Track>>

    @Query("SELECT * FROM tracks WHERE lastPlayedTimestamp > 0 ORDER BY lastPlayedTimestamp DESC LIMIT 50")
    fun getRecentlyPlayedTracks(): Flow<List<Track>>

    @Query("SELECT * FROM tracks ORDER BY id DESC LIMIT 50")
    fun getRecentlyAddedTracks(): Flow<List<Track>>

    @Query("DELETE FROM tracks WHERE id IN (:ids)")
    suspend fun deleteTracksByIds(ids: List<String>)

    @Query("DELETE FROM tracks WHERE source = 'LOCAL'")
    suspend fun deleteLocalTracks()
}

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY createdAt DESC")
    fun getAllPlaylists(): Flow<List<Playlist>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylist(playlist: Playlist): Long

    @Delete
    suspend fun deletePlaylist(playlist: Playlist)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addTrackToPlaylist(crossRef: PlaylistTrackCrossRef)

    @Query("""
        SELECT t.* FROM tracks t
        INNER JOIN playlist_track_cross_ref ref ON t.id = ref.trackId
        WHERE ref.playlistId = :playlistId
        ORDER BY ref.addedAt ASC
    """)
    fun getTracksForPlaylist(playlistId: Long): Flow<List<Track>>
}

@Dao
interface StatDao {
    @Query("SELECT * FROM listening_stats ORDER BY timestamp DESC")
    fun getAllStats(): Flow<List<ListeningStat>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStat(stat: ListeningStat)

    @Query("SELECT SUM(durationPlayedMs) FROM listening_stats")
    fun getTotalListeningTimeMs(): Flow<Long?>

    @Query("SELECT COUNT(*) FROM listening_stats")
    fun getTotalPlayCount(): Flow<Int>
}

@Dao
interface DownloadDao {
    @Query("SELECT * FROM download_entries ORDER BY createdAt DESC")
    fun getAllDownloads(): Flow<List<DownloadEntry>>

    @Query("SELECT * FROM download_entries WHERE id = :id LIMIT 1")
    suspend fun getDownloadById(id: String): DownloadEntry?

    @Query("SELECT * FROM download_entries WHERE status = :status ORDER BY createdAt ASC")
    fun getDownloadsByStatus(status: DownloadStatus): Flow<List<DownloadEntry>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDownload(download: DownloadEntry)

    @Update
    suspend fun updateDownload(download: DownloadEntry)

    @Query("UPDATE download_entries SET progress = :progress, bytesDownloaded = :bytes, status = :status WHERE id = :id")
    suspend fun updateProgress(id: String, progress: Float, bytes: Long, status: DownloadStatus)

    @Query("UPDATE download_entries SET status = :status, filePath = :path, completedAt = :completedAt WHERE id = :id")
    suspend fun markCompleted(id: String, path: String, status: DownloadStatus, completedAt: Long)

    @Query("UPDATE download_entries SET status = :status, errorMessage = :error WHERE id = :id")
    suspend fun markFailed(id: String, error: String, status: DownloadStatus)

    @Delete
    suspend fun deleteDownload(download: DownloadEntry)

    @Query("DELETE FROM download_entries WHERE id = :id")
    suspend fun deleteDownloadById(id: String)
}

@Dao
interface QueueDao {
    @Query("SELECT * FROM queue_items ORDER BY orderIndex ASC")
    suspend fun getQueueItems(): List<QueueItem>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertQueueItems(items: List<QueueItem>)

    @Query("DELETE FROM queue_items")
    suspend fun clearQueueItems()

    @Query("SELECT * FROM queue_state WHERE id = 1 LIMIT 1")
    suspend fun getQueueState(): QueueState?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveQueueState(state: QueueState)

    @Query("DELETE FROM queue_state")
    suspend fun clearQueueState()
}
