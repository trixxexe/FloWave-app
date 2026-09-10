package com.example.flowave.diagnostics

import java.util.Locale

enum class DiagnosticLevel {
    DEBUG, INFO, WARN, ERROR
}

data class DiagnosticEntry(
    val id: String,
    val timestampMs: Long,
    val level: DiagnosticLevel,
    val category: String,
    val event: String,
    val message: String,
    val context: String = "",
    val throwable: String? = null,
    val protected: Boolean = false
)

object DiagnosticLogCodec {
    private const val FIELD_SEPARATOR = '|'

    fun encode(entry: DiagnosticEntry): String = listOf(
        entry.id,
        entry.timestampMs.toString(),
        entry.level.name,
        entry.category,
        entry.event,
        entry.message,
        entry.context,
        entry.throwable.orEmpty(),
        if (entry.protected) "1" else "0"
    ).joinToString(FIELD_SEPARATOR.toString(), transform = ::escape)

    fun decode(line: String): DiagnosticEntry? {
        val fields = splitEscaped(line)
        if (fields.size < 8) return null
        return runCatching {
            DiagnosticEntry(
                id = fields[0],
                timestampMs = fields[1].toLong(),
                level = DiagnosticLevel.valueOf(fields[2].uppercase(Locale.US)),
                category = fields[3],
                event = fields[4],
                message = fields[5],
                context = fields[6],
                throwable = fields[7].takeIf { it.isNotEmpty() },
                protected = fields.getOrNull(8) == "1"
            )
        }.getOrNull()
    }

    fun humanReadable(entries: List<DiagnosticEntry>): String = buildString {
        appendLine("FloWave diagnostic log")
        appendLine("Entries: ${entries.size}")
        appendLine("Generated: ${System.currentTimeMillis()}")
        appendLine("=".repeat(96))
        entries.forEach { entry ->
            appendLine("${entry.timestampMs} [${entry.level}] ${entry.category}/${entry.event}")
            if (entry.context.isNotBlank()) appendLine("Context: ${entry.context}")
            appendLine(entry.message)
            entry.throwable?.let { appendLine(it) }
            appendLine("-".repeat(96))
        }
    }

    private fun escape(value: String): String = value
        .replace("\\", "\\\\")
        .replace("|", "\\p")
        .replace("\n", "\\n")
        .replace("\r", "\\r")

    private fun splitEscaped(line: String): List<String> {
        val output = mutableListOf<String>()
        val current = StringBuilder()
        var escaped = false
        line.forEach { char ->
            if (escaped) {
                current.append(
                    when (char) {
                        'n' -> '\n'
                        'r' -> '\r'
                        'p' -> '|'
                        '\\' -> '\\'
                        else -> char
                    }
                )
                escaped = false
            } else if (char == '\\') {
                escaped = true
            } else if (char == FIELD_SEPARATOR) {
                output += current.toString()
                current.clear()
            } else {
                current.append(char)
            }
        }
        if (escaped) current.append('\\')
        output += current.toString()
        return output
    }
}

object DiagnosticSanitizer {
    private val secretPattern = Regex(
        "(?i)(authorization|cookie|token|access_token|refresh_token|api[_-]?key|password|secret)=([^&\\s]+)"
    )
    private val urlPattern = Regex("https?://[^\\s]+")

    fun text(value: String?): String {
        if (value.isNullOrBlank()) return ""
        return value
            .replace(secretPattern) { "${it.groupValues[1]}=<redacted>" }
            .replace(urlPattern) { match ->
                val raw = match.value
                val end = raw.indexOfAny(charArrayOf('?', '&')).let { if (it < 0) raw.length else it }
                raw.substring(0, end) + if (end < raw.length) "?<redacted>" else ""
            }
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(4000)
    }

    fun throwable(throwable: Throwable?): String? {
        if (throwable == null) return null
        val writer = java.io.StringWriter()
        throwable.printStackTrace(java.io.PrintWriter(writer))
        return text(writer.toString()).take(12000)
    }
}

object DiagnosticRetention {
    const val DEFAULT_RETENTION_MS = 7 * 24 * 60 * 60 * 1000L

    fun cleanup(active: List<DiagnosticEntry>, nowMs: Long, retentionMs: Long = DEFAULT_RETENTION_MS): List<DiagnosticEntry> =
        active.filter { it.timestampMs >= nowMs - retentionMs }
}
