package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderShared
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.core.diagnostics.DiagnosticLogEntry
import com.example.core.diagnostics.LogCategory
import com.example.core.diagnostics.LogLevel
import com.example.core.security.SharedSafTree
import com.example.ui.ApexUiState
import com.example.ui.components.AppleGroupedRow
import com.example.ui.components.AppleGroupedSection
import com.example.ui.components.AppleInsetDivider
import com.example.ui.components.AppleSectionHeader
import com.example.ui.components.formatByteSize
import com.example.ui.theme.AppleBlue
import com.example.ui.theme.AppleGreen
import com.example.ui.theme.AppleIndigo
import com.example.ui.theme.AppleOrange
import com.example.ui.theme.AppleRed
import com.example.ui.theme.AppleTeal
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class LogFilterMode(val label: String) {
    ALL("All"),
    ERRORS("Errors"),
    NETWORK("Discovery & TLS"),
    FILES("Files & SFTP"),
    PLUGINS("Plugins & Pair")
}

@Composable
fun SettingsScreen(
    uiState: ApexUiState,
    isLoggingEnabled: Boolean,
    isVerbosePacketsEnabled: Boolean,
    diagnosticEntries: List<DiagnosticLogEntry>,
    logFileSizeBytes: Long,
    sharedSafTrees: List<SharedSafTree>,
    onToggleLogging: (Boolean) -> Unit,
    onToggleVerbosePackets: (Boolean) -> Unit,
    onExportLogsToSaf: (Uri) -> Unit,
    onShareLogsFile: (android.content.Context) -> Unit,
    onCopyLogs: () -> Unit,
    onClearLogs: () -> Unit,
    onAddSharedFolder: (Uri) -> Unit,
    onRemoveSharedFolder: (String) -> Unit,
    onRenameDevice: (String) -> Unit,
    onBackToHub: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var logFilter by remember { mutableStateOf(LogFilterMode.ALL) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameDraft by remember(uiState.localNodeName) { mutableStateOf(uiState.localNodeName) }

    BackHandler {
        onBackToHub()
    }

    val exportSafLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain")
    ) { uri: Uri? ->
        if (uri != null) {
            onExportLogsToSaf(uri)
        }
    }

    val shareSafFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri: Uri? ->
        if (treeUri != null) {
            onAddSharedFolder(treeUri)
        }
    }

    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Device Name") },
            text = {
                OutlinedTextField(
                    value = renameDraft,
                    onValueChange = { renameDraft = it },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onRenameDevice(renameDraft)
                        showRenameDialog = false
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    val filteredEntries = remember(diagnosticEntries, logFilter) {
        when (logFilter) {
            LogFilterMode.ALL -> diagnosticEntries
            LogFilterMode.ERRORS -> diagnosticEntries.filter { it.level != LogLevel.INFO }
            LogFilterMode.NETWORK -> diagnosticEntries.filter {
                it.category == LogCategory.DISCOVERY || it.category == LogCategory.TLS || it.category == LogCategory.SYSTEM
            }
            LogFilterMode.FILES -> diagnosticEntries.filter { it.category == LogCategory.FILES }
            LogFilterMode.PLUGINS -> diagnosticEntries.filter {
                it.category == LogCategory.PAIRING || it.category == LogCategory.PLUGINS || it.category == LogCategory.SPACE
            }
        }
    }

    val ipSummary = if (uiState.localIpAddresses.isEmpty()) {
        "Not connected to Wi-Fi"
    } else {
        uiState.localIpAddresses.joinToString(", ")
    }

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 680.dp)
                .testTag("settings_screen"),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            item {
                Column {
                    Text(
                        text = "Settings",
                        style = MaterialTheme.typography.displaySmall,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = "Device Identity, Shared Folders & Diagnostic Logs",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 1. DIAGNOSTIC LOGGING & EXPORT SECTION (Requested by User!)
            item {
                Column {
                    AppleSectionHeader(title = "Diagnostics & Debug Logs")
                    AppleGroupedSection {
                        AppleGroupedRow(
                            title = "Record Diagnostic Logs",
                            subtitle = "${diagnosticEntries.size} events • ${formatByteSize(logFileSizeBytes)}",
                            leadingIcon = Icons.Filled.BugReport,
                            iconTint = AppleOrange,
                            showChevron = false,
                            trailingContent = {
                                Switch(
                                    checked = isLoggingEnabled,
                                    onCheckedChange = onToggleLogging,
                                    modifier = Modifier.testTag("settings_log_toggle")
                                )
                            }
                        )
                        AppleInsetDivider()
                        AppleGroupedRow(
                            title = "Verbose Wire Packet Details",
                            subtitle = "Logs KDE Connect JSON packets, ports & stack traces",
                            leadingIcon = Icons.Filled.Wifi,
                            iconTint = AppleIndigo,
                            showChevron = false,
                            trailingContent = {
                                Switch(
                                    checked = isVerbosePacketsEnabled,
                                    onCheckedChange = onToggleVerbosePackets,
                                    enabled = isLoggingEnabled,
                                    modifier = Modifier.testTag("settings_verbose_log_toggle")
                                )
                            }
                        )
                        AppleInsetDivider()
                        AppleGroupedRow(
                            title = "Export Logs as File (.txt)",
                            subtitle = "Save complete diagnostic report to phone Downloads",
                            leadingIcon = Icons.Filled.Download,
                            iconTint = AppleBlue,
                            onClick = {
                                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                                exportSafLauncher.launch("apexconnect_logs_$stamp.txt")
                            },
                            testTag = "export_logs_saf_button"
                        )
                        AppleInsetDivider()
                        AppleGroupedRow(
                            title = "Share Diagnostic File",
                            subtitle = "Send log file via Telegram, WhatsApp, or Email",
                            leadingIcon = Icons.Filled.IosShare,
                            iconTint = AppleGreen,
                            onClick = { onShareLogsFile(context) },
                            testTag = "share_logs_button"
                        )
                        AppleInsetDivider()
                        AppleGroupedRow(
                            title = "Copy Full Report to Clipboard",
                            subtitle = "Paste directly into chat for debugging",
                            leadingIcon = Icons.Filled.ContentCopy,
                            iconTint = AppleTeal,
                            onClick = onCopyLogs,
                            testTag = "copy_logs_button"
                        )
                        AppleInsetDivider()
                        AppleGroupedRow(
                            title = "Clear Recorded Logs",
                            leadingIcon = Icons.Filled.DeleteOutline,
                            iconTint = AppleRed,
                            showChevron = false,
                            onClick = onClearLogs,
                            testTag = "clear_logs_button"
                        )
                    }
                }
            }

            // 2. SHARED PHONE FOLDERS FOR REMOTE FILE BROWSING
            item {
                Column {
                    AppleSectionHeader(title = "Shared Folders (Remote File Browser)")
                    AppleGroupedSection {
                        AppleGroupedRow(
                            title = "Share Phone Folder…",
                            subtitle = "Grant a folder (Downloads, DCIM, Documents) for paired peers",
                            leadingIcon = Icons.Filled.CreateNewFolder,
                            iconTint = AppleBlue,
                            onClick = { shareSafFolderLauncher.launch(null) },
                            testTag = "settings_add_saf_folder_button"
                        )
                        if (sharedSafTrees.isNotEmpty()) {
                            sharedSafTrees.forEach { tree ->
                                AppleInsetDivider()
                                AppleGroupedRow(
                                    title = tree.alias,
                                    subtitle = "Exported over Mutual TLS VFS",
                                    leadingIcon = Icons.Filled.FolderShared,
                                    iconTint = AppleTeal,
                                    showChevron = false,
                                    trailingContent = {
                                        IconButton(onClick = { onRemoveSharedFolder(tree.alias) }) {
                                            Icon(
                                                imageVector = Icons.Filled.DeleteOutline,
                                                contentDescription = "Remove shared folder",
                                                tint = AppleRed
                                            )
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // 3. LOCAL NODE & NETWORK IDENTITY
            item {
                Column {
                    AppleSectionHeader(title = "This Device & Network")
                    AppleGroupedSection {
                        AppleGroupedRow(
                            title = uiState.localNodeName,
                            subtitle = "Device Name • Tap to edit",
                            leadingIcon = Icons.Filled.Smartphone,
                            iconTint = AppleBlue,
                            onClick = { showRenameDialog = true },
                            trailingContent = {
                                Icon(
                                    imageVector = Icons.Filled.Edit,
                                    contentDescription = "Edit name",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        )
                        AppleInsetDivider()
                        AppleGroupedRow(
                            title = "LAN IPv4 & Ports",
                            subtitle = "$ipSummary • TCP ${uiState.activeTcpPort} / UDP ${uiState.activeUdpPort}",
                            leadingIcon = Icons.Filled.Wifi,
                            iconTint = AppleGreen,
                            showChevron = false
                        )
                        AppleInsetDivider()
                        AppleGroupedRow(
                            title = "X.509 Certificate SHA-256",
                            subtitle = "${uiState.localFingerprint}\nID: ${uiState.localDeviceId}",
                            leadingIcon = Icons.Filled.Security,
                            iconTint = AppleIndigo,
                            showChevron = false
                        )
                    }
                }
            }

            // 4. LIVE DIAGNOSTIC LOG CONSOLE
            item {
                Column {
                    AppleSectionHeader(title = "Live Protocol Console (${filteredEntries.size})")
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(bottom = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        LogFilterMode.entries.forEach { mode ->
                            FilterChip(
                                selected = logFilter == mode,
                                onClick = { logFilter = mode },
                                label = { Text(mode.label) }
                            )
                        }
                    }

                    AppleGroupedSection(modifier = Modifier.testTag("live_log_console_card")) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            if (filteredEntries.isEmpty()) {
                                Text(
                                    text = if (isLoggingEnabled) {
                                        "No log entries match this filter yet."
                                    } else {
                                        "Logging is paused. Turn on 'Record Diagnostic Logs' above."
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                filteredEntries.take(60).forEachIndexed { idx, entry ->
                                    DiagnosticConsoleEntryItem(entry)
                                    if (idx < minOf(filteredEntries.size, 60) - 1) {
                                        HorizontalDivider(
                                            thickness = 0.5.dp,
                                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagnosticConsoleEntryItem(entry: DiagnosticLogEntry) {
    val badgeColor = when (entry.level) {
        LogLevel.ERROR -> AppleRed
        LogLevel.WARN -> AppleOrange
        LogLevel.INFO -> AppleBlue
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(5.dp))
                    .background(badgeColor.copy(alpha = 0.15f))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "${entry.level.name} • ${entry.category.name}",
                    style = MaterialTheme.typography.labelSmall,
                    color = badgeColor,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = entry.timestamp.substringAfter(' '),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = entry.message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        if (!entry.details.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .padding(8.dp)
            ) {
                Text(
                    text = entry.details,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFF8E8E93)
                )
            }
        }
    }
}
