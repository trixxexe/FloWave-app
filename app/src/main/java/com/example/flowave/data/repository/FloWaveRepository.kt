package com.example.flowave.data.repository

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import com.example.flowave.data.local.AppDatabase
import com.example.flowave.data.model.ListeningStat
import com.example.flowave.data.model.Playlist
import com.example.flowave.data.model.PlaylistTrackCrossRef
import com.example.flowave.data.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File

class FloWaveRepository(private val context: Context) {
    private val db = AppDatabase.getDatabase(context)
    private val trackDao = db.trackDao()
    private val playlistDao = db.playlistDao()
    private val statDao = db.statDao()

    val allTracks: Flow<List<Track>> = trackDao.getAllTracks()
    val favoriteTracks: Flow<List<Track>> = trackDao.getFavoriteTracks()
    val downloadedTracks: Flow<List<Track>> = trackDao.getDownloadedTracks()
    val mostPlayedTracks: Flow<List<Track>> = trackDao.getMostPlayedTracks()
    val recentlyPlayedTracks: Flow<List<Track>> = trackDao.getRecentlyPlayedTracks()
    val recentlyAddedTracks: Flow<List<Track>> = trackDao.getRecentlyAddedTracks()
    val allPlaylists: Flow<List<Playlist>> = playlistDao.getAllPlaylists()
    val totalListeningTimeMs: Flow<Long?> = statDao.getTotalListeningTimeMs()
    val totalPlayCount: Flow<Int> = statDao.getTotalPlayCount()

    suspend fun deleteTracks(ids: List<String>) = withContext(Dispatchers.IO) {
        trackDao.deleteTracksByIds(ids)
    }

    fun findDuplicates(allTracks: List<Track>): List<Track> {
        val seen = mutableSetOf<String>()
        val duplicates = mutableListOf<Track>()
        for (track in allTracks) {
            val key = "${track.title.lowercase().trim()}_${track.artist.lowercase().trim()}"
            if (seen.contains(key)) {
                duplicates.add(track)
            } else {
                seen.add(key)
            }
        }
        return duplicates
    }

    suspend fun insertTrack(track: Track) = withContext(Dispatchers.IO) {
        trackDao.insertTrack(track)
    }
    suspend fun updateTrack(track: Track) = withContext(Dispatchers.IO) {
        trackDao.updateTrack(track)
    }
    suspend fun toggleFavorite(track: Track) = withContext(Dispatchers.IO) {
        val updated = track.copy(isFavorite = !track.isFavorite)
        trackDao.updateTrack(updated)
    }

    suspend fun recordPlay(track: Track, durationMs: Long) = withContext(Dispatchers.IO) {
        val currentTrack = trackDao.getTrackById(track.id)
        if (currentTrack != null) {
            val updated = currentTrack.copy(
                playCount = currentTrack.playCount + 1,
                lastPlayedTimestamp = System.currentTimeMillis()
            )
            trackDao.updateTrack(updated)
        }
        statDao.insertStat(
            ListeningStat(
                trackId = track.id,
                trackTitle = track.title,
                artistName = track.artist,
                durationPlayedMs = durationMs
            )
        )
    }

    suspend fun createPlaylist(name: String, description: String = ""): Long = withContext(Dispatchers.IO) {
        playlistDao.insertPlaylist(Playlist(name = name, description = description))
    }

    suspend fun addTrackToPlaylist(playlistId: Long, trackId: String) = withContext(Dispatchers.IO) {
        playlistDao.addTrackToPlaylist(PlaylistTrackCrossRef(playlistId = playlistId, trackId = trackId))
    }

    fun getTracksForPlaylist(playlistId: Long): Flow<List<Track>> = playlistDao.getTracksForPlaylist(playlistId)

    suspend fun scanMediaStore(): List<Track> = withContext(Dispatchers.IO) {
        val localList = mutableListOf<Track>()
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DATA
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        
        try {
            context.contentResolver.query(
                collection,
                projection,
                selection,
                null,
                "${MediaStore.Audio.Media.TITLE} ASC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    var title = cursor.getString(titleCol) ?: "Unknown Track"
                    var artist = cursor.getString(artistCol) ?: "Unknown Artist"
                    var album = cursor.getString(albumCol) ?: "Unknown Album"
                    var duration = cursor.getLong(durationCol)
                    val albumId = cursor.getLong(albumIdCol)
                    val filePath = cursor.getString(dataCol) ?: ""

                    // Robust local metadata extraction: Prefer actual tags via MediaMetadataRetriever
                    // if standard MediaStore returns "<unknown>" or empty.
                    val file = java.io.File(filePath)
                    if (file.exists() && file.isFile) {
                        val retriever = android.media.MediaMetadataRetriever()
                        try {
                            retriever.setDataSource(filePath)
                            val metaTitle = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE)
                            val metaArtist = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST)
                            val metaAlbum = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ALBUM)
                            val metaDurationStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                            val metaDuration = metaDurationStr?.toLongOrNull()

                            if (!metaTitle.isNullOrBlank() && !metaTitle.trim().equals("<unknown>", ignoreCase = true)) {
                                title = metaTitle
                            }
                            if (!metaArtist.isNullOrBlank() && !metaArtist.trim().equals("<unknown>", ignoreCase = true)) {
                                artist = metaArtist
                            }
                            if (!metaAlbum.isNullOrBlank() && !metaAlbum.trim().equals("<unknown>", ignoreCase = true)) {
                                album = metaAlbum
                            }
                            if (metaDuration != null && metaDuration > 0L) {
                                duration = metaDuration
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("FloWaveRepository", "MediaMetadataRetriever extraction failed: ${e.message}")
                        } finally {
                            try {
                                retriever.release()
                            } catch (e: Exception) {
                                // Ignored
                            }
                        }
                    }

                    // Ultimate fallback to filename if still unknown or blank
                    if (title.isBlank() || title.trim().equals("<unknown>", ignoreCase = true)) {
                        title = file.nameWithoutExtension.ifBlank { "Track $id" }
                    }
                    if (artist.isBlank() || artist.trim().equals("<unknown>", ignoreCase = true)) {
                        artist = "Unknown Artist"
                    }
                    if (album.isBlank() || album.trim().equals("<unknown>", ignoreCase = true)) {
                        album = "Unknown Album"
                    }

                    val contentUri = ContentUris.withAppendedId(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        id
                    ).toString()

                    val albumArtUri = ContentUris.withAppendedId(
                        android.net.Uri.parse("content://media/external/audio/albumart"),
                        albumId
                    ).toString()

                    val track = Track(
                        id = "local_$id",
                        title = title,
                        artist = artist,
                        album = album,
                        durationMs = duration,
                        mediaUri = contentUri,
                        artworkUri = albumArtUri,
                        isOnline = false,
                        source = "LOCAL",
                        folderPath = file.parent
                    )
                    localList.add(track)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("FloWaveRepository", "Error scanning local MediaStore tracks", e)
        }

        if (localList.isNotEmpty()) {
            trackDao.insertTracks(localList)
        }
        localList
    }
}
