package com.example.flowave.downloader

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

class SealStyleDownloadEngine(private val envManager: FloWaveNativeEnvManager) {

    fun executeDownload(url: String, outputDir: File): Flow<DownloadState> = flow {
        emit(DownloadState.Initializing)
        if (!outputDir.exists()) outputDir.mkdirs()

        val ytDlp = envManager.ytDlpExecutable
        if (!ytDlp.exists()) {
            val fetched = envManager.fetchYtDlpBinaryFromGitHub()
            if (!fetched) {
                emit(DownloadState.Error("yt-dlp binary not available. Please check your network connection."))
                return@flow
            }
        }
        ytDlp.setExecutable(true, false)

        val outputTemplate = "${outputDir.absolutePath}/%(title)s.%(ext)s"
        val isKeywordSearch = !url.startsWith("http")
        val target = if (isKeywordSearch) "ytsearch1:$url" else url

        val cmd = mutableListOf(
            ytDlp.absolutePath,
            target,
            "-x",
            "--audio-format", "m4a",
            "--audio-quality", "0",
            "-o", outputTemplate,
            "--no-playlist",
            "--newline",
            "--no-warnings"
        )

        val process = try {
            ProcessBuilder(cmd)
                .directory(outputDir)
                .redirectErrorStream(true)
                .start()
        } catch (e: Exception) {
            emit(DownloadState.Error("Failed to start yt-dlp: ${e.message}"))
            return@flow
        }

        var outputFilePath = ""
        val progressRegex = Regex("""\[download\]\s+([\d.]+)%\s+of\s+\S+\s+at\s+(\S+)\s+ETA\s+(\S+)""")
        val destinationRegex = Regex("""\[(?:ExtractAudio|download)\] Destination:\s*(.+)""")
        val mergeRegex = Regex("""\[Merger\] Merging formats into "(.+)"""")

        val reader = BufferedReader(InputStreamReader(process.inputStream))
        var line: String?
        while (reader.readLine().also { line = it } != null) {
            val l = line ?: continue
            progressRegex.find(l)?.let { m ->
                emit(DownloadState.Downloading(
                    progress = m.groupValues[1].toFloatOrNull() ?: 0f,
                    speed = m.groupValues[2],
                    eta = m.groupValues[3]
                ))
            }
            destinationRegex.find(l)?.let { m -> outputFilePath = m.groupValues[1].trim() }
            mergeRegex.find(l)?.let { m -> outputFilePath = m.groupValues[1].trim() }
            if (l.contains("[ExtractAudio]") || l.contains("Deleting original file")) {
                emit(DownloadState.PostProcessing("Converting audio..."))
            }
        }

        val exitCode = process.waitFor()
        if (exitCode == 0) {
            emit(DownloadState.Success(outputFilePath))
        } else {
            emit(DownloadState.Error("Download failed (exit code $exitCode). Track may be age-restricted or unavailable."))
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Search by keyword using yt-dlp's built-in ytsearch prefix.
     * Returns a list of Pair(title, videoUrl).
     */
    suspend fun searchByKeyword(keyword: String, maxResults: Int = 5): List<Pair<String, String>> =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            val ytDlp = envManager.ytDlpExecutable
            if (!ytDlp.exists() || ytDlp.length() == 0L) return@withContext emptyList()

            val cmd = listOf(
                ytDlp.absolutePath,
                "ytsearch${maxResults}:$keyword",
                "--print", "%(id)s\t%(title)s",
                "--no-playlist",
                "--flat-playlist",
                "--no-warnings"
            )
            try {
                val process = ProcessBuilder(cmd)
                    .redirectErrorStream(false)
                    .start()

                val lines = process.inputStream.bufferedReader().readLines()
                process.waitFor()

                lines.mapNotNull { line ->
                    val tabIdx = line.indexOf('\t')
                    if (tabIdx > 0) {
                        val id = line.substring(0, tabIdx).trim()
                        val title = line.substring(tabIdx + 1).trim()
                        if (id.length == 11 && title.isNotBlank()) {
                            Pair(title, "https://www.youtube.com/watch?v=$id")
                        } else null
                    } else null
                }
            } catch (e: Exception) {
                android.util.Log.e("SealStyleDownloadEngine", "searchByKeyword failed: ${e.message}")
                emptyList()
            }
        }
}
