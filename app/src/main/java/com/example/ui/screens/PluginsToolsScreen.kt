package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.ContentPasteGo
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.network.RemoteCommandItem
import com.example.data.local.PairState
import com.example.data.local.PairedDeviceEntity
import com.example.ui.components.AppleGroupedRow
import com.example.ui.components.AppleGroupedSection
import com.example.ui.components.AppleInsetDivider
import com.example.ui.components.AppleSectionHeader
import com.example.ui.components.AppleSquircleIcon
import com.example.ui.theme.AppleIndigo
import com.example.ui.theme.AppleOrange
import com.example.ui.theme.ApplePink
import com.example.ui.theme.AppleTeal
import kotlin.math.roundToInt

@Composable
fun PluginsToolsScreen(
    selectedDevice: PairedDeviceEntity?,
    remoteCommands: List<RemoteCommandItem>,
    onTouchpadMove: (Float, Float) -> Unit,
    onTouchpadScroll: (Float) -> Unit,
    onTouchpadClick: (Boolean) -> Unit,
    onSendRemoteKey: (String) -> Unit,
    onSendRemoteSpecialKey: (Int, String) -> Unit,
    onMediaAction: (String, Int?) -> Unit,
    onRefreshTelemetry: () -> Unit,
    onPushClipboard: (String?) -> Unit,
    onExecuteCommand: (RemoteCommandItem) -> Unit,
    onRingDevice: () -> Unit,
    onSendPing: (String) -> Unit,
    onBackToHub: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler {
        onBackToHub()
    }

    var cursorAccumX by remember { mutableFloatStateOf(0f) }
    var cursorAccumY by remember { mutableFloatStateOf(0f) }
    var remoteKeyboardText by remember { mutableStateOf("") }
    var clipboardCustomText by remember { mutableStateOf("") }
    var customPingMessage by remember { mutableStateOf("") }

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 680.dp)
                .testTag("plugins_tools_screen"),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Controls",
                            style = MaterialTheme.typography.displaySmall,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            text = selectedDevice?.let { "${it.deviceName} • Remote Continuity" }
                                ?: "Select a paired device",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (selectedDevice?.pairState == PairState.PAIRED) {
                        IconButton(
                            onClick = onRefreshTelemetry,
                            modifier = Modifier.testTag("refresh_telemetry_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = "Refresh players and shortcuts",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }

            if (selectedDevice == null || selectedDevice.pairState != PairState.PAIRED) {
                item {
                    AppleGroupedSection(modifier = Modifier.testTag("tools_no_device_card")) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(26.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            AppleSquircleIcon(
                                icon = Icons.Filled.Mouse,
                                tintColor = AppleOrange,
                                sizeDp = 46
                            )
                            Spacer(modifier = Modifier.height(14.dp))
                            Text(
                                text = "Pair a Device to Unlock Controls",
                                style = MaterialTheme.typography.titleLarge
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Connect to a KDE Connect computer or ApexConnect phone on Wi-Fi to use Magic Trackpad, Media Controls, Universal Clipboard, and Remote Shortcuts.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(
                                onClick = onBackToHub,
                                shape = CircleShape,
                                modifier = Modifier.testTag("tools_open_devices_button")
                            ) {
                                Text("Open Devices")
                            }
                        }
                    }
                }
            } else {
                // 1. Magic Trackpad & Remote Keyboard Platter
                item {
                    Column {
                        AppleSectionHeader(title = "Magic Trackpad & Keyboard")
                        AppleGroupedSection {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(190.dp)
                                        .clip(RoundedCornerShape(18.dp))
                                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                                        .border(
                                            1.dp,
                                            MaterialTheme.colorScheme.outlineVariant,
                                            RoundedCornerShape(18.dp)
                                        )
                                        .pointerInput(selectedDevice.deviceId) {
                                            detectDragGestures { change, dragAmount ->
                                                change.consume()
                                                cursorAccumX += dragAmount.x
                                                cursorAccumY += dragAmount.y
                                                onTouchpadMove(dragAmount.x, dragAmount.y)
                                            }
                                        }
                                        .pointerInput(selectedDevice.deviceId) {
                                            detectTapGestures(
                                                onTap = { onTouchpadClick(false) },
                                                onLongPress = { onTouchpadClick(true) }
                                            )
                                        }
                                        .testTag("remote_touchpad_surface"),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        AppleSquircleIcon(
                                            icon = Icons.Filled.Mouse,
                                            tintColor = AppleOrange,
                                            sizeDp = 36
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            text = "Glide to Move Cursor • Tap to Click",
                                            style = MaterialTheme.typography.titleSmall,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = "Offset (${cursorAccumX.roundToInt()}, ${cursorAccumY.roundToInt()})",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(12.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = { onTouchpadClick(false) },
                                        shape = CircleShape,
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("touchpad_left_click")
                                    ) {
                                        Text(stringResource(R.string.btn_left_click))
                                    }
                                    OutlinedButton(
                                        onClick = { onTouchpadClick(true) },
                                        shape = CircleShape,
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("touchpad_right_click")
                                    ) {
                                        Text(stringResource(R.string.btn_right_click))
                                    }
                                    FilledTonalButton(
                                        onClick = { onTouchpadScroll(-120f) },
                                        shape = CircleShape
                                    ) {
                                        Text("↑")
                                    }
                                    FilledTonalButton(
                                        onClick = { onTouchpadScroll(120f) },
                                        shape = CircleShape
                                    ) {
                                        Text("↓")
                                    }
                                }

                                Spacer(modifier = Modifier.height(12.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    OutlinedTextField(
                                        value = remoteKeyboardText,
                                        onValueChange = { remoteKeyboardText = it },
                                        placeholder = { Text("Type text on remote screen…") },
                                        leadingIcon = { Icon(Icons.Filled.Keyboard, contentDescription = null) },
                                        singleLine = true,
                                        shape = RoundedCornerShape(14.dp),
                                        modifier = Modifier.weight(1f)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    IconButton(
                                        onClick = {
                                            if (remoteKeyboardText.isNotBlank()) {
                                                onSendRemoteKey(remoteKeyboardText)
                                                remoteKeyboardText = ""
                                            }
                                        },
                                        enabled = remoteKeyboardText.isNotBlank()
                                    ) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.Send,
                                            contentDescription = "Send",
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(10.dp))

                                // KDE Connect Special Keys Row (Backspace=1, Tab=2, Enter=12, Esc=14)
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    FilledTonalButton(
                                        onClick = { onSendRemoteSpecialKey(14, "Esc") },
                                        shape = CircleShape,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("Esc")
                                    }
                                    FilledTonalButton(
                                        onClick = { onSendRemoteSpecialKey(2, "Tab") },
                                        shape = CircleShape,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("Tab")
                                    }
                                    FilledTonalButton(
                                        onClick = { onSendRemoteSpecialKey(1, "Backspace") },
                                        shape = CircleShape,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("⌫")
                                    }
                                    Button(
                                        onClick = { onSendRemoteSpecialKey(12, "Enter") },
                                        shape = CircleShape,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("Enter")
                                    }
                                }
                            }
                        }
                    }
                }

                // 2. Apple Control Center Media Platter
                item {
                    var sliderVolume by remember(selectedDevice.mediaVolume) {
                        mutableFloatStateOf(selectedDevice.mediaVolume.toFloat())
                    }

                    Column {
                        AppleSectionHeader(title = "Media & Volume")
                        AppleGroupedSection {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    AppleSquircleIcon(
                                        icon = Icons.Filled.MusicNote,
                                        tintColor = ApplePink,
                                        sizeDp = 42
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        Text(
                                            text = selectedDevice.mediaTitle.ifBlank {
                                                selectedDevice.mediaPlayerName.ifBlank { "Ready for Media" }
                                            },
                                            style = MaterialTheme.typography.titleMedium
                                        )
                                        Text(
                                            text = selectedDevice.mediaArtist.ifBlank { selectedDevice.deviceName },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(14.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceEvenly,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    FilledTonalButton(
                                        onClick = { onMediaAction("Previous", null) },
                                        shape = CircleShape
                                    ) {
                                        Icon(Icons.Filled.SkipPrevious, contentDescription = "Previous")
                                    }
                                    Button(
                                        onClick = { onMediaAction("PlayPause", null) },
                                        shape = CircleShape,
                                        modifier = Modifier.testTag("tools_mpris_play_pause")
                                    ) {
                                        Icon(
                                            imageVector = if (selectedDevice.isMediaPlaying) Icons.Outlined.Pause else Icons.Filled.PlayArrow,
                                            contentDescription = "Play or pause"
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(if (selectedDevice.isMediaPlaying) "Pause" else "Play")
                                    }
                                    FilledTonalButton(
                                        onClick = { onMediaAction("Next", null) },
                                        shape = CircleShape
                                    ) {
                                        Icon(Icons.Filled.SkipNext, contentDescription = "Next")
                                    }
                                }

                                Spacer(modifier = Modifier.height(10.dp))

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                                        contentDescription = "Volume",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Slider(
                                        value = sliderVolume,
                                        onValueChange = { sliderVolume = it },
                                        onValueChangeFinished = {
                                            onMediaAction("Volume", sliderVolume.roundToInt())
                                        },
                                        valueRange = 0f..100f,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "${sliderVolume.roundToInt()}%",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }

                // 3. Universal Clipboard Platter
                item {
                    Column {
                        AppleSectionHeader(title = "Universal Clipboard")
                        AppleGroupedSection {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    AppleSquircleIcon(
                                        icon = Icons.Filled.ContentPasteGo,
                                        tintColor = AppleTeal,
                                        sizeDp = 34
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = selectedDevice.lastClipboardSnippet.ifBlank { "Push text or links instantaneously" },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1
                                    )
                                }
                                Spacer(modifier = Modifier.height(12.dp))
                                OutlinedTextField(
                                    value = clipboardCustomText,
                                    onValueChange = { clipboardCustomText = it },
                                    placeholder = { Text("Paste text or leave blank to push system clipboard…") },
                                    singleLine = true,
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("clipboard_push_input")
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                Button(
                                    onClick = {
                                        onPushClipboard(clipboardCustomText.ifBlank { null })
                                        clipboardCustomText = ""
                                    },
                                    shape = CircleShape,
                                    modifier = Modifier.testTag("push_clipboard_btn")
                                ) {
                                    Text("Send to Peer Clipboard")
                                }
                            }
                        }
                    }
                }

                // 4. Remote Shortcuts (RunCommand) Inset Grouped List
                if (remoteCommands.isNotEmpty()) {
                    item {
                        Column {
                            AppleSectionHeader(title = "Remote Shortcuts")
                            AppleGroupedSection {
                                remoteCommands.forEachIndexed { index, cmd ->
                                    AppleGroupedRow(
                                        title = cmd.name,
                                        subtitle = cmd.command,
                                        leadingIcon = Icons.Filled.Terminal,
                                        iconTint = AppleIndigo,
                                        trailingText = "Run",
                                        onClick = { onExecuteCommand(cmd) },
                                        testTag = "exec_cmd_${cmd.key}"
                                    )
                                    if (index < remoteCommands.lastIndex) {
                                        AppleInsetDivider()
                                    }
                                }
                            }
                        }
                    }
                }

                // 5. Find My Peer & Custom Ping Platter
                item {
                    Column {
                        AppleSectionHeader(title = "Find My & Alert")
                        AppleGroupedSection {
                            Column(modifier = Modifier.padding(16.dp)) {
                                OutlinedTextField(
                                    value = customPingMessage,
                                    onValueChange = { customPingMessage = it },
                                    placeholder = { Text("Custom alert banner message…") },
                                    singleLine = true,
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Button(
                                        onClick = {
                                            onSendPing(customPingMessage)
                                            customPingMessage = ""
                                        },
                                        shape = CircleShape,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Ping")
                                    }
                                    FilledTonalButton(
                                        onClick = onRingDevice,
                                        shape = CircleShape,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(Icons.Filled.NotificationsActive, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Play Sound")
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
