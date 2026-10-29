package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.ApexDestination
import com.example.ui.ApexViewModel
import com.example.ui.components.ActiveDeviceSelectorStrip
import com.example.ui.components.ActiveTransferBanner
import com.example.ui.screens.DeviceSpaceScreen
import com.example.ui.screens.HubScreen
import com.example.ui.screens.PluginsToolsScreen
import com.example.ui.screens.RemoteFileBrowserScreen
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                val vm: ApexViewModel = viewModel(factory = ApexViewModel.Factory(application))
                ApexConnectApp(viewModel = vm)
            }
        }
    }
}

private data class NavTabSpec(
    val destination: ApexDestination,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String
)

@Composable
fun ApexConnectApp(viewModel: ApexViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    val messages by viewModel.messagesForSelectedDevice.collectAsStateWithLifecycle()
    val notes by viewModel.notesForSelectedDevice.collectAsStateWithLifecycle()
    val transfers by viewModel.transfersForSelectedDevice.collectAsStateWithLifecycle()
    val activeTransfer by viewModel.activeTransfer.collectAsStateWithLifecycle()
    val protocolEvents by viewModel.protocolEvents.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(viewModel) {
        viewModel.snackbarEvents.collect { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    val selectedDevice = devices.firstOrNull { it.deviceId == uiState.selectedDeviceId }
        ?: devices.firstOrNull()

    val navTabs = remember {
        listOf(
            NavTabSpec(
                destination = ApexDestination.HUB,
                label = "Devices",
                selectedIcon = Icons.Filled.Devices,
                unselectedIcon = Icons.Outlined.Devices,
                testTag = "nav_tab_hub"
            ),
            NavTabSpec(
                destination = ApexDestination.REMOTE_FILES,
                label = "Files",
                selectedIcon = Icons.Filled.Folder,
                unselectedIcon = Icons.Outlined.Folder,
                testTag = "nav_tab_files"
            ),
            NavTabSpec(
                destination = ApexDestination.DEVICE_SPACE,
                label = "Space",
                selectedIcon = Icons.AutoMirrored.Filled.Chat,
                unselectedIcon = Icons.AutoMirrored.Outlined.Chat,
                testTag = "nav_tab_space"
            ),
            NavTabSpec(
                destination = ApexDestination.PLUGINS_TOOLS,
                label = "Controls",
                selectedIcon = Icons.Filled.Tune,
                unselectedIcon = Icons.Outlined.Tune,
                testTag = "nav_tab_tools"
            )
        )
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useNavigationRail = maxWidth >= 600.dp

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            contentWindowInsets = WindowInsets.safeDrawing,
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                if (!useNavigationRail) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        HorizontalDivider(
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
                        )
                        // Clean iOS TabBar aesthetic: crisp surface, no heavy oval indicator, pure Apple Blue active tint
                        NavigationBar(
                            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                            tonalElevation = 0.dp
                        ) {
                            navTabs.forEach { tab ->
                                val selected = uiState.currentTab == tab.destination
                                NavigationBarItem(
                                    selected = selected,
                                    onClick = { viewModel.selectTab(tab.destination) },
                                    icon = {
                                        Icon(
                                            imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                            contentDescription = tab.label
                                        )
                                    },
                                    label = {
                                        Text(
                                            text = tab.label,
                                            style = MaterialTheme.typography.labelMedium
                                        )
                                    },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = MaterialTheme.colorScheme.primary,
                                        selectedTextColor = MaterialTheme.colorScheme.primary,
                                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        indicatorColor = Color.Transparent
                                    ),
                                    modifier = Modifier.testTag(tab.testTag)
                                )
                            }
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (useNavigationRail) {
                    NavigationRail(
                        modifier = Modifier.fillMaxHeight(),
                        containerColor = MaterialTheme.colorScheme.surface
                    ) {
                        navTabs.forEach { tab ->
                            val selected = uiState.currentTab == tab.destination
                            NavigationRailItem(
                                selected = selected,
                                onClick = { viewModel.selectTab(tab.destination) },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                        contentDescription = tab.label
                                    )
                                },
                                label = { Text(tab.label) },
                                modifier = Modifier.testTag(tab.testTag)
                            )
                        }
                    }
                }

                Column(modifier = Modifier.fillMaxSize()) {
                    // Compact Dynamic-Island-inspired top device switcher capsule
                    ActiveDeviceSelectorStrip(
                        devices = devices,
                        selectedDeviceId = uiState.selectedDeviceId,
                        onSelectDevice = { id -> viewModel.selectDevice(id) },
                        onToggleReachability = { id -> viewModel.togglePeerReachability(id) }
                    )

                    // AirDrop-style transfer progress platter
                    ActiveTransferBanner(
                        progress = activeTransfer,
                        onCancel = { viewModel.cancelActiveTransfer() }
                    )

                    when (uiState.currentTab) {
                        ApexDestination.HUB -> {
                            HubScreen(
                                uiState = uiState,
                                devices = devices,
                                protocolEvents = protocolEvents,
                                onSelectDevice = { id, dest -> viewModel.selectDevice(id, dest) },
                                onRequestPair = { id -> viewModel.requestPairing(id) },
                                onAcceptPair = { id -> viewModel.acceptPairing(id) },
                                onUnpair = { id -> viewModel.unpairDevice(id) },
                                onScanLan = { ip -> viewModel.scanLanDevices(ip) },
                                onNavigateTab = { dest -> viewModel.selectTab(dest) },
                                onRingDevice = { viewModel.ringPairedDevice() },
                                onPushClipboard = { viewModel.pushCurrentClipboardOrText(null) },
                                onSendPing = { viewModel.sendPing() },
                                onQuickShareFile = { uri -> viewModel.uploadFileToCurrentRemoteDir(uri) },
                                onMediaAction = { act -> viewModel.sendMediaCommand(act) },
                                modifier = Modifier.weight(1f)
                            )
                        }

                        ApexDestination.REMOTE_FILES -> {
                            RemoteFileBrowserScreen(
                                selectedDevice = selectedDevice,
                                currentPath = uiState.currentRemotePath,
                                directoryState = uiState.remoteDirectoryState,
                                recentTransfers = transfers,
                                onNavigateToDirectory = { path -> viewModel.refreshRemoteDirectory(path) },
                                onNavigateUp = { viewModel.navigateUpDirectory() },
                                onRefresh = { viewModel.refreshRemoteDirectory() },
                                onDownloadFileToSaf = { entry, uri -> viewModel.downloadRemoteFileToSaf(entry, uri) },
                                onDownloadAndOpenFile = { ctx, entry -> viewModel.downloadAndOpenRemoteFile(ctx, entry) },
                                onUploadFileToCurrentDir = { uri -> viewModel.uploadFileToCurrentRemoteDir(uri) },
                                onRequestPair = { id -> viewModel.requestPairing(id) },
                                onBackToHub = { viewModel.selectTab(ApexDestination.HUB) },
                                modifier = Modifier.weight(1f)
                            )
                        }

                        ApexDestination.DEVICE_SPACE -> {
                            DeviceSpaceScreen(
                                selectedDevice = selectedDevice,
                                activeSubTab = uiState.spaceSubTab,
                                messages = messages,
                                notes = notes,
                                onSelectSubTab = { sub -> viewModel.selectSpaceSubTab(sub) },
                                onSendTextMessage = { txt -> viewModel.sendChatMessage(txt) },
                                onSendImageMessage = { cap, uri -> viewModel.sendImageMessage(cap, uri) },
                                onDeleteMessage = { msg -> viewModel.deleteMessage(msg) },
                                onClearHistory = { viewModel.clearHistoryForSelectedDevice() },
                                onOpenAttachment = { ctx, file, mime ->
                                    viewModel.openLocalFileWithIntent(ctx, file, mime)
                                },
                                onSaveNote = { uuid, title, body, pinned ->
                                    viewModel.savePersistentNote(uuid, title, body, pinned)
                                },
                                onToggleNotePin = { note -> viewModel.toggleNotePin(note) },
                                onDeleteNote = { note -> viewModel.deleteNote(note) },
                                onCopyNote = { title, body -> viewModel.copyToLocalClipboard(title, body) },
                                onRetrySync = { viewModel.retrySyncForSelectedDevice() },
                                onRequestPair = { id -> viewModel.requestPairing(id) },
                                onBackToHub = { viewModel.selectTab(ApexDestination.HUB) },
                                modifier = Modifier.weight(1f)
                            )
                        }

                        ApexDestination.PLUGINS_TOOLS -> {
                            PluginsToolsScreen(
                                selectedDevice = selectedDevice,
                                remoteCommands = viewModel.getRemoteCommands(selectedDevice),
                                onTouchpadMove = { dx, dy -> viewModel.sendTouchpadDelta(dx, dy) },
                                onTouchpadClick = { right -> viewModel.sendTouchpadClick(right) },
                                onSendRemoteKey = { key -> viewModel.sendRemoteKey(key) },
                                onMediaAction = { act, vol -> viewModel.sendMediaCommand(act, vol) },
                                onPushClipboard = { txt -> viewModel.pushCurrentClipboardOrText(txt) },
                                onExecuteCommand = { cmd -> viewModel.executeRemoteCommand(cmd) },
                                onRingDevice = { viewModel.ringPairedDevice() },
                                onSendPing = { msg -> viewModel.sendPing(msg) },
                                onBackToHub = { viewModel.selectTab(ApexDestination.HUB) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }
    }
}
