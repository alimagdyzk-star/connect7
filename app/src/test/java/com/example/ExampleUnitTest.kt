package com.example

import com.example.core.security.PathValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class ExampleUnitTest {

    @Test
    fun `validateRemotePath normalizes clean nested paths`() {
        val res = PathValidator.validateRemotePath("/Documents//Architecture/")
        assertTrue(res is PathValidator.ValidationResult.Valid)
        assertEquals("/Documents/Architecture", (res as PathValidator.ValidationResult.Valid).normalizedRelativePath)
    }

    @Test
    fun `validateRemotePath rejects dot-dot and encoded traversal attacks`() {
        val rawTraversal = PathValidator.validateRemotePath("/Documents/../../etc/passwd")
        assertTrue(rawTraversal is PathValidator.ValidationResult.Denied)

        val encodedTraversal = PathValidator.validateRemotePath("/Documents/%2e%2e/%2e%2e/data")
        assertTrue(encodedTraversal is PathValidator.ValidationResult.Denied)

        val nullByte = PathValidator.validateRemotePath("/Documents/report.pdf\u0000.txt")
        assertTrue(nullByte is PathValidator.ValidationResult.Denied)
    }

    @Test
    fun `resolveWithinRoot confines files strictly inside canonical root`() {
        val tempRoot: File = createTempDirectory("apex_vfs_test").toFile()
        try {
            val subDir = File(tempRoot, "Downloads").apply { mkdirs() }
            val safeFile = File(subDir, "notes.txt").apply { writeText("ok") }

            val ok = PathValidator.resolveWithinRoot(tempRoot, "/Downloads/notes.txt")
            assertTrue(ok is PathValidator.ValidationResult.Valid)
            assertEquals(safeFile.canonicalPath, (ok as PathValidator.ValidationResult.Valid).resolvedFile?.canonicalPath)

            val escape = PathValidator.resolveWithinRoot(tempRoot, "../outside_secret.txt")
            assertTrue(escape is PathValidator.ValidationResult.Denied)
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun `sanitizeFileName strips path separators and traversal tokens`() {
        val sanitized = PathValidator.sanitizeFileName("../../secret/key.pem")
        assertTrue(!sanitized.contains("/"))
        assertTrue(!sanitized.contains(".."))
    }
}
