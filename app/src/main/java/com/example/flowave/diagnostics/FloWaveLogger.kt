package com.example.flowave.diagnostics

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** Best-effort structured diagnostics backed by a single serialized writer. */
class FloWaveLogger private constructor(context: Context) {
    private val directory = File(context.applicationContext.filesDir, "diagnostics")
    private val activeFile = File(directory, "events.log")
    private val savedFile = File(directory, "saved.log")
    private val writer = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "flowave-diagnostics").apply { isDaemon = true }
    }
    private val sequence = AtomicLong(0)
    private val lastEvents = ConcurrentHashMap<String, Long>()
    private val _entries = MutableStateFlow<List<DiagnosticEntry>>(emptyList())
    val entries: StateFlow<List<DiagnosticEntry>> = _entries

    init {
        writer.execute {
            runCatching {
                directory.mkdirs()
                refreshState()
                cleanupInternal(System.currentTimeMillis())
            }
        }
    }

    fun debug(category: String, event: String, message: String = "", context: Map<String, Any?> = emptyMap()) =
        write(DiagnosticLevel.DEBUG, category, event, message, context, null)

    fun info(category: String, event: String, message: String = "", context: Map<String, Any?> = emptyMap()) =
        write(DiagnosticLevel.INFO, category, event, message, context, null)

    fun warn(category: String, event: String, message: String = "", context: Map<String, Any?> = emptyMap(), throwable: Throwable? = null) =
        write(DiagnosticLevel.WARN, category, event, message, context, throwable)

    fun error(category: String, event: String, message: String = "", context: Map<String, Any?> = emptyMap(), throwable: Throwable? = null) =
        write(DiagnosticLevel.ERROR, category, event, message, context, throwable)

    fun recordException(category: String, event: String, throwable: Throwable, context: Map<String, Any?> = emptyMap()) =
        error(category, event, throwable.message.orEmpty(), context, throwable)

    fun protect(id: String, value: Boolean) {
        writer.execute {
            runCatching {
                val source = if (value) activeFile else savedFile
                val target = if (value) savedFile else activeFile
                val sourceEntries = read(source).map { if (it.id == id) it.copy(protected = value) else it }
                val selected = sourceEntries.firstOrNull { it.id == id } ?: return@runCatching
                writeAll(target, (read(target) + selected).distinctBy { it.id })
                writeAll(source, sourceEntries.filterNot { it.id == id })
                refreshState()
            }
        }
    }

    fun clearUnsaved() {
        writer.execute { runCatching { activeFile.delete(); refreshState() } }
    }

    fun clearAll() {
        writer.execute { runCatching { activeFile.delete(); savedFile.delete(); refreshState() } }
    }

    suspend fun exportText(): String = withContext(Dispatchers.IO) {
        runCatching {
            DiagnosticLogCodec.humanReadable((read(activeFile) + read(savedFile)).sortedBy { it.timestampMs })
        }.getOrDefault("FloWave diagnostics unavailable.")
    }

    private fun write(
        level: DiagnosticLevel,
        category: String,
        event: String,
        message: String,
        context: Map<String, Any?>,
        throwable: Throwable?
    ) {
        val safeCategory = DiagnosticSanitizer.text(category).ifBlank { "app" }
        val safeEvent = DiagnosticSanitizer.text(event).ifBlank { "event" }
        val safeMessage = DiagnosticSanitizer.text(message)
        val key = "$safeCategory|$safeEvent|$safeMessage"
        val now = System.currentTimeMillis()
        if (level == DiagnosticLevel.DEBUG && now - (lastEvents[key] ?: 0L) < THROTTLE_MS) return
        lastEvents[key] = now
        val entry = DiagnosticEntry(
            id = "$now-${sequence.incrementAndGet()}",
            timestampMs = now,
            level = level,
            category = safeCategory,
            event = safeEvent,
            message = safeMessage,
            context = context.entries.joinToString(",") { "${DiagnosticSanitizer.text(it.key)}=${DiagnosticSanitizer.text(it.value?.toString())}" },
            throwable = DiagnosticSanitizer.throwable(throwable)
        )
        writer.execute {
            runCatching {
                directory.mkdirs()
                activeFile.appendText(DiagnosticLogCodec.encode(entry) + "\n")
                trimActive()
                refreshState()
            }.onFailure { Log.w(TAG, "Diagnostics write unavailable", it) }
        }
        runCatching { Log.println(androidLevel(level), "FloWave/$safeCategory", safeMessage) }
    }

    private fun cleanupInternal(now: Long) {
        writeAll(activeFile, DiagnosticRetention.cleanup(read(activeFile), now, RETENTION_MS))
        trimActive()
        refreshState()
    }

    private fun trimActive() {
        if (!activeFile.exists() || activeFile.length() <= MAX_ACTIVE_BYTES) return
        var entries = read(activeFile)
        while (entries.size > 1 && DiagnosticLogCodec.humanReadable(entries).length > MAX_ACTIVE_BYTES) {
            entries = entries.drop(1)
        }
        writeAll(activeFile, entries)
    }

    private fun refreshState() {
        _entries.value = (read(activeFile) + read(savedFile)).sortedBy { it.timestampMs }.takeLast(MAX_MEMORY_ENTRIES)
    }

    private fun read(file: File): List<DiagnosticEntry> =
        runCatching { if (!file.exists()) emptyList() else file.readLines().mapNotNull(DiagnosticLogCodec::decode) }
            .getOrDefault(emptyList())

    private fun writeAll(file: File, entries: List<DiagnosticEntry>) {
        file.parentFile?.mkdirs()
        file.writeText(entries.joinToString("\n", postfix = if (entries.isNotEmpty()) "\n" else "", transform = DiagnosticLogCodec::encode))
    }

    private fun androidLevel(level: DiagnosticLevel): Int = when (level) {
        DiagnosticLevel.DEBUG -> Log.DEBUG
        DiagnosticLevel.INFO -> Log.INFO
        DiagnosticLevel.WARN -> Log.WARN
        DiagnosticLevel.ERROR -> Log.ERROR
    }

    companion object {
        private const val TAG = "FloWaveDiagnostics"
        private const val MAX_ACTIVE_BYTES = 512 * 1024L
        private const val MAX_MEMORY_ENTRIES = 300
        private const val RETENTION_MS = 7 * 24 * 60 * 60 * 1000L
        private const val THROTTLE_MS = 5_000L
        @Volatile private var instance: FloWaveLogger? = null

        fun getInstance(context: Context): FloWaveLogger =
            instance ?: synchronized(this) {
                instance ?: FloWaveLogger(context.applicationContext).also { instance = it }
            }
    }
}
