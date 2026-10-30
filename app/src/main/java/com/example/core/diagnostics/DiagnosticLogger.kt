package com.example.core.diagnostics

import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.example.core.protocol.NetworkPacket
import com.example.data.local.PairedDeviceEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

enum class LogLevel {
    INFO,
    WARN,
    ERROR
}

enum class LogCategory {
    SYSTEM,
    DISCOVERY,
    TLS,
    PAIRING,
    FILES,
    PLUGINS,
    SPACE
}

data class DiagnosticLogEntry(
    val id: Long,
    val epochMs: Long,
    val timestamp: String,
    val level: LogLevel,
    val category: LogCategory,
    val message: String,
    val details: String? = null
) {
    fun formatLine(): String = buildString {
        append("[$timestamp] [${level.name}] [${category.name}] $message")
        if (!details.isNullOrBlank()) {
            append("\n    ")
            append(details.trim().replace("\n", "\n    "))
        }
    }
}

/**
 * Persistent & real-time diagnostic logger for troubleshooting LAN discovery, X.509 TLS handshakes,
 * KDE Connect pairing, SFTP/VFS remote file browsing, and plugin interoperability on real devices.
 */
class DiagnosticLogger(
    context: Context,
    profilePrefix: String = ""
) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(
        "${profilePrefix}apexconnect_diagnostics_prefs",
        Context.MODE_PRIVATE
    )
    private val idCounter = AtomicLong(1L)
    private val fileLock = Any()

    private val diagDir: File by lazy {
        File(appContext.filesDir, "${profilePrefix}diagnostics").apply {
            if (!exists()) mkdirs()
        }
    }

    val persistentLogFile: File by lazy {
        File(diagDir, "apexconnect_debug.log")
    }

    private val _isLoggingEnabled = MutableStateFlow(prefs.getBoolean(KEY_LOGGING_ENABLED, true))
    val isLoggingEnabled: StateFlow<Boolean> = _isLoggingEnabled.asStateFlow()

    private val _isVerbosePacketsEnabled = MutableStateFlow(prefs.getBoolean(KEY_VERBOSE_PACKETS, true))
    val isVerbosePacketsEnabled: StateFlow<Boolean> = _isVerbosePacketsEnabled.asStateFlow()

    private val _entries = MutableStateFlow<List<DiagnosticLogEntry>>(emptyList())
    val entries: StateFlow<List<DiagnosticLogEntry>> = _entries.asStateFlow()

    init {
        loadTailFromDisk()
        info(
            LogCategory.SYSTEM,
            "DiagnosticLogger initialized (SDK ${Build.VERSION.SDK_INT}, ${Build.MANUFACTURER} ${Build.MODEL})"
        )
    }

    fun setLoggingEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_LOGGING_ENABLED, enabled).apply()
        _isLoggingEnabled.value = enabled
        if (enabled) {
            info(LogCategory.SYSTEM, "Diagnostic logging enabled by user")
        }
    }

    fun setVerbosePacketsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_VERBOSE_PACKETS, enabled).apply()
        _isVerbosePacketsEnabled.value = enabled
        info(LogCategory.SYSTEM, "Verbose protocol packet logging set to $enabled")
    }

    fun info(category: LogCategory, message: String, details: String? = null) {
        record(LogLevel.INFO, category, message, details)
    }

    fun warn(category: LogCategory, message: String, details: String? = null) {
        record(LogLevel.WARN, category, message, details)
    }

    fun error(category: LogCategory, message: String, throwable: Throwable? = null, details: String? = null) {
        val combined = buildString {
            if (!details.isNullOrBlank()) {
                append(details)
            }
            if (throwable != null) {
                if (isNotEmpty()) append("\n")
                append("${throwable.javaClass.name}: ${throwable.message}")
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val traceLines = sw.toString().lineSequence().take(12).joinToString("\n")
                append("\n").append(traceLines)
            }
        }.ifBlank { null }
        record(LogLevel.ERROR, category, message, combined)
    }

    fun logPacket(
        direction: String,
        peerLabel: String,
        packet: NetworkPacket,
        category: LogCategory = LogCategory.PLUGINS
    ) {
        if (!_isLoggingEnabled.value) return
        val detail = if (_isVerbosePacketsEnabled.value) {
            sanitizePacketJson(packet)
        } else {
            null
        }
        val payloadTag = if (packet.payloadSize != null) " [payload=${packet.payloadSize}B]" else ""
        record(
            level = LogLevel.INFO,
            category = category,
            message = "$direction ${packet.type}$payloadTag ($peerLabel)",
            details = detail
        )
    }

    private fun record(level: LogLevel, category: LogCategory, message: String, details: String?) {
        if (!_isLoggingEnabled.value) return
        val now = System.currentTimeMillis()
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(now))
        val entry = DiagnosticLogEntry(
            id = idCounter.getAndIncrement(),
            epochMs = now,
            timestamp = stamp,
            level = level,
            category = category,
            message = message,
            details = details
        )
        _entries.value = (listOf(entry) + _entries.value).take(MAX_MEMORY_ENTRIES)

        synchronized(fileLock) {
            runCatching {
                rotateIfOversized()
                FileOutputStream(persistentLogFile, true).bufferedWriter(Charsets.UTF_8).use { writer ->
                    writer.write(entry.formatLine())
                    writer.write("\n")
                }
            }
        }
    }

    fun clearLogs() {
        _entries.value = emptyList()
        synchronized(fileLock) {
            runCatching {
                if (persistentLogFile.exists()) persistentLogFile.delete()
            }
        }
    }

    fun getLogFileSizeBytes(): Long = synchronized(fileLock) {
        if (persistentLogFile.exists()) persistentLogFile.length() else 0L
    }

    /**
     * Builds a complete diagnostic report suitable for saving to the phone or sending to a developer.
     */
    fun buildFullDiagnosticReport(
        localDeviceName: String,
        localDeviceId: String,
        localFingerprint: String,
        localIps: List<String>,
        activeTcpPort: Int,
        activeUdpPort: Int,
        multicastHeld: Boolean,
        sharedFolderNames: List<String>,
        devices: List<PairedDeviceEntity>
    ): String {
        val generatedAt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())
        return buildString {
            appendLine("======================================================================")
            appendLine("APEXCONNECT DIAGNOSTIC & PROTOCOL REPORT")
            appendLine("Generated: $generatedAt")
            appendLine("======================================================================")
            appendLine("1. ENVIRONMENT & LOCAL NODE")
            appendLine("   Android SDK    : ${Build.VERSION.SDK_INT} (Release ${Build.VERSION.RELEASE})")
            appendLine("   Device Hardware: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("   Local Name     : $localDeviceName")
            appendLine("   Local DeviceId : $localDeviceId")
            appendLine("   X.509 SHA-256  : $localFingerprint")
            appendLine("   LAN IPv4 Addrs : ${if (localIps.isEmpty()) "NONE (Check Wi-Fi!)" else localIps.joinToString(", ")}")
            appendLine("   Bound TCP Port : $activeTcpPort")
            appendLine("   Bound UDP Port : $activeUdpPort")
            appendLine("   MulticastLock  : ${if (multicastHeld) "HELD (OK)" else "NOT HELD"}")
            appendLine("   Exported Roots : Vault + ${if (sharedFolderNames.isEmpty()) "0 SAF folders" else sharedFolderNames.joinToString(", ")}")
            appendLine()
            appendLine("2. DISCOVERED & PAIRED PEERS (${devices.size})")
            if (devices.isEmpty()) {
                appendLine("   (No peers recorded in database yet)")
            } else {
                devices.forEachIndexed { idx, d ->
                    appendLine(
                        "   [${idx + 1}] '${d.deviceName}' (id=${d.deviceId}) @ ${d.ipAddress}:${d.tcpPort} " +
                            "| proto=v${d.protocolVersion} | state=${d.pairState} | online=${d.isReachable}"
                    )
                    appendLine(
                        "       caps: kdeSftp=${d.supportsKdeSftp}, apexVfs=${d.supportsApexVfs}, " +
                            "space=${d.supportsSpaceSync}, mouse=${d.supportsMousepad}, mpris=${d.supportsMpris}"
                    )
                    appendLine("       cert=${d.certificateFingerprint} | verifyKey=${d.verificationCode}")
                }
            }
            appendLine()
            appendLine("3. CHRONOLOGICAL PROTOCOL & NETWORK LOGS")
            appendLine("----------------------------------------------------------------------")
            val diskText = synchronized(fileLock) {
                runCatching {
                    if (persistentLogFile.exists()) persistentLogFile.readText(Charsets.UTF_8) else ""
                }.getOrDefault("")
            }
            if (diskText.isNotBlank()) {
                append(diskText)
            } else {
                _entries.value.asReversed().forEach { appendLine(it.formatLine()) }
            }
        }
    }

    fun exportReportToSafUri(destinationUri: Uri, reportContent: String): Result<Long> = runCatching {
        val bytes = reportContent.toByteArray(Charsets.UTF_8)
        appContext.contentResolver.openOutputStream(destinationUri, "wt")?.use { out ->
            out.write(bytes)
            out.flush()
        } ?: appContext.contentResolver.openOutputStream(destinationUri)?.use { out ->
            out.write(bytes)
            out.flush()
        } ?: throw IllegalStateException("Unable to open selected file URI for writing.")
        bytes.size.toLong()
    }

    fun createShareableReportIntent(reportContent: String): Result<android.content.Intent> = runCatching {
        val shareDir = File(appContext.cacheDir, "exported_logs").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val outFile = File(shareDir, "apexconnect_logs_$stamp.txt")
        outFile.writeText(reportContent, Charsets.UTF_8)

        val uri = FileProvider.getUriForFile(
            appContext,
            "${appContext.packageName}.fileprovider",
            outFile
        )
        android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_SUBJECT, "ApexConnect Diagnostic Logs ($stamp)")
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun rotateIfOversized() {
        if (persistentLogFile.exists() && persistentLogFile.length() > MAX_LOG_FILE_BYTES) {
            val tail = runCatching {
                persistentLogFile.readLines(Charsets.UTF_8).takeLast(600).joinToString("\n") + "\n"
            }.getOrDefault("")
            persistentLogFile.writeText(tail, Charsets.UTF_8)
        }
    }

    private fun loadTailFromDisk() {
        synchronized(fileLock) {
            runCatching {
                if (!persistentLogFile.exists()) return
                val lines = persistentLogFile.readLines(Charsets.UTF_8).takeLast(180)
                val parsed = mutableListOf<DiagnosticLogEntry>()
                val headerRegex = Regex("""^\[([^\]]+)] \[(INFO|WARN|ERROR)] \[([A-Z]+)] (.*)$""")
                for (line in lines) {
                    val m = headerRegex.matchEntire(line)
                    if (m != null) {
                        val lvl = runCatching { LogLevel.valueOf(m.groupValues[2]) }.getOrDefault(LogLevel.INFO)
                        val cat = runCatching { LogCategory.valueOf(m.groupValues[3]) }.getOrDefault(LogCategory.SYSTEM)
                        parsed.add(
                            DiagnosticLogEntry(
                                id = idCounter.getAndIncrement(),
                                epochMs = System.currentTimeMillis(),
                                timestamp = m.groupValues[1],
                                level = lvl,
                                category = cat,
                                message = m.groupValues[4]
                            )
                        )
                    } else if (parsed.isNotEmpty() && line.startsWith("    ")) {
                        val last = parsed.removeAt(parsed.lastIndex)
                        val nextDetail = if (last.details.isNullOrBlank()) {
                            line.removePrefix("    ")
                        } else {
                            last.details + "\n" + line.removePrefix("    ")
                        }
                        parsed.add(last.copy(details = nextDetail))
                    }
                }
                _entries.value = parsed.asReversed().take(MAX_MEMORY_ENTRIES)
            }
        }
    }

    private fun sanitizePacketJson(packet: NetworkPacket): String {
        return runCatching {
            val copy = JSONObject(packet.body.toString())
            if (copy.has("password")) copy.put("password", "***REDACTED***")
            buildString {
                append("body=").append(copy.toString())
                if (packet.payloadTransferInfo != null) {
                    append(" payloadTransferInfo=").append(packet.payloadTransferInfo.toString())
                }
            }
        }.getOrDefault("{}")
    }

    companion object {
        private const val KEY_LOGGING_ENABLED = "record_logs_enabled"
        private const val KEY_VERBOSE_PACKETS = "verbose_packets_enabled"
        private const val MAX_MEMORY_ENTRIES = 500
        private const val MAX_LOG_FILE_BYTES = 1_500_000L // ~1.5 MB rolling log

        @Volatile
        private var defaultInstance: DiagnosticLogger? = null

        fun getInstance(context: Context): DiagnosticLogger {
            return defaultInstance ?: synchronized(this) {
                defaultInstance ?: DiagnosticLogger(context.applicationContext).also {
                    defaultInstance = it
                }
            }
        }
    }
}
