package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.network.RemoteFileEntry
import com.example.data.local.PairState
import com.example.data.local.PairedDeviceEntity
import com.example.data.local.TransferRecordEntity
import com.example.ui.RemoteDirectoryState
import com.example.ui.components.formatByteSize
import com.example.ui.components.formatDateAndTime
import com.example.ui.theme.BreezeCyan
import com.example.ui.theme.BreezeTeal
import com.example.ui.theme.EmeraldSuccess

@Composable
fun RemoteFileBrowserScreen(
    selectedDevice: PairedDeviceEntity?,
    currentPath: String,
    directoryState: RemoteDirectoryState,
    recentTransfers: List<TransferRecordEntity>,
    onNavigateToDirectory: (String) -> Unit,
    onNavigateUp: () -> Unit,
    onRefresh: () -> Unit,
    onDownloadFileToSaf: (RemoteFileEntry, Uri) -> Unit,
    onDownloadAndOpenFile: (android.content.Context, RemoteFileEntry) -> Unit,
    onUploadFileToCurrentDir: (Uri) -> Unit,
    onRequestPair: (String) -> Unit,
    onBackToHub: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var pendingDownloadEntry by remember { mutableStateOf<RemoteFileEntry?>(null) }

    // System back button navigates up parent directory first, then returns to Hub
    BackHandler {
        if (currentPath != "/" && currentPath.isNotBlank()) {
            onNavigateUp()
        } else {
            onBackToHub()
        }
    }

    // SAF CreateDocument launcher for saving remote files to user-selected storage
    val safDownloadLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*")
    ) { destinationUri: Uri? ->
        val entry = pendingDownloadEntry
        if (destinationUri != null && entry != null) {
            onDownloadFileToSaf(entry, destinationUri)
        }
        pendingDownloadEntry = null
    }

    // SAF OpenDocument launcher for uploading local files into the active remote folder
    val uploadPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { sourceUri: Uri? ->
        if (sourceUri != null) {
            onUploadFileToCurrentDir(sourceUri)
        }
    }

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 760.dp)
                .padding(horizontal = 16.dp)
                .testTag("remote_files_screen")
        ) {
            // Header & Actions Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.files_title),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = selectedDevice?.let { "${it.deviceName} • ${it.platformOs}" }
                            ?: stringResource(R.string.files_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    IconButton(
                        onClick = onRefresh,
                        modifier = Modifier.testTag("refresh_remote_dir_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.files_refresh)
                        )
                    }

                    if (selectedDevice?.pairState == PairState.PAIRED &&
                        selectedDevice.isReachable &&
                        selectedDevice.supportsUploadFs
                    ) {
                        FilledTonalButton(
                            onClick = { uploadPickerLauncher.launch(arrayOf("*/*")) },
                            modifier = Modifier.testTag("upload_remote_file_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.CloudUpload,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Upload")
                        }
                    }
                }
            }

            // Interactive Breadcrumb Bar
            BreadcrumbStrip(
                currentPath = currentPath,
                canGoUp = currentPath != "/" && currentPath.isNotBlank(),
                onNavigateUp = onNavigateUp,
                onSelectBreadcrumbPath = onNavigateToDirectory
            )

            Spacer(modifier = Modifier.height(10.dp))

            // State-specific Directory Content
            when {
                selectedDevice == null -> {
                    StatusMessageCard(
                        icon = Icons.Filled.WarningAmber,
                        title = "No Device Selected",
                        body = "Select a paired KDE Connect device above to browse its exported filesystem."
                    )
                }

                selectedDevice.pairState != PairState.PAIRED -> {
                    StatusMessageCard(
                        icon = Icons.Filled.WarningAmber,
                        title = "TLS Pairing Required",
                        body = stringResource(R.string.files_unpaired_notice),
                        actionLabel = stringResource(R.string.action_pair),
                        onAction = { onRequestPair(selectedDevice.deviceId) }
                    )
                }

                directoryState is RemoteDirectoryState.Loading -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "Querying encrypted VFS directory…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                directoryState is RemoteDirectoryState.Unsupported -> {
                    StatusMessageCard(
                        icon = Icons.Filled.ErrorOutline,
                        title = "Platform Capability Unsupported",
                        body = directoryState.reason
                    )
                }

                directoryState is RemoteDirectoryState.Error -> {
                    StatusMessageCard(
                        icon = Icons.Filled.ErrorOutline,
                        title = "Remote Directory Unavailable",
                        body = directoryState.message,
                        actionLabel = stringResource(R.string.files_refresh),
                        onAction = onRefresh
                    )
                }

                directoryState is RemoteDirectoryState.Loaded -> {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .testTag("remote_files_list"),
                        contentPadding = PaddingValues(bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (directoryState.currentPath != "/") {
                            item(key = "__parent_up__") {
                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(14.dp))
                                        .clickable(onClick = onNavigateUp)
                                        .testTag("parent_dir_row")
                                ) {
                                    Row(
                                        modifier = Modifier.padding(14.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Text(
                                            text = stringResource(R.string.files_up_parent),
                                            style = MaterialTheme.typography.titleSmall,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }
                            }
                        }

                        if (directoryState.entries.isEmpty()) {
                            item {
                                EmptyDirectoryView()
                            }
                        } else {
                            items(directoryState.entries, key = { it.relativePath }) { entry ->
                                RemoteFileRow(
                                    entry = entry,
                                    onClickDirectory = { onNavigateToDirectory(entry.relativePath) },
                                    onOpenFile = { onDownloadAndOpenFile(context, entry) },
                                    onSaveToSaf = {
                                        pendingDownloadEntry = entry
                                        safDownloadLauncher.launch(entry.name)
                                    }
                                )
                            }
                        }

                        if (recentTransfers.isNotEmpty()) {
                            item {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "Verified Transfer Ledger",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            items(recentTransfers.take(5), key = { it.transferId }) { rec ->
                                TransferHistoryItem(rec)
                            }
                        }
                    }
                }

                else -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Button(onClick = onRefresh) {
                            Text("Load Remote Export Root")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BreadcrumbStrip(
    currentPath: String,
    canGoUp: Boolean,
    onNavigateUp: () -> Unit,
    onSelectBreadcrumbPath: (String) -> Unit
) {
    val segments = currentPath.split('/').filter { it.isNotBlank() }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (canGoUp) {
                IconButton(
                    onClick = onNavigateUp,
                    modifier = Modifier
                        .size(36.dp)
                        .testTag("breadcrumb_up_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Up directory",
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
            }

            Surface(
                shape = RoundedCornerShape(8.dp),
                color = if (segments.isEmpty()) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onSelectBreadcrumbPath("/") }
                    .testTag("breadcrumb_root")
            ) {
                Text(
                    text = "/root",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }

            var accumulated = ""
            segments.forEachIndexed { idx, seg ->
                accumulated += "/$seg"
                val target = accumulated
                Text(
                    text = " / ",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (idx == segments.lastIndex) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainer
                    },
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onSelectBreadcrumbPath(target) }
                ) {
                    Text(
                        text = seg,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun RemoteFileRow(
    entry: RemoteFileEntry,
    onClickDirectory: () -> Unit,
    onOpenFile: () -> Unit,
    onSaveToSaf: () -> Unit
) {
    val icon = when {
        entry.isDirectory -> Icons.Filled.Folder
        entry.mimeType.startsWith("image/") -> Icons.Filled.Image
        entry.name.endsWith(".json") || entry.name.endsWith(".sh") || entry.name.endsWith(".kt") -> Icons.Filled.Code
        entry.mimeType.startsWith("text/") -> Icons.Filled.Description
        else -> Icons.AutoMirrored.Filled.InsertDriveFile
    }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable {
                if (entry.isDirectory) onClickDirectory() else onOpenFile()
            }
            .testTag("vfs_entry_${entry.name}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (entry.isDirectory) BreezeCyan.copy(alpha = 0.16f)
                            else BreezeTeal.copy(alpha = 0.15f)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = entry.name,
                        tint = if (entry.isDirectory) BreezeCyan else BreezeTeal
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = entry.name,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val metaText = if (entry.isDirectory) {
                        "Directory • ${formatDateAndTime(entry.lastModifiedEpochMs)}"
                    } else {
                        "${formatByteSize(entry.sizeBytes)} • ${entry.mimeType} • ${formatDateAndTime(entry.lastModifiedEpochMs)}"
                    }
                    Text(
                        text = metaText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (!entry.isDirectory) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onOpenFile,
                        modifier = Modifier.testTag("open_file_${entry.name}")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = stringResource(R.string.files_open_local),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    IconButton(
                        onClick = onSaveToSaf,
                        modifier = Modifier.testTag("download_saf_${entry.name}")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Download,
                            contentDescription = stringResource(R.string.files_download_saf),
                            tint = MaterialTheme.colorScheme.secondary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyDirectoryView() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(
            painter = painterResource(id = R.drawable.img_empty_vault),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(150.dp)
                .clip(RoundedCornerShape(20.dp))
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.files_empty_dir),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = "Tap Upload above to push documents or media into this remote folder.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun StatusMessageCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    body: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp)
            .testTag("files_status_card")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(40.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            if (actionLabel != null && onAction != null) {
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedButton(
                    onClick = onAction,
                    modifier = Modifier.testTag("files_status_action_button")
                ) {
                    Text(actionLabel)
                }
            }
        }
    }
}

@Composable
private fun TransferHistoryItem(record: TransferRecordEntity) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${record.direction}: ${record.fileName}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = buildString {
                        append(record.status)
                        if (!record.sha256Checksum.isNullOrBlank()) {
                            append(" • SHA-256 ${record.sha256Checksum.take(10)}…")
                        }
                        if (!record.errorMessage.isNullOrBlank()) {
                            append(" • ${record.errorMessage}")
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (record.status == "COMPLETED") {
                Icon(
                    imageVector = Icons.Filled.Verified,
                    contentDescription = "Integrity verified",
                    tint = EmeraldSuccess,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
