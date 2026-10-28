package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AddLink
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentPasteGo
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.local.PairState
import com.example.data.local.PairedDeviceEntity
import com.example.ui.ApexDestination
import com.example.ui.ApexUiState
import com.example.ui.components.deviceIconForType
import com.example.ui.theme.BreezeCyan
import com.example.ui.theme.BreezeTeal
import com.example.ui.theme.EmeraldSuccess

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
    onNavigateTab: (ApexDestination) -> Unit,
    onRingDevice: () -> Unit,
    onPushClipboard: () -> Unit,
    onSendPing: () -> Unit,
    onQuickShareFile: (Uri) -> Unit,
    onMediaAction: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var showAddByIpDialog by remember { mutableStateOf(false) }
    var customIpInput by remember { mutableStateOf("") }

    val quickShareLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) onQuickShareFile(uri)
    }

    val selectedDevice = devices.firstOrNull { it.deviceId == uiState.selectedDeviceId }
        ?: devices.firstOrNull()

    val pairedDevices = devices.filter { it.pairState == PairState.PAIRED }
    val discoveredDevices = devices.filter { it.pairState != PairState.PAIRED }

    if (showAddByIpDialog) {
        AlertDialog(
            onDismissRequest = { showAddByIpDialog = false },
            title = { Text(stringResource(R.string.action_add_ip)) },
            text = {
                Column {
                    Text(
                        text = "Probe a KDE Connect v8 node directly via unicast UDP/TCP port 1716 (useful across subnets, Tailscale, or WireGuard).",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = customIpInput,
                        onValueChange = { customIpInput = it },
                        label = { Text("IPv4 / Hostname (e.g. 192.168.1.50)") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("custom_ip_input")
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onScanLan(customIpInput)
                        customIpInput = ""
                        showAddByIpDialog = false
                    },
                    modifier = Modifier.testTag("confirm_add_ip_button")
                ) {
                    Text("Probe Node")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddByIpDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 720.dp)
                .testTag("hub_lazy_column"),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. Hero Local Node & LAN Discovery Banner
            item {
                Card(
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
                ) {
                    Box(modifier = Modifier.fillMaxWidth()) {
                        Image(
                            painter = painterResource(id = R.drawable.img_network_hero),
                            contentDescription = "Wireless encrypted P2P network illustration",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(168.dp)
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(168.dp)
                                .background(
                                    Brush.verticalGradient(
                                        colors = listOf(
                                            Color(0x66090E1A),
                                            Color(0xE6090E1A),
                                            Color(0xFF0F172A)
                                        )
                                    )
                                )
                        )
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(18.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(50),
                                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.9f)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Security,
                                            contentDescription = null,
                                            tint = BreezeCyan,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = stringResource(R.string.app_tagline),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(22.dp))

                            Text(
                                text = uiState.localNodeName,
                                style = MaterialTheme.typography.headlineMedium,
                                color = Color.White
                            )
                            Text(
                                text = "SHA-256 Key: ${uiState.localFingerprint}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Spacer(modifier = Modifier.height(14.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Button(
                                    onClick = { onScanLan(null) },
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("scan_lan_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Radar,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = if (uiState.isScanningLan) "Scanning…" else "Scan LAN",
                                        maxLines = 1
                                    )
                                }

                                OutlinedButton(
                                    onClick = { showAddByIpDialog = true },
                                    modifier = Modifier.testTag("add_by_ip_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.AddLink,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Add IP")
                                }
                            }
                        }
                    }
                }
            }

            // 2. Unified Feature Spotlight + Core KDE Connect Actions for Active Device
            if (selectedDevice != null) {
                item {
                    ActiveDeviceCommandDeck(
                        device = selectedDevice,
                        onOpenRemoteFiles = { onNavigateTab(ApexDestination.REMOTE_FILES) },
                        onOpenDeviceSpace = { onNavigateTab(ApexDestination.DEVICE_SPACE) },
                        onOpenPlugins = { onNavigateTab(ApexDestination.PLUGINS_TOOLS) },
                        onShareFile = { quickShareLauncher.launch(arrayOf("*/*")) },
                        onPushClipboard = onPushClipboard,
                        onRingDevice = onRingDevice,
                        onSendPing = onSendPing,
                        onMediaAction = onMediaAction,
                        onRequestPair = { onRequestPair(selectedDevice.deviceId) },
                        onAcceptPair = { onAcceptPair(selectedDevice.deviceId) }
                    )
                }
            }

            // 3. Discovered / Pending Pairing Section
            if (discoveredDevices.isNotEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.section_available_devices),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
                items(discoveredDevices, key = { it.deviceId }) { peer ->
                    PairingDeviceCard(
                        device = peer,
                        isSelected = peer.deviceId == uiState.selectedDeviceId,
                        onSelect = { onSelectDevice(peer.deviceId, null) },
                        onRequestPair = { onRequestPair(peer.deviceId) },
                        onAcceptPair = { onAcceptPair(peer.deviceId) },
                        onUnpair = { onUnpair(peer.deviceId) }
                    )
                }
            }

            // 4. Trusted Paired Devices Section
            item {
                Text(
                    text = stringResource(R.string.section_paired_devices),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            items(pairedDevices, key = { it.deviceId }) { peer ->
                PairingDeviceCard(
                    device = peer,
                    isSelected = peer.deviceId == uiState.selectedDeviceId,
                    onSelect = { onSelectDevice(peer.deviceId, null) },
                    onRequestPair = { onRequestPair(peer.deviceId) },
                    onAcceptPair = { onAcceptPair(peer.deviceId) },
                    onUnpair = { onUnpair(peer.deviceId) }
                )
            }

            // 5. Live KDE Connect v8 Wire Protocol Inspector
            if (protocolEvents.isNotEmpty()) {
                item {
                    Card(
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Filled.Terminal,
                                    contentDescription = null,
                                    tint = BreezeTeal,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "TLS Packet & Sync Telemetry",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            protocolEvents.take(5).forEach { line ->
                                Text(
                                    text = line,
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

@Composable
private fun ActiveDeviceCommandDeck(
    device: PairedDeviceEntity,
    onOpenRemoteFiles: () -> Unit,
    onOpenDeviceSpace: () -> Unit,
    onOpenPlugins: () -> Unit,
    onShareFile: () -> Unit,
    onPushClipboard: () -> Unit,
    onRingDevice: () -> Unit,
    onSendPing: () -> Unit,
    onMediaAction: (String) -> Unit,
    onRequestPair: () -> Unit,
    onAcceptPair: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "Active Node: ${device.deviceName}",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground
        )

        if (device.pairState != PairState.PAIRED) {
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = if (device.pairState == PairState.PAIR_REQUESTED_BY_PEER) {
                            "Confirm TLS Verification Key"
                        } else {
                            "Device Unpaired — Establish Encrypted Link"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Verification Code: ${device.verificationCode}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    if (device.pairState == PairState.PAIR_REQUESTED_BY_PEER || device.pairState == PairState.PAIR_REQUESTED_BY_US) {
                        Button(
                            onClick = onAcceptPair,
                            modifier = Modifier.testTag("accept_pair_banner_button")
                        ) {
                            Icon(Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.action_accept_pair))
                        }
                    } else {
                        Button(
                            onClick = onRequestPair,
                            modifier = Modifier.testTag("request_pair_banner_button")
                        ) {
                            Icon(Icons.Filled.Link, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.action_pair))
                        }
                    }
                }
            }
        }

        // Primary Hero Feature Cards right on the main screen:
        // 1) Remote File Browser  2) Persistent Device Chat & Notes
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            PrimaryFeatureSpotlightCard(
                title = stringResource(R.string.quick_remote_files),
                subtitle = if (device.supportsRemoteFs) "SFTP / VFS Explorer" else "Unsupported on OS",
                icon = Icons.Filled.FolderOpen,
                accent = BreezeCyan,
                onClick = onOpenRemoteFiles,
                testTag = "spotlight_remote_files_card",
                modifier = Modifier.weight(1f)
            )
            PrimaryFeatureSpotlightCard(
                title = stringResource(R.string.quick_device_space),
                subtitle = "Persistent Chat & Notes",
                icon = Icons.AutoMirrored.Filled.Chat,
                accent = BreezeTeal,
                onClick = onOpenDeviceSpace,
                testTag = "spotlight_device_space_card",
                modifier = Modifier.weight(1f)
            )
        }

        // Core KDE Connect Feature Grid on Main Screen
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            QuickPluginTile(
                label = stringResource(R.string.quick_send_file),
                icon = Icons.Filled.UploadFile,
                onClick = onShareFile,
                testTag = "quick_share_file_tile",
                modifier = Modifier.weight(1f)
            )
            QuickPluginTile(
                label = stringResource(R.string.quick_clipboard),
                icon = Icons.Filled.ContentPasteGo,
                onClick = onPushClipboard,
                testTag = "quick_clipboard_tile",
                modifier = Modifier.weight(1f)
            )
            QuickPluginTile(
                label = stringResource(R.string.quick_find_device),
                icon = Icons.Filled.NotificationsActive,
                onClick = onRingDevice,
                testTag = "quick_ring_tile",
                modifier = Modifier.weight(1f)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            QuickPluginTile(
                label = stringResource(R.string.quick_touchpad),
                icon = Icons.Filled.Mouse,
                onClick = onOpenPlugins,
                testTag = "quick_touchpad_tile",
                modifier = Modifier.weight(1f)
            )
            QuickPluginTile(
                label = stringResource(R.string.quick_run_cmd),
                icon = Icons.Filled.Terminal,
                onClick = onOpenPlugins,
                testTag = "quick_commands_tile",
                modifier = Modifier.weight(1f)
            )
            QuickPluginTile(
                label = "Send Ping",
                icon = Icons.AutoMirrored.Filled.Send,
                onClick = onSendPing,
                testTag = "quick_ping_tile",
                modifier = Modifier.weight(1f)
            )
        }

        // Inline MPRIS Now Playing Bar (classic KDE Connect home widget)
        if (device.pairState == PairState.PAIRED && device.supportsMpris) {
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.MusicNote,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = device.mediaTitle.ifBlank { "No Media Playing" },
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${device.mediaArtist.ifBlank { "MPRIS Controller" }} • Vol ${device.mediaVolume}%",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { onMediaAction("Previous") }) {
                            Icon(Icons.Filled.SkipPrevious, contentDescription = "Previous track")
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
                            Icon(Icons.Filled.SkipNext, contentDescription = "Next track")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PrimaryFeatureSpotlightCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    accent: Color,
    onClick: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .border(1.dp, accent.copy(alpha = 0.45f), RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .testTag(testTag)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(accent.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = accent,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
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
private fun QuickPluginTile(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .testTag(testTag)
    ) {
        Column(
            modifier = Modifier.padding(vertical = 12.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun PairingDeviceCard(
    device: PairedDeviceEntity,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onRequestPair: () -> Unit,
    onAcceptPair: () -> Unit,
    onUnpair: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.surfaceContainerHighest
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            }
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onSelect)
            .testTag("hub_device_card_${device.deviceId}")
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = deviceIconForType(device.deviceType),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = device.deviceName,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "${device.platformOs} • ${device.ipAddress}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (device.pairState == PairState.PAIRED) {
                    TextButton(
                        onClick = onUnpair,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.testTag("unpair_btn_${device.deviceId}")
                    ) {
                        Icon(Icons.Filled.LinkOff, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Unpair")
                    }
                } else if (device.pairState == PairState.PAIR_REQUESTED_BY_PEER || device.pairState == PairState.PAIR_REQUESTED_BY_US) {
                    FilledTonalButton(
                        onClick = onAcceptPair,
                        modifier = Modifier.testTag("accept_pair_btn_${device.deviceId}")
                    ) {
                        Text("Confirm ${device.verificationCode}")
                    }
                } else {
                    FilledTonalButton(
                        onClick = onRequestPair,
                        modifier = Modifier.testTag("pair_btn_${device.deviceId}")
                    ) {
                        Icon(Icons.Filled.Link, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Pair")
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "TLS Key: ${device.verificationCode} (${device.certificateFingerprint})",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = when {
                        device.pairState != PairState.PAIRED -> "UNPAIRED"
                        device.isReachable -> "ONLINE"
                        else -> "OFFLINE"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = when {
                        device.pairState != PairState.PAIRED -> MaterialTheme.colorScheme.tertiary
                        device.isReachable -> EmeraldSuccess
                        else -> MaterialTheme.colorScheme.outline
                    }
                )
            }
        }
    }
}
