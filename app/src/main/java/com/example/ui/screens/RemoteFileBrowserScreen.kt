package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.network.RemoteFileEntry
import com.example.data.local.PairState
import com.example.data.local.PairedDeviceEntity
import com.example.data.local.TransferRecordEntity
import com.example.ui.RemoteDirectoryState
import com.example.ui.components.AppleGroupedRow
import com.example.ui.components.AppleGroupedSection
import com.example.ui.components.AppleInsetDivider
import com.example.ui.components.AppleSectionHeader
import com.example.ui.components.AppleSquircleIcon
import com.example.ui.components.formatByteSize
import com.example.ui.components.formatDateAndTime
import com.example.ui.theme.AppleBlue
import com.example.ui.theme.AppleGreen
import com.example.ui.theme.AppleIndigo
import com.example.ui.theme.AppleOrange
import com.example.ui.theme.AppleTeal

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
    onConnectCustomSftp: (host: String, port: Int, username: String, password: String, rootPath: String) -> Unit,
    onAddSharedPhoneFolder: (Uri) -> Unit,
    onRequestPair: (String) -> Unit,
    onBackToHub: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var pendingDownloadEntry by remember { mutableStateOf<RemoteFileEntry?>(null) }
    var showSftpDialog by remember { mutableStateOf(false) }

    var sftpHost by remember(selectedDevice?.ipAddress) { mutableStateOf(selectedDevice?.ipAddress.orEmpty()) }
    var sftpPort by remember { mutableStateOf("22") }
    var sftpUser by remember { mutableStateOf("") }
    var sftpPassword by remember { mutableStateOf("") }
    var sftpRoot by remember { mutableStateOf("/") }

    BackHandler {
        if (currentPath != "/" && currentPath.isNotBlank()) {
            onNavigateUp()
        } else {
            onBackToHub()
        }
    }

    val safDownloadLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*")
    ) { destinationUri: Uri? ->
        val entry = pendingDownloadEntry
        if (destinationUri != null && entry != null) {
            onDownloadFileToSaf(entry, destinationUri)
        }
        pendingDownloadEntry = null
    }

    val uploadPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { sourceUri: Uri? ->
        if (sourceUri != null) {
            onUploadFileToCurrentDir(sourceUri)
        }
    }

    val shareFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri: Uri? ->
        if (treeUri != null) {
            onAddSharedPhoneFolder(treeUri)
        }
    }

    if (showSftpDialog && selectedDevice != null) {
        AlertDialog(
            onDismissRequest = { showSftpDialog = false },
            title = { Text("Connect via SFTP / SSH") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Browse ${selectedDevice.deviceName} directly over OpenSSH / SFTP (Linux sshd, macOS Remote Login, or Windows OpenSSH).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = sftpHost,
                            onValueChange = { sftpHost = it },
                            label = { Text("Host / IP") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = sftpPort,
                            onValueChange = { sftpPort = it },
                            label = { Text("Port") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.width(86.dp)
                        )
                    }
                    OutlinedTextField(
                        value = sftpUser,
                        onValueChange = { sftpUser = it },
                        label = { Text("Username") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = sftpPassword,
                        onValueChange = { sftpPassword = it },
                        label = { Text("Password") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = sftpRoot,
                        onValueChange = { sftpRoot = it },
                        label = { Text("Initial Folder (e.g. / or /home/user)") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val portNum = sftpPort.toIntOrNull() ?: 22
                        onConnectCustomSftp(sftpHost, portNum, sftpUser, sftpPassword, sftpRoot)
                        showSftpDialog = false
                    },
                    enabled = sftpUser.isNotBlank() && sftpPassword.isNotBlank(),
                    shape = CircleShape
                ) {
                    Text("Connect")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSftpDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 680.dp)
                .padding(horizontal = 20.dp)
                .testTag("remote_files_screen")
        ) {
            // Apple Files Style Large Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Browse",
                        style = MaterialTheme.typography.displaySmall,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = selectedDevice?.deviceName ?: stringResource(R.string.files_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surface,
                        shadowElevation = 0.5.dp,
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .clickable { shareFolderLauncher.launch(null) }
                            .testTag("share_local_folder_button")
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Filled.CreateNewFolder,
                                contentDescription = "Share local folder",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(19.dp)
                            )
                        }
                    }

                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surface,
                        shadowElevation = 0.5.dp,
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .clickable(onClick = onRefresh)
                            .testTag("refresh_remote_dir_button")
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = stringResource(R.string.files_refresh),
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(19.dp)
                            )
                        }
                    }

                    if (selectedDevice?.pairState == PairState.PAIRED && selectedDevice.supportsUploadFs) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clip(CircleShape)
                                .clickable { uploadPickerLauncher.launch(arrayOf("*/*")) }
                                .testTag("upload_remote_file_button")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.CloudUpload,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Upload",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = Color.White
                                )
                            }
                        }
                    }
                }
            }

            FinderPathBar(
                currentPath = currentPath,
                canGoUp = currentPath != "/" && currentPath.isNotBlank(),
                onNavigateUp = onNavigateUp,
                onSelectBreadcrumbPath = onNavigateToDirectory
            )

            Spacer(modifier = Modifier.height(14.dp))

            when {
                selectedDevice == null -> {
                    AppleFilesStatusPlatter(
                        icon = Icons.Filled.WarningAmber,
                        tint = AppleOrange,
                        title = "No Paired Device Active",
                        body = "Scan your Wi-Fi network on the Devices tab and pair with a PC, Mac, or phone to browse its shared folders.",
                        actionLabel = "Open Devices",
                        onAction = onBackToHub
                    )
                }

                selectedDevice.pairState != PairState.PAIRED -> {
                    AppleFilesStatusPlatter(
                        icon = Icons.Filled.WarningAmber,
                        tint = AppleOrange,
                        title = "Pairing Required",
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
                            CircularProgressIndicator(
                                color = MaterialTheme.colorScheme.primary,
                                strokeWidth = 3.dp,
                                modifier = Modifier.size(30.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "Connecting to remote folder…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                directoryState is RemoteDirectoryState.Unsupported -> {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentPadding = PaddingValues(bottom = 28.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        item {
                            AppleFilesStatusPlatter(
                                icon = Icons.Filled.Dns,
                                tint = AppleBlue,
                                title = "Connect to ${selectedDevice.deviceName} Folders",
                                body = directoryState.reason,
                                actionLabel = "Connect via SFTP / SSH",
                                onAction = { showSftpDialog = true },
                                secondaryActionLabel = "Send File (KDE Share)",
                                onSecondaryAction = { uploadPickerLauncher.launch(arrayOf("*/*")) }
                            )
                        }
                        if (recentTransfers.isNotEmpty()) {
                            item {
                                RecentTransfersSection(recentTransfers)
                            }
                        }
                    }
                }

                directoryState is RemoteDirectoryState.Error -> {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentPadding = PaddingValues(bottom = 28.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        item {
                            AppleFilesStatusPlatter(
                                icon = Icons.Filled.ErrorOutline,
                                tint = AppleOrange,
                                title = "Folder Unavailable",
                                body = directoryState.message,
                                actionLabel = "Try Again",
                                onAction = onRefresh,
                                secondaryActionLabel = "Connect via SFTP / SSH",
                                onSecondaryAction = { showSftpDialog = true }
                            )
                        }
                        if (recentTransfers.isNotEmpty()) {
                            item {
                                RecentTransfersSection(recentTransfers)
                            }
                        }
                    }
                }

                directoryState is RemoteDirectoryState.Loaded -> {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .testTag("remote_files_list"),
                        contentPadding = PaddingValues(bottom = 28.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp)
                    ) {
                        if (directoryState.currentPath != "/") {
                            item(key = "__parent_up__") {
                                AppleGroupedSection {
                                    AppleGroupedRow(
                                        title = "Back to Parent Folder",
                                        leadingIcon = Icons.AutoMirrored.Filled.ArrowBack,
                                        iconTint = AppleBlue,
                                        showChevron = false,
                                        onClick = onNavigateUp,
                                        testTag = "parent_dir_row"
                                    )
                                }
                            }
                        }

                        if (directoryState.entries.isEmpty()) {
                            item {
                                AppleEmptyFolderPlatter()
                            }
                        } else {
                            item {
                                Column {
                                    AppleSectionHeader(title = "${directoryState.entries.size} Items")
                                    AppleGroupedSection {
                                        directoryState.entries.forEachIndexed { index, entry ->
                                            AppleFinderRow(
                                                entry = entry,
                                                onClickDirectory = { onNavigateToDirectory(entry.relativePath) },
                                                onOpenFile = { onDownloadAndOpenFile(context, entry) },
                                                onSaveToSaf = {
                                                    pendingDownloadEntry = entry
                                                    safDownloadLauncher.launch(entry.name)
                                                }
                                            )
                                            if (index < directoryState.entries.lastIndex) {
                                                AppleInsetDivider()
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        if (recentTransfers.isNotEmpty()) {
                            item {
                                RecentTransfersSection(recentTransfers)
                            }
                        }
                    }
                }

                else -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(onClick = onRefresh, shape = CircleShape) {
                                Text("Open Shared Folder")
                            }
                            FilledTonalButton(onClick = { showSftpDialog = true }, shape = CircleShape) {
                                Text("SFTP Login")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentTransfersSection(recentTransfers: List<TransferRecordEntity>) {
    Column {
        AppleSectionHeader(title = "Recent Transfers")
        AppleGroupedSection {
            recentTransfers.take(5).forEachIndexed { index, rec ->
                AppleTransferLedgerRow(rec)
                if (index < minOf(recentTransfers.size, 5) - 1) {
                    AppleInsetDivider()
                }
            }
        }
    }
}

@Composable
private fun FinderPathBar(
    currentPath: String,
    canGoUp: Boolean,
    onNavigateUp: () -> Unit,
    onSelectBreadcrumbPath: (String) -> Unit
) {
    val segments = currentPath.split('/').filter { it.isNotBlank() }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 0.5.dp,
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
                        .size(28.dp)
                        .testTag("breadcrumb_up_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(17.dp)
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
            }

            Text(
                text = "Shared Root",
                style = MaterialTheme.typography.labelLarge,
                color = if (segments.isEmpty()) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
                fontWeight = if (segments.isEmpty()) FontWeight.SemiBold else FontWeight.Medium,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { onSelectBreadcrumbPath("/") }
                    .padding(horizontal = 6.dp, vertical = 2.dp)
                    .testTag("breadcrumb_root")
            )

            var accumulated = ""
            segments.forEachIndexed { idx, seg ->
                accumulated += "/$seg"
                val target = accumulated
                val isLast = idx == segments.lastIndex
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = seg,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isLast) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
                    fontWeight = if (isLast) FontWeight.SemiBold else FontWeight.Medium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { onSelectBreadcrumbPath(target) }
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun AppleFinderRow(
    entry: RemoteFileEntry,
    onClickDirectory: () -> Unit,
    onOpenFile: () -> Unit,
    onSaveToSaf: () -> Unit
) {
    val (icon, tint) = when {
        entry.isDirectory -> Icons.Filled.Folder to AppleBlue
        entry.mimeType.startsWith("image/") -> Icons.Filled.Image to AppleTeal
        entry.name.endsWith(".json") || entry.name.endsWith(".sh") || entry.name.endsWith(".kt") ->
            Icons.Filled.Code to AppleIndigo
        entry.mimeType.startsWith("text/") -> Icons.Filled.Description to AppleOrange
        else -> Icons.AutoMirrored.Filled.InsertDriveFile to Color(0xFF8E8E93)
    }

    val subtitle = if (entry.isDirectory) {
        "Folder • ${formatDateAndTime(entry.lastModifiedEpochMs)}"
    } else {
        "${formatByteSize(entry.sizeBytes)} • ${formatDateAndTime(entry.lastModifiedEpochMs)}"
    }

    AppleGroupedRow(
        title = entry.name,
        subtitle = subtitle,
        leadingIcon = icon,
        iconTint = tint,
        onClick = { if (entry.isDirectory) onClickDirectory() else onOpenFile() },
        testTag = "vfs_entry_${entry.name}",
        trailingContent = if (entry.isDirectory) {
            null
        } else {
            {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onSaveToSaf,
                        modifier = Modifier
                            .size(36.dp)
                            .testTag("download_saf_${entry.name}")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Download,
                            contentDescription = stringResource(R.string.files_download_saf),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    )
}

@Composable
private fun AppleEmptyFolderPlatter() {
    AppleGroupedSection {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 36.dp, horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            AppleSquircleIcon(
                icon = Icons.Filled.FolderOpen,
                tintColor = AppleBlue,
                sizeDp = 48
            )
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = "Folder is Empty",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Tap Upload above to send files from your phone directly into this folder, or tap the folder+ icon to expose a folder from your phone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun AppleFilesStatusPlatter(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    title: String,
    body: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    secondaryActionLabel: String? = null,
    onSecondaryAction: (() -> Unit)? = null
) {
    AppleGroupedSection(modifier = Modifier.testTag("files_status_card")) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(26.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            AppleSquircleIcon(icon = icon, tintColor = tint, sizeDp = 46)
            Spacer(modifier = Modifier.height(14.dp))
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
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = onAction,
                        shape = CircleShape,
                        modifier = Modifier.testTag("files_status_action_button")
                    ) {
                        Text(actionLabel)
                    }
                    if (secondaryActionLabel != null && onSecondaryAction != null) {
                        OutlinedButton(
                            onClick = onSecondaryAction,
                            shape = CircleShape,
                            modifier = Modifier.testTag("files_secondary_action_button")
                        ) {
                            Text(secondaryActionLabel)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppleTransferLedgerRow(record: TransferRecordEntity) {
    val sub = buildString {
        append(record.status.lowercase().replaceFirstChar { it.uppercase() })
        if (!record.sha256Checksum.isNullOrBlank()) {
            append(" • Verified SHA-256")
        }
        if (!record.errorMessage.isNullOrBlank()) {
            append(" • ${record.errorMessage}")
        }
    }
    AppleGroupedRow(
        title = record.fileName,
        subtitle = sub,
        showChevron = false,
        trailingContent = {
            if (record.status == "COMPLETED") {
                Icon(
                    imageVector = Icons.Filled.Verified,
                    contentDescription = "Verified",
                    tint = AppleGreen,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    )
}
