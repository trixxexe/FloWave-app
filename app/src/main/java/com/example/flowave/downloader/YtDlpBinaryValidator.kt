package com.example.flowave.downloader

import android.content.Context
import com.example.flowave.FloWaveRuntime
import java.io.File
import java.io.InputStream

/**
 * Validates whether the yt-dlp asset or runtime executable binary is a genuine
 * compiled ELF binary and not a stub/placeholder script.
 */
object YtDlpBinaryValidator {
    val ELF_MAGIC = byteArrayOf(0x7F.toByte(), 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte())
    const val MIN_ELF_SIZE = 100 * 1024L // 100 KB minimum for a real binary

    /**
     * Checks whether an input stream starts with the 4-byte ELF magic header
     * and contains at least [MIN_ELF_SIZE] bytes.
     */
    fun isElfBinaryStream(stream: InputStream): Boolean {
        return runCatching {
            val header = ByteArray(4)
            var bytesRead = 0
            while (bytesRead < 4) {
                val r = stream.read(header, bytesRead, 4 - bytesRead)
                if (r == -1) return@runCatching false
                bytesRead += r
            }
            if (!header.contentEquals(ELF_MAGIC)) {
                return@runCatching false
            }
            // Count remaining bytes to ensure size is >= MIN_ELF_SIZE
            var totalBytes = 4L
            val buf = ByteArray(8192)
            while (totalBytes < MIN_ELF_SIZE) {
                val r = stream.read(buf)
                if (r == -1) break
                totalBytes += r
            }
            totalBytes >= MIN_ELF_SIZE
        }.getOrDefault(false)
    }

    /**
     * Inspects the bundled asset "yt-dlp".
     * Returns false if it is missing, a stub/placeholder, or non-ELF binary.
     */
    fun isAssetValid(context: Context?): Boolean {
        if (context == null) return false
        return runCatching {
            context.assets.open("yt-dlp").use { stream ->
                isElfBinaryStream(stream)
            }
        }.getOrDefault(false)
    }

    /**
     * Inspects an extracted binary file on disk.
     */
    fun isBinaryValid(file: File?): Boolean {
        if (file == null || !file.exists() || !file.isFile || file.length() < MIN_ELF_SIZE) {
            return false
        }
        return runCatching {
            file.inputStream().use { stream ->
                isElfBinaryStream(stream)
            }
        }.getOrDefault(false)
    }

    /**
     * Determines whether yt-dlp is genuinely ready and valid to execute.
     * If yt-dlp is not ready, or is a stub/placeholder asset, returns false.
     */
    fun isRuntimeAvailable(context: Context?): Boolean {
        if (!FloWaveRuntime.ready) return false
        if (context == null) return false
        if (!isAssetValid(context)) {
            val candidatePaths = listOfNotNull(
                File(context.filesDir, "yt-dlp"),
                File(context.filesDir, "bin/yt-dlp"),
                File(context.noBackupFilesDir, "youtubedl-android/yt-dlp"),
                context.applicationInfo?.nativeLibraryDir?.let { File(it, "libyt-dlp.so") }
            )
            if (!candidatePaths.any { isBinaryValid(it) }) {
                return false
            }
        }
        return true
    }
}
