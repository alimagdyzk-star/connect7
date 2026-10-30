package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentPasteGo
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.local.PairState
import com.example.data.local.PairedDeviceEntity
import com.example.ui.ApexDestination
import com.example.ui.ApexUiState
import com.example.ui.components.AppleGroupedRow
import com.example.ui.components.AppleGroupedSection
import com.example.ui.components.AppleInsetDivider
import com.example.ui.components.AppleSectionHeader
import com.example.ui.components.AppleSquircleIcon
import com.example.ui.components.deviceIconForType
import com.example.ui.theme.AppleBlue
import com.example.ui.theme.AppleGreen
import com.example.ui.theme.AppleIndigo
import com.example.ui.theme.AppleOrange
import com.example.ui.theme.ApplePink
import com.example.ui.theme.AppleRed
import com.example.ui.theme.AppleTeal

@Composable
fun HubScreen(
    uiState: ApexUiState,
    devices: List<PairedDeviceEntity>,
    protocolEvents: List<String>,
    onSelectDevice: (String, ApexDestination?) -> Unit,
    onRequestPair: (String) -> Unit,
    onAcceptPair: (String) -> Unit,
    onUnpair: (String) -> Unit,
    onScanLan: (String?) -> Unit,
    onRenameLocalNode: (String) -> Unit,
    onNavigateTab: (ApexDestination) -> Unit,
    onRingDevice: () -> Unit,
    onPushClipboard: () -> Unit,
    onSendPing: () -> Unit,
    onQuickShareFile: (Uri) -> Unit,
    onMediaAction: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var showAddByIpDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showSecuritySheet by remember { mutableStateOf(false) }
    var showDiagnostics by remember { mutableStateOf(false) }
    var customIpInput by remember { mutableStateOf("") }
    var renameDraft by remember(uiState.localNodeName) { mutableStateOf(uiState.localNodeName) }

    val quickShareLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) onQuickShareFile(uri)
    }

    val selectedDevice = devices.firstOrNull { it.deviceId == uiState.selectedDeviceId }
        ?: devices.firstOrNull()

    val pairedDevices = devices.filter { it.pairState == PairState.PAIRED }
    val nearbyUnpaired = devices.filter { it.pairState != PairState.PAIRED }

    val primaryLocalIp = uiState.localIpAddresses.firstOrNull()
    val localEndpointText = if (primaryLocalIp != null) {
        "$primaryLocalIp:${uiState.activeTcpPort}"
    } else {
        "Port ${uiState.activeTcpPort} • Connect Wi-Fi"
    }

    if (showAddByIpDialog) {
        AlertDialog(
            onDismissRequest = { showAddByIpDialog = false },
            title = { Text("Connect via IP Address") },
            text = {
                Column {
                    Text(
                        text = "Enter the local Wi-Fi IP or Tailscale IP of a computer or phone running KDE Connect or ApexConnect (default port 1716).",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    OutlinedTextField(
                        value = customIpInput,
                        onValueChange = { customIpInput = it },
                        placeholder = { Text("192.168.1.50 or 192.168.1.50:1716") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("custom_ip_input")
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (customIpInput.isNotBlank()) {
                            onScanLan(customIpInput.trim())
                            customIpInput = ""
                            showAddByIpDialog = false
                        }
                    },
                    shape = CircleShape,
                    modifier = Modifier.testTag("confirm_add_ip_button")
                ) {
                    Text("Connect")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddByIpDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Device Name") },
            text = {
                Column {
                    Text(
                        text = "This name is broadcast to nearby KDE Connect and ApexConnect devices on your Wi-Fi network.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = renameDraft,
                        onValueChange = { renameDraft = it },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("rename_device_input")
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onRenameLocalNode(renameDraft)
                        showRenameDialog = false
                    },
                    shape = CircleShape
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    if (showSecuritySheet) {
        AlertDialog(
            onDismissRequest = { showSecuritySheet = false },
            title = { Text("TLS Encryption & X.509 Identity") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "This Device: ${uiState.localNodeName}",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = "Endpoint: $localEndpointText",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "X.509 SHA-256: ${uiState.localFingerprint}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (selectedDevice != null) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Peer: ${selectedDevice.deviceName} (${selectedDevice.ipAddress}:${selectedDevice.tcpPort})",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            text = "Pairing Verification Key: ${selectedDevice.verificationCode}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Peer SHA-256: ${selectedDevice.certificateFingerprint}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSecuritySheet = false }) {
                    Text("Done")
                }
            }
        )
    }

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 680.dp)
                .testTag("hub_lazy_column"),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // 1. Apple Large Title + Scan & Connect by IP Pills
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Devices",
                        style = MaterialTheme.typography.displaySmall,
                        color = MaterialTheme.colorScheme.onBackground
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surface,
                            shadowElevation = 0.5.dp,
                            modifier = Modifier
                                .clip(CircleShape)
                                .clickable { onScanLan(null) }
                                .testTag("scan_lan_button")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (uiState.isScanningLan) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(15.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Filled.Radar,
                                        contentDescription = "Scan Wi-Fi",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(17.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(5.dp))
                                Text(
                                    text = if (uiState.isScanningLan) "Scanning" else "Scan",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }

                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surface,
                            shadowElevation = 0.5.dp,
                            modifier = Modifier
                                .size(35.dp)
                                .clip(CircleShape)
                                .clickable { showAddByIpDialog = true }
                                .testTag("add_by_ip_button")
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Filled.Add,
                                    contentDescription = stringResource(R.string.action_add_ip),
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }

            // 2. This Phone Identity Platter (Real Local IP, Port, Editable Name & X.509 Cert)
            item {
                AppleGroupedSection(modifier = Modifier.testTag("local_identity_card")) {
                    AppleGroupedRow(
                        title = uiState.localNodeName,
                        subtitle = "This Phone • $localEndpointText",
                        leadingIcon = Icons.Filled.Smartphone,
                        iconTint = AppleBlue,
                        showChevron = false,
                        onClick = { showRenameDialog = true },
                        trailingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(
                                    onClick = { showRenameDialog = true },
                                    modifier = Modifier.size(34.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Edit,
                                        contentDescription = "Rename device",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                IconButton(
                                    onClick = { showSecuritySheet = true },
                                    modifier = Modifier.size(34.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Info,
                                        contentDescription = "Local certificate info",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    )
                }
            }

            // 3. Empty LAN State (Zero fake devices — real AirDrop / KDE Connect radar card!)
            if (selectedDevice == null) {
                item {
                    AppleEmptyDiscoveryPlatter(
                        isScanning = uiState.isScanningLan,
                        localEndpoint = localEndpointText,
                        onScanWifi = { onScanLan(null) },
                        onConnectByIp = { showAddByIpDialog = true }
                    )
                }
            } else {
                // Active Real Peer Hero Card (with authentic incoming vs outgoing pairing flow)
                item {
                    ActiveAppleDeviceHeroCard(
                        device = selectedDevice,
                        onShowSecurityInfo = { showSecuritySheet = true },
                        onRequestPair = { onRequestPair(selectedDevice.deviceId) },
                        onAcceptPair = { onAcceptPair(selectedDevice.deviceId) },
                        onDeclineOrUnpair = { onUnpair(selectedDevice.deviceId) }
                    )
                }
            }

            // 4. Continuity Workspace (Remote Files + Persistent Chat & Notes)
            item {
                Column {
                    AppleSectionHeader(title = "Continuity Workspace")
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        AppleWorkspaceWidgetCard(
                            title = "Remote Files",
                            subtitle = when {
                                selectedDevice == null -> "Connect a device first"
                                selectedDevice.supportsRemoteFs -> "SFTP & LAN browser"
                                else -> "Share files to peer"
                            },
                            icon = Icons.Filled.Folder,
                            iconColor = AppleBlue,
                            onClick = { onNavigateTab(ApexDestination.REMOTE_FILES) },
                            testTag = "spotlight_remote_files_card",
                            modifier = Modifier.weight(1f)
                        )
                        AppleWorkspaceWidgetCard(
                            title = "Chat & Notes",
                            subtitle = if (selectedDevice != null) {
                                "Synced with ${selectedDevice.deviceName}"
                            } else {
                                "Messages & shared board"
                            },
                            icon = Icons.AutoMirrored.Filled.Chat,
                            iconColor = AppleGreen,
                            onClick = { onNavigateTab(ApexDestination.DEVICE_SPACE) },
                            testTag = "spotlight_device_space_card",
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // 5. Control Center Quick Actions & Now Playing (shown when a peer is selected)
            if (selectedDevice != null) {
                item {
                    Column {
                        AppleSectionHeader(
                            title = "Quick Controls",
                            trailingActionLabel = "All Controls",
                            onTrailingClick = { onNavigateTab(ApexDestination.PLUGINS_TOOLS) }
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                AppleControlCenterTile(
                                    label = "Send File",
                                    caption = "KDE Share over TLS",
                                    icon = Icons.Filled.UploadFile,
                                    tint = AppleIndigo,
                                    onClick = { quickShareLauncher.launch(arrayOf("*/*")) },
                                    testTag = "quick_share_file_tile",
                                    modifier = Modifier.weight(1f)
                                )
                                AppleControlCenterTile(
                                    label = "Clipboard",
                                    caption = "Push to Peer",
                                    icon = Icons.Filled.ContentPasteGo,
                                    tint = AppleTeal,
                                    onClick = onPushClipboard,
                                    testTag = "quick_clipboard_tile",
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                AppleControlCenterTile(
                                    label = "Trackpad",
                                    caption = "Mouse & Keyboard",
                                    icon = Icons.Filled.Mouse,
                                    tint = AppleOrange,
                                    onClick = { onNavigateTab(ApexDestination.PLUGINS_TOOLS) },
                                    testTag = "quick_touchpad_tile",
                                    modifier = Modifier.weight(1f)
                                )
                                AppleControlCenterTile(
                                    label = "Ring Device",
                                    caption = "Find My Peer",
                                    icon = Icons.Filled.NotificationsActive,
                                    tint = ApplePink,
                                    onClick = onRingDevice,
                                    testTag = "quick_ring_tile",
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }

                if (selectedDevice.pairState == PairState.PAIRED && selectedDevice.supportsMpris) {
                    item {
                        AppleNowPlayingPlatter(
                            device = selectedDevice,
                            onMediaAction = onMediaAction,
                            onSendPing = onSendPing
                        )
                    }
                }
            }

            // 6. Inset Grouped List — Paired Devices
            if (pairedDevices.isNotEmpty()) {
                item {
                    Column {
                        AppleSectionHeader(title = "My Paired Devices")
                        AppleGroupedSection {
                            pairedDevices.forEachIndexed { index, peer ->
                                AppleDeviceListRow(
                                    device = peer,
                                    isSelected = peer.deviceId == uiState.selectedDeviceId,
                                    onSelect = { onSelectDevice(peer.deviceId, null) },
                                    onActionClick = { onUnpair(peer.deviceId) }
                                )
                                if (index < pairedDevices.lastIndex) {
                                    AppleInsetDivider()
                                }
                            }
                        }
                    }
                }
            }

            // 7. Inset Grouped List — Discovered Unpaired Devices on Wi-Fi
            if (nearbyUnpaired.isNotEmpty()) {
                item {
                    Column {
                        AppleSectionHeader(title = "Other Devices on Wi-Fi")
                        AppleGroupedSection {
                            nearbyUnpaired.forEachIndexed { index, peer ->
                                AppleDeviceListRow(
                                    device = peer,
                                    isSelected = peer.deviceId == uiState.selectedDeviceId,
                                    onSelect = { onSelectDevice(peer.deviceId, null) },
                                    onActionClick = {
                                        if (peer.pairState == PairState.PAIR_REQUESTED_BY_PEER) {
                                            onAcceptPair(peer.deviceId)
                                        } else {
                                            onRequestPair(peer.deviceId)
                                        }
                                    }
                                )
                                if (index < nearbyUnpaired.lastIndex) {
                                    AppleInsetDivider()
                                }
                            }
                        }
                    }
                }
            }

            // 8. Collapsible Live Network Log
            if (protocolEvents.isNotEmpty()) {
                item {
                    AppleGroupedSection {
                        AppleGroupedRow(
                            title = "Network Activity",
                            subtitle = protocolEvents.first(),
                            leadingIcon = Icons.Filled.Info,
                            iconTint = Color(0xFF8E8E93),
                            trailingText = if (showDiagnostics) "Hide" else "Logs",
                            onClick = { showDiagnostics = !showDiagnostics }
                        )
                        AnimatedVisibility(visible = showDiagnostics) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 16.dp, end = 16.dp, bottom = 14.dp)
                            ) {
                                AppleInsetDivider()
                                Spacer(modifier = Modifier.height(8.dp))
                                protocolEvents.take(8).forEach { log ->
                                    Text(
                                        text = log,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(vertical = 2.dp)
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

@Composable
private fun AppleEmptyDiscoveryPlatter(
    isScanning: Boolean,
    localEndpoint: String,
    onScanWifi: () -> Unit,
    onConnectByIp: () -> Unit
) {
    AppleGroupedSection(modifier = Modifier.testTag("empty_lan_discovery_card")) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp, vertical = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(AppleBlue.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                if (isScanning) {
                    CircularProgressIndicator(
                        color = AppleBlue,
                        strokeWidth = 2.5.dp,
                        modifier = Modifier.size(30.dp)
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.Radar,
                        contentDescription = null,
                        tint = AppleBlue,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = "Looking for Devices on Wi-Fi",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "Open KDE Connect on your PC, Mac, or Linux desktop — or ApexConnect on another phone — connected to the same Wi-Fi network ($localEndpoint).",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(18.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onScanWifi,
                    shape = CircleShape,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.Radar, contentDescription = null, modifier = Modifier.size(17.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Scan Wi-Fi")
                }

                FilledTonalButton(
                    onClick = onConnectByIp,
                    shape = CircleShape,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(17.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Enter IP")
                }
            }
        }
    }
}

@Composable
private fun ActiveAppleDeviceHeroCard(
    device: PairedDeviceEntity,
    onShowSecurityInfo: () -> Unit,
    onRequestPair: () -> Unit,
    onAcceptPair: () -> Unit,
    onDeclineOrUnpair: () -> Unit
) {
    AppleGroupedSection {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = deviceIconForType(device.deviceType),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(14.dp))
                    Column {
                        Text(
                            text = device.deviceName,
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        val statusLabel = when {
                            device.pairState == PairState.PAIR_REQUESTED_BY_PEER ->
                                "Incoming Pair Request • ${device.ipAddress}"
                            device.pairState == PairState.PAIR_REQUESTED_BY_US ->
                                "Waiting for ${device.deviceName} to Accept…"
                            device.pairState != PairState.PAIRED ->
                                "Discovered on LAN • ${device.ipAddress}"
                            device.isReachable ->
                                "Connected • Mutual TLS (${device.ipAddress})"
                            else ->
                                "Offline • Last seen at ${device.ipAddress}"
                        }
                        Text(
                            text = statusLabel,
                            style = MaterialTheme.typography.bodySmall,
                            color = when {
                                device.pairState != PairState.PAIRED -> AppleOrange
                                device.isReachable -> AppleGreen
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }

                IconButton(onClick = onShowSecurityInfo) {
                    Icon(
                        imageVector = Icons.Filled.Info,
                        contentDescription = "Certificate & Security Details",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            if (device.pairState != PairState.PAIRED) {
                Spacer(modifier = Modifier.height(14.dp))
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "SHA-256 Verification Key",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = device.verificationCode.ifBlank { "Negotiating TLS…" },
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        when (device.pairState) {
                            PairState.PAIR_REQUESTED_BY_PEER -> {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    TextButton(onClick = onDeclineOrUnpair) {
                                        Text("Decline", color = AppleRed)
                                    }
                                    Button(
                                        onClick = onAcceptPair,
                                        shape = CircleShape,
                                        modifier = Modifier.testTag("accept_pair_banner_button")
                                    ) {
                                        Icon(Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Accept")
                                    }
                                }
                            }
                            PairState.PAIR_REQUESTED_BY_US -> {
                                FilledTonalButton(
                                    onClick = onRequestPair,
                                    shape = CircleShape,
                                    modifier = Modifier.testTag("resend_pair_banner_button")
                                ) {
                                    Icon(Icons.Filled.Schedule, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Resend")
                                }
                            }
                            PairState.UNPAIRED -> {
                                Button(
                                    onClick = onRequestPair,
                                    shape = CircleShape,
                                    modifier = Modifier.testTag("request_pair_banner_button")
                                ) {
                                    Icon(Icons.Filled.Link, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Pair")
                                }
                            }
                            PairState.PAIRED -> Unit
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppleWorkspaceWidgetCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    iconColor: Color,
    onClick: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 0.5.dp,
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .testTag(testTag)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                AppleSquircleIcon(icon = icon, tintColor = iconColor, sizeDp = 38)
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            }
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun AppleControlCenterTile(
    label: String,
    caption: String,
    icon: ImageVector,
    tint: Color,
    onClick: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 0.5.dp,
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .testTag(testTag)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(tint.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = tint,
                    modifier = Modifier.size(19.dp)
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = caption,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun AppleNowPlayingPlatter(
    device: PairedDeviceEntity,
    onMediaAction: (String) -> Unit,
    onSendPing: () -> Unit
) {
    Column {
        AppleSectionHeader(
            title = "Now Playing",
            trailingActionLabel = "Send Ping",
            onTrailingClick = onSendPing
        )
        AppleGroupedSection {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    AppleSquircleIcon(
                        icon = Icons.Filled.MusicNote,
                        tintColor = ApplePink,
                        sizeDp = 42
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = device.mediaTitle.ifBlank { "No Active Media" },
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val playerLabel = device.mediaArtist.ifBlank {
                            device.mediaPlayerName.ifBlank { device.deviceName }
                        }
                        Text(
                            text = "$playerLabel • Volume ${device.mediaVolume}%",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { onMediaAction("Previous") }) {
                        Icon(Icons.Filled.SkipPrevious, contentDescription = "Previous")
                    }
                    IconButton(
                        onClick = { onMediaAction("PlayPause") },
                        modifier = Modifier.testTag("hub_mpris_play_pause")
                    ) {
                        Icon(
                            imageVector = if (device.isMediaPlaying) Icons.Outlined.Pause else Icons.Filled.PlayArrow,
                            contentDescription = "Play or pause"
                        )
                    }
                    IconButton(onClick = { onMediaAction("Next") }) {
                        Icon(Icons.Filled.SkipNext, contentDescription = "Next")
                    }
                }
            }
        }
    }
}

@Composable
private fun AppleDeviceListRow(
    device: PairedDeviceEntity,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onActionClick: () -> Unit
) {
    val iconColor = when {
        device.pairState != PairState.PAIRED -> AppleOrange
        device.isReachable -> AppleBlue
        else -> Color(0xFF8E8E93)
    }

    AppleGroupedRow(
        title = device.deviceName,
        subtitle = buildString {
            append(
                when (device.pairState) {
                    PairState.PAIR_REQUESTED_BY_PEER -> "Accept Code ${device.verificationCode}"
                    PairState.PAIR_REQUESTED_BY_US -> "Waiting for Peer (${device.verificationCode})"
                    PairState.UNPAIRED -> "Available on ${device.ipAddress}"
                    PairState.PAIRED -> if (device.isReachable) "Connected • ${device.ipAddress}" else "Offline"
                }
            )
        },
        leadingIcon = deviceIconForType(device.deviceType),
        iconTint = iconColor,
        onClick = onSelect,
        testTag = "hub_device_card_${device.deviceId}",
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isSelected && device.pairState == PairState.PAIRED) {
                    Text(
                        text = "Active",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }

                when (device.pairState) {
                    PairState.PAIRED -> {
                        TextButton(
                            onClick = onActionClick,
                            modifier = Modifier.testTag("unpair_btn_${device.deviceId}")
                        ) {
                            Text("Unpair", color = AppleRed, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                    PairState.PAIR_REQUESTED_BY_PEER -> {
                        FilledTonalButton(
                            onClick = onActionClick,
                            shape = CircleShape,
                            modifier = Modifier.testTag("accept_pair_btn_${device.deviceId}")
                        ) {
                            Text("Accept")
                        }
                    }
                    PairState.PAIR_REQUESTED_BY_US -> {
                        FilledTonalButton(
                            onClick = onActionClick,
                            shape = CircleShape,
                            modifier = Modifier.testTag("resend_pair_btn_${device.deviceId}")
                        ) {
                            Text("Pending")
                        }
                    }
                    PairState.UNPAIRED -> {
                        FilledTonalButton(
                            onClick = onActionClick,
                            shape = CircleShape,
                            modifier = Modifier.testTag("pair_btn_${device.deviceId}")
                        ) {
                            Text("Pair")
                        }
                    }
                }
            }
        }
    )
}
