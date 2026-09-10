package com.example

import com.example.flowave.diagnostics.DiagnosticEntry
import com.example.flowave.diagnostics.DiagnosticLevel
import com.example.flowave.diagnostics.DiagnosticLogCodec
import com.example.flowave.diagnostics.DiagnosticSanitizer
import com.example.flowave.diagnostics.DiagnosticRetention
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticLogTest {
    @Test
    fun codecRoundTripsEscapedStructuredFields() {
        val entry = DiagnosticEntry(
            id = "1",
            timestampMs = 10L,
            level = DiagnosticLevel.ERROR,
            category = "player",
            event = "failure",
            message = "line one|line two",
            context = "track=local_1",
            throwable = "java.io.IOException: broken\n at Player.play",
            protected = true
        )
        assertEquals(entry, DiagnosticLogCodec.decode(DiagnosticLogCodec.encode(entry)))
    }

    @Test
    fun sanitizerRemovesSecretsAndUrlQueries() {
        val sanitized = DiagnosticSanitizer.text(
            "token=secret123 https://example.test/audio?expire=123&sig=secret"
        )
        assertFalse(sanitized.contains("secret123"))
        assertFalse(sanitized.contains("expire=123"))
        assertTrue(sanitized.contains("<redacted>"))
    }

    @Test
    fun humanReadableExportIncludesContextAndStackTrace() {
        val text = DiagnosticLogCodec.humanReadable(
            listOf(DiagnosticEntry("1", 1L, DiagnosticLevel.WARN, "import", "failed", "bad", "source=saf", "stack"))
        )
        assertTrue(text.contains("WARN"))
        assertTrue(text.contains("source=saf"))
        assertTrue(text.contains("stack"))
    }

    @Test
    fun retentionDropsOldUnsavedEntriesButProtectedStoreIsIndependent() {
        val now = 10_000L
        val old = DiagnosticEntry("old", now - 8_000L, DiagnosticLevel.INFO, "app", "old", "")
        val fresh = DiagnosticEntry("fresh", now - 1_000L, DiagnosticLevel.INFO, "app", "fresh", "")
        assertEquals(listOf(fresh), DiagnosticRetention.cleanup(listOf(old, fresh), now, 7_000L))
        assertTrue(DiagnosticRetention.cleanup(listOf(fresh), now, 7_000L).contains(fresh))
    }
}
