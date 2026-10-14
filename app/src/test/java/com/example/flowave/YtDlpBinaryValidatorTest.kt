package com.example.flowave

import com.example.flowave.downloader.YtDlpBinaryValidator
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File

class YtDlpBinaryValidatorTest {

    @Test
    fun testDummyPythonStubRejected() {
        val dummyScript = """
            #!/usr/bin/env python3
            # FloWave Native yt-dlp executable wrapper asset
            import sys
            sys.exit(0)
        """.trimIndent().toByteArray(Charsets.UTF_8)

        val stream = ByteArrayInputStream(dummyScript)
        val valid = YtDlpBinaryValidator.isElfBinaryStream(stream)
        assertFalse("Small text script should be rejected as non-ELF", valid)
    }

    @Test
    fun testElfMagicHeaderTooSmallRejected() {
        // 4-byte ELF magic header + 20 bytes payload = 24 bytes total (well below 100KB)
        val smallElf = byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()) + ByteArray(20)
        val stream = ByteArrayInputStream(smallElf)
        val valid = YtDlpBinaryValidator.isElfBinaryStream(stream)
        assertFalse("ELF binary under 100KB threshold must be rejected", valid)
    }

    @Test
    fun testLargeNonElfBinaryRejected() {
        // 120KB of zero bytes (missing ELF magic header)
        val nonElf = ByteArray(120 * 1024)
        val stream = ByteArrayInputStream(nonElf)
        val valid = YtDlpBinaryValidator.isElfBinaryStream(stream)
        assertFalse("Large file without ELF magic header must be rejected", valid)
    }

    @Test
    fun testValidElfBinaryStreamAccepted() {
        // ELF header + 105KB of bytes
        val validElf = byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()) + ByteArray(105 * 1024)
        val stream = ByteArrayInputStream(validElf)
        val valid = YtDlpBinaryValidator.isElfBinaryStream(stream)
        assertTrue("Genuine ELF binary >= 100KB must be accepted", valid)
    }

    @Test
    fun testDiskFileValidation() {
        val tempFile = File.createTempFile("test_ytdlp", ".bin")
        try {
            // Write stub
            tempFile.writeText("#!/usr/bin/env python3")
            assertFalse("Disk stub file must be invalid", YtDlpBinaryValidator.isBinaryValid(tempFile))

            // Write valid binary
            val validElf = byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()) + ByteArray(101 * 1024)
            tempFile.writeBytes(validElf)
            assertTrue("Disk valid ELF file must be valid", YtDlpBinaryValidator.isBinaryValid(tempFile))
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun testNullContextHandling() {
        assertFalse("Null context asset check should safely return false", YtDlpBinaryValidator.isAssetValid(null))
        assertFalse("Null context runtime check should safely return false", YtDlpBinaryValidator.isRuntimeAvailable(null))
    }
}
