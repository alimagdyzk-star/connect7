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
import androidx.compose.material.icons.filled.PlayCircleFilled
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
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
import com.example.ui.theme.BreezeCyan
import com.example.ui.theme.BreezeTeal
import kotlin.math.roundToInt

@Composable
fun PluginsToolsScreen(
    selectedDevice: PairedDeviceEntity?,
    remoteCommands: List<RemoteCommandItem>,
    onTouchpadMove: (Float, Float) -> Unit,
    onTouchpadClick: (Boolean) -> Unit,
    onSendRemoteKey: (String) -> Unit,
    onMediaAction: (String, Int?) -> Unit,
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
                .widthIn(max = 760.dp)
                .testTag("plugins_tools_screen"),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Text(
                    text = stringResource(R.string.tools_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = selectedDevice?.let { "Controlling ${it.deviceName} (${it.ipAddress})" }
                        ?: "Select a paired KDE Connect device",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (selectedDevice == null || selectedDevice.pairState != PairState.PAIRED) {
                item {
                    Card(
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Pair and connect to a device first to use Remote Input, MPRIS Media, Clipboard, and RunCommand plugins.",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(20.dp)
                        )
                    }
                }
            } else {
                // 1. Remote Touchpad & Keyboard (`kdeconnect.mousepad.request`)
                item {
                    Card(
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Mouse, contentDescription = null, tint = BreezeCyan)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = stringResource(R.string.tool_touchpad_title),
                                    style = MaterialTheme.typography.titleMedium
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = stringResource(R.string.tool_touchpad_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(12.dp))

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(190.dp)
                                    .clip(RoundedCornerShape(18.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                                    .border(
                                        1.dp,
                                        BreezeCyan.copy(alpha = 0.4f),
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
                                    Text(
                                        text = "Interactive Trackpad Surface",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = "Δ(${cursorAccumX.roundToInt()}, ${cursorAccumY.roundToInt()}) px",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = BreezeCyan
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                OutlinedButton(
                                    onClick = { onTouchpadClick(false) },
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("touchpad_left_click")
                                ) {
                                    Text(stringResource(R.string.btn_left_click))
                                }
                                OutlinedButton(
                                    onClick = { onTouchpadClick(true) },
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("touchpad_right_click")
                                ) {
                                    Text(stringResource(R.string.btn_right_click))
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
                                    label = { Text("Remote Keyboard Input") },
                                    leadingIcon = { Icon(Icons.Filled.Keyboard, contentDescription = null) },
                                    singleLine = true,
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
                                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send keystrokes")
                                }
                            }
                        }
                    }
                }

                // 2. MPRIS Media Controller (`kdeconnect.mpris.request`)
                item {
                    var sliderVolume by remember(selectedDevice.mediaVolume) {
                        mutableFloatStateOf(selectedDevice.mediaVolume.toFloat())
                    }

                    Card(
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.MusicNote, contentDescription = null, tint = BreezeTeal)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = stringResource(R.string.tool_media_title),
                                    style = MaterialTheme.typography.titleMedium
                                )
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = selectedDevice.mediaTitle.ifBlank { "Idle Media Session" },
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                text = selectedDevice.mediaArtist.ifBlank { "Plasma / MPRIS2" },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                FilledTonalButton(onClick = { onMediaAction("Previous", null) }) {
                                    Icon(Icons.Filled.SkipPrevious, contentDescription = "Previous")
                                }
                                Button(
                                    onClick = { onMediaAction("PlayPause", null) },
                                    modifier = Modifier.testTag("tools_mpris_play_pause")
                                ) {
                                    Icon(
                                        imageVector = if (selectedDevice.isMediaPlaying) Icons.Outlined.Pause else Icons.Filled.PlayArrow,
                                        contentDescription = "Play or pause"
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(if (selectedDevice.isMediaPlaying) "Pause" else "Play")
                                }
                                FilledTonalButton(onClick = { onMediaAction("Next", null) }) {
                                    Icon(Icons.Filled.SkipNext, contentDescription = "Next")
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

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
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        }
                    }
                }

                // 3. Clipboard Bridge (`kdeconnect.clipboard`)
                item {
                    Card(
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.ContentPasteGo, contentDescription = null, tint = BreezeCyan)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = stringResource(R.string.tool_clipboard_title),
                                    style = MaterialTheme.typography.titleMedium
                                )
                            }
                            if (selectedDevice.lastClipboardSnippet.isNotBlank()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = "Last synced: ${selectedDevice.lastClipboardSnippet}",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(10.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                            OutlinedTextField(
                                value = clipboardCustomText,
                                onValueChange = { clipboardCustomText = it },
                                label = { Text("Text or URL to push to remote clipboard") },
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("clipboard_push_input")
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(
                                    onClick = {
                                        onPushClipboard(clipboardCustomText.ifBlank { null })
                                        clipboardCustomText = ""
                                    },
                                    modifier = Modifier.testTag("push_clipboard_btn")
                                ) {
                                    Text("Push Clipboard")
                                }
                            }
                        }
                    }
                }

                // 4. Remote Commands (`kdeconnect.runcommand.request`)
                if (remoteCommands.isNotEmpty()) {
                    item {
                        Card(
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.Terminal, contentDescription = null, tint = BreezeTeal)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = stringResource(R.string.tool_commands_title),
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                }
                                Spacer(modifier = Modifier.height(10.dp))
                                remoteCommands.forEach { cmd ->
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 12.dp, vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(cmd.name, style = MaterialTheme.typography.titleSmall)
                                                Text(
                                                    cmd.command,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            IconButton(
                                                onClick = { onExecuteCommand(cmd) },
                                                modifier = Modifier.testTag("exec_cmd_${cmd.key}")
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Filled.PlayCircleFilled,
                                                    contentDescription = "Run ${cmd.name}",
                                                    tint = BreezeCyan
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // 5. Find My Device & Custom Ping (`kdeconnect.findmyphone.request` & `kdeconnect.ping`)
                item {
                    Card(
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.NotificationsActive, contentDescription = null, tint = BreezeCyan)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = stringResource(R.string.tool_telephony_title),
                                    style = MaterialTheme.typography.titleMedium
                                )
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                            OutlinedTextField(
                                value = customPingMessage,
                                onValueChange = { customPingMessage = it },
                                label = { Text("Optional Ping Notification Text") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(
                                    onClick = {
                                        onSendPing(customPingMessage)
                                        customPingMessage = ""
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(stringResource(R.string.btn_send_ping))
                                }
                                FilledTonalButton(
                                    onClick = onRingDevice,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(stringResource(R.string.quick_find_device))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
