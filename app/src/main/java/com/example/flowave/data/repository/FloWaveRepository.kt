package com.example.flowave.data.repository

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import android.provider.DocumentsContract
import com.example.flowave.data.local.AppDatabase
import com.example.flowave.data.model.ListeningStat
import com.example.flowave.data.model.Playlist
import com.example.flowave.data.model.PlaylistTrackCrossRef
import com.example.flowave.data.model.Track
import com.example.flowave.diagnostics.FloWaveLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.ArrayDeque

class FloWaveRepository(private val context: Context) {
    private val logger = FloWaveLogger.getInstance(context)
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
        logger.info("library", "media_scan_started")
        val localList = mutableListOf<Track>()
        var queryCompleted = false
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.RELATIVE_PATH,
            MediaStore.Audio.Media.DISPLAY_NAME
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
                queryCompleted = true
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                val dataCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
                val relativePathCol = cursor.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)
                val displayNameCol = cursor.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    var title = cursor.getString(titleCol) ?: "Unknown Track"
                    var artist = cursor.getString(artistCol) ?: "Unknown Artist"
                    var album = cursor.getString(albumCol) ?: "Unknown Album"
                    var duration = cursor.getLong(durationCol)
                    val albumId = cursor.getLong(albumIdCol)
                    val filePath = if (dataCol >= 0) cursor.getString(dataCol) ?: "" else ""
                    val relativePath = if (relativePathCol >= 0) cursor.getString(relativePathCol) else null
                    val displayName = if (displayNameCol >= 0) cursor.getString(displayNameCol) else null
                    val contentUri = ContentUris.withAppendedId(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        id
                    )

                    // Robust local metadata extraction: Prefer actual tags via MediaMetadataRetriever
                    // if standard MediaStore returns "<unknown>" or empty.
                    val file = java.io.File(filePath)
                    if (file.exists() && file.isFile || filePath.isBlank()) {
                        val retriever = android.media.MediaMetadataRetriever()
                        try {
                            if (file.exists() && file.isFile) retriever.setDataSource(filePath)
                            else retriever.setDataSource(context, contentUri)
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
                    title = LocalTrackIdentity.cleanMetadata(
                        title,
                        LocalTrackIdentity.fallbackTitle(displayName ?: file.name, "Track $id")
                    )
                    artist = LocalTrackIdentity.cleanMetadata(artist, "Unknown Artist")
                    album = LocalTrackIdentity.cleanMetadata(album, "Unknown Album")

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
                        mediaUri = contentUri.toString(),
                        artworkUri = albumArtUri,
                        isOnline = false,
                        source = "LOCAL",
                        folderPath = relativePath?.trimEnd('/')?.takeIf { it.isNotBlank() } ?: file.parent
                    )
                    localList.add(track)
                }
            }
        } catch (e: Exception) {
            logger.error("library", "media_scan_failed", throwable = e)
            android.util.Log.e("FloWaveRepository", "Error scanning local MediaStore tracks", e)
        }

        if (queryCompleted) {
            // Refresh only the MediaStore-owned rows. Downloaded/imported rows
            // remain available offline and are never removed by a rescan.
            val existingByUri = trackDao.getLocalTracks().associateBy { it.mediaUri }
            val mergedLocalList = localList.map { fresh ->
                existingByUri[fresh.mediaUri]?.let { existing ->
                    fresh.copy(
                        playCount = existing.playCount,
                        lastPlayedTimestamp = existing.lastPlayedTimestamp,
                        isFavorite = existing.isFavorite
                    )
                } ?: fresh
            }
            trackDao.deleteLocalTracks()
            if (mergedLocalList.isNotEmpty()) {
                trackDao.insertTracks(mergedLocalList)
            }
        }
        logger.info("library", "media_scan_finished", context = mapOf("count" to localList.size))
        localList
    }

    /** Imports an audio document without copying it; the persisted SAF URI is the playback source. */
    suspend fun importAudioUri(uri: android.net.Uri, importedFolder: String? = null): Track? = withContext(Dispatchers.IO) {
        try {
            logger.info("import", "file_import_started", context = mapOf("source" to "saf_file"))
            persistReadPermission(uri)

            val resolver = context.contentResolver
            val displayName = resolver.query(
                uri,
                arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
            val mimeType = resolver.getType(uri)
            if (mimeType != null && !mimeType.startsWith("audio/") &&
                !LocalTrackIdentity.isSupportedAudioName(displayName.orEmpty())
            ) {
                return@withContext null
            }
            val retriever = android.media.MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                val fallbackTitle = LocalTrackIdentity.fallbackTitle(displayName)
                val title = LocalTrackIdentity.cleanMetadata(
                    retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE), fallbackTitle
                )
                val artist = LocalTrackIdentity.cleanMetadata(
                    retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST), "Unknown Artist"
                )
                val album = LocalTrackIdentity.cleanMetadata(
                    retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ALBUM), "Unknown Album"
                )
                val duration = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L
                val artworkUri = retriever.embeddedPicture?.let { bytes ->
                    val artwork = File(context.cacheDir, "artwork_${uri.toString().hashCode()}.jpg")
                    artwork.writeBytes(bytes)
                    android.net.Uri.fromFile(artwork).toString()
                }
                val stableId = LocalTrackIdentity.stableId(uri.toString())
                val track = Track(
                    id = stableId,
                    title = title,
                    artist = artist,
                    album = album,
                    durationMs = duration,
                    mediaUri = uri.toString(),
                    artworkUri = artworkUri,
                    source = "IMPORTED",
                    folderPath = importedFolder ?: "Imported audio"
                )
                val previous = trackDao.getTrackById(stableId)
                val merged = previous?.let {
                    track.copy(
                        playCount = it.playCount,
                        lastPlayedTimestamp = it.lastPlayedTimestamp,
                        isFavorite = it.isFavorite
                    )
                } ?: track
                trackDao.insertTrack(merged)
                merged
            } finally {
                retriever.release()
            }
        } catch (e: Exception) {
            logger.warn("import", "file_import_failed", context = mapOf("source" to "saf_file"), throwable = e)
            android.util.Log.w("FloWaveRepository", "Could not import audio URI $uri", e)
            null
        }
    }

    suspend fun importAudioUris(uris: List<android.net.Uri>): List<Track> = withContext(Dispatchers.IO) {
        uris.distinctBy { it.toString() }.mapNotNull { importAudioUri(it) }
    }

    /** Imports all supported audio descendants of a persisted SAF tree URI. */
    suspend fun importAudioTree(treeUri: android.net.Uri): List<Track> = withContext(Dispatchers.IO) {
        logger.info("import", "folder_import_started", context = mapOf("source" to "saf_tree"))
        persistReadPermission(treeUri)
        val resolver = context.contentResolver
        val pending = ArrayDeque<Pair<android.net.Uri, String>>()
        val files = mutableListOf<Pair<android.net.Uri, String>>()
        pending.add(treeUri to "Imported audio")
        while (pending.isNotEmpty()) {
            val (parent, folderPath) = pending.removeFirst()
            val parentId = runCatching { DocumentsContract.getDocumentId(parent) }.getOrNull() ?: continue
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(parent, parentId)
            runCatching {
                resolver.query(
                    childrenUri,
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE
                    ),
                    null, null, null
                )?.use { cursor ->
                    val idIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    val nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    val mimeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    while (cursor.moveToNext()) {
                        val id = cursor.getString(idIndex) ?: continue
                        val name = cursor.getString(nameIndex).orEmpty()
                        val mime = cursor.getString(mimeIndex).orEmpty()
                        val child = DocumentsContract.buildDocumentUriUsingTree(parent, id)
                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                            pending.add(child to (folderPath + "/" + name.ifBlank { "Folder" }))
                        } else if (mime.startsWith("audio/") || LocalTrackIdentity.isSupportedAudioName(name)) {
                            files.add(child to folderPath)
                        }
                    }
                }
            }.onFailure { android.util.Log.w("FloWaveRepository", "Could not enumerate SAF directory", it) }
        }
        files.distinctBy { it.first.toString() }.mapNotNull { (uri, folder) -> importAudioUri(uri, folder) }
            .also { logger.info("import", "folder_import_finished", context = mapOf("count" to it.size)) }
    }

    private fun persistReadPermission(uri: android.net.Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
    }

    suspend fun removeUnavailableImportedTracks(): Int = withContext(Dispatchers.IO) {
        val removed = trackDao.getImportedTracks().filterNot { track ->
            runCatching {
                context.contentResolver.openAssetFileDescriptor(android.net.Uri.parse(track.mediaUri), "r")?.use { true }
                    ?: false
            }.getOrDefault(false)
        }
        removed.forEach { trackDao.deleteTrack(it) }
        if (removed.isNotEmpty()) logger.warn("library", "stale_imports_removed", context = mapOf("count" to removed.size))
        removed.size
    }
}
