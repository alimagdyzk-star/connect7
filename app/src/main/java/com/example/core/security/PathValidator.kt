package com.example.core.security

import java.io.File
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Security validator enforcing strict path traversal prevention and authorized root isolation
 * for Remote File Browsing (`kdeconnect.vfs.*`) and attachment storage.
 *
 * Inspired by KDE Connect SFTP export boundaries and FileApex default-deny peer APIs.
 */
object PathValidator {

    sealed class ValidationResult {
        data class Valid(val normalizedRelativePath: String, val resolvedFile: File? = null) : ValidationResult()
        data class Denied(val reason: String) : ValidationResult()
    }

    /**
     * Validates a virtual remote path string (e.g., `/Documents/Work`) before sending or serving
     * directory listings or file read/write operations.
     */
    fun validateRemotePath(rawPath: String): ValidationResult {
        if (rawPath.isBlank()) {
            return ValidationResult.Valid("/")
        }
        if (rawPath.contains('\u0000')) {
            return ValidationResult.Denied("Null byte injection detected in remote path.")
        }

        val decoded = try {
            URLDecoder.decode(rawPath, StandardCharsets.UTF_8.name())
        } catch (_: IllegalArgumentException) {
            rawPath
        }

        if (decoded.contains('\u0000')) {
            return ValidationResult.Denied("Encoded null byte detected in remote path.")
        }

        val normalizedSlashes = decoded.replace('\\', '/')
        val segments = normalizedSlashes.split('/').filter { it.isNotEmpty() }

        for (segment in segments) {
            if (segment == "." || segment == "..") {
                return ValidationResult.Denied("Directory traversal segment ('$segment') is prohibited.")
            }
            if (segment.contains("..")) {
                return ValidationResult.Denied("Suspicious traversal sequence in segment '$segment'.")
            }
        }

        val cleanPath = if (segments.isEmpty()) "/" else "/" + segments.joinToString("/")
        return ValidationResult.Valid(cleanPath)
    }

    /**
     * Resolves a remote path strictly inside an authorized local root directory using canonical path
     * comparison so symlinks or relative tricks cannot escape `authorizedRoot`.
     */
    fun resolveWithinRoot(authorizedRoot: File, requestedPath: String): ValidationResult {
        val pathCheck = validateRemotePath(requestedPath)
        if (pathCheck is ValidationResult.Denied) {
            return pathCheck
        }
        val cleanRelative = (pathCheck as ValidationResult.Valid).normalizedRelativePath.removePrefix("/")

        return try {
            val rootCanonical = authorizedRoot.canonicalFile
            val candidate = if (cleanRelative.isEmpty()) {
                rootCanonical
            } else {
                File(rootCanonical, cleanRelative).canonicalFile
            }

            val rootPrefix = rootCanonical.path + File.separator
            if (candidate.path != rootCanonical.path && !candidate.path.startsWith(rootPrefix)) {
                ValidationResult.Denied("Resolved path escapes authorized export root.")
            } else {
                ValidationResult.Valid(
                    normalizedRelativePath = if (cleanRelative.isEmpty()) "/" else "/$cleanRelative",
                    resolvedFile = candidate
                )
            }
        } catch (e: Exception) {
            ValidationResult.Denied("Canonical path resolution failed: ${e.message}")
        }
    }

    /**
     * Sanitizes a single filename for safe local file storage without directory separators.
     */
    fun sanitizeFileName(rawName: String): String {
        val trimmed = rawName.trim()
            .replace('/', '_')
            .replace('\\', '_')
            .replace('\u0000', '_')
            .replace("..", "_")
        return trimmed.ifBlank { "transfer_${System.currentTimeMillis()}.bin" }
    }
}
