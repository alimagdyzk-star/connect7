package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.R
import com.example.data.local.AttachmentAvailability
import com.example.data.local.DeviceMessageEntity
import com.example.data.local.DeviceNoteEntity
import com.example.data.local.MessageContentType
import com.example.data.local.PairState
import com.example.data.local.PairedDeviceEntity
import com.example.data.local.SyncStatus
import com.example.ui.SpaceSubTab
import com.example.ui.components.AppleGroupedSection
import com.example.ui.components.AppleSegmentedControl
import com.example.ui.components.AppleSquircleIcon
import com.example.ui.components.formatByteSize
import com.example.ui.components.formatDateAndTime
import com.example.ui.components.formatShortTime
import com.example.ui.theme.AppleBlue
import com.example.ui.theme.AppleGreen
import com.example.ui.theme.AppleOrange
import com.example.ui.theme.AppleRed
import com.example.ui.theme.AppleYellow
import java.io.File

@Composable
fun DeviceSpaceScreen(
    selectedDevice: PairedDeviceEntity?,
    activeSubTab: SpaceSubTab,
    messages: List<DeviceMessageEntity>,
    notes: List<DeviceNoteEntity>,
    onSelectSubTab: (SpaceSubTab) -> Unit,
    onSendTextMessage: (String) -> Unit,
    onSendImageMessage: (String, Uri) -> Unit,
    onDeleteMessage: (DeviceMessageEntity) -> Unit,
    onClearHistory: () -> Unit,
    onOpenAttachment: (android.content.Context, File, String?) -> Unit,
    onSaveNote: (String?, String, String, Boolean) -> Unit,
    onToggleNotePin: (DeviceNoteEntity) -> Unit,
    onDeleteNote: (DeviceNoteEntity) -> Unit,
    onCopyNote: (String, String) -> Unit,
    onRetrySync: () -> Unit,
    onRequestPair: (String) -> Unit,
    onBackToHub: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var draftMessage by rememberSaveable { mutableStateOf("") }
    var showNoteEditor by remember { mutableStateOf(false) }
    var editingNote by remember { mutableStateOf<DeviceNoteEntity?>(null) }

    BackHandler {
        onBackToHub()
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            onSendImageMessage(draftMessage, uri)
            draftMessage = ""
        }
    }

    if (showNoteEditor) {
        AppleNoteSheetDialog(
            initialNote = editingNote,
            onDismiss = {
                showNoteEditor = false
                editingNote = null
            },
            onSave = { uuid, title, content, pinned ->
                onSaveNote(uuid, title, content, pinned)
                showNoteEditor = false
                editingNote = null
            }
        )
    }

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 680.dp)
                .padding(horizontal = 20.dp)
                .testTag("device_space_screen")
        ) {
            // Apple Messages / Notes Large Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (activeSubTab == SpaceSubTab.CHAT_STREAM) "Messages" else "Notes",
                        style = MaterialTheme.typography.displaySmall,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = selectedDevice?.deviceName ?: stringResource(R.string.space_title),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onRetrySync,
                        modifier = Modifier.testTag("space_sync_now_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Sync,
                            contentDescription = stringResource(R.string.action_retry),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    if (activeSubTab == SpaceSubTab.CHAT_STREAM && messages.isNotEmpty()) {
                        IconButton(
                            onClick = onClearHistory,
                            modifier = Modifier.testTag("space_clear_history_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.DeleteSweep,
                                contentDescription = stringResource(R.string.space_clear_history),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Signature iOS Sliding Segmented Control
            AppleSegmentedControl(
                options = listOf(
                    "Messages (${messages.size})",
                    "Notes (${notes.size})"
                ),
                selectedIndex = if (activeSubTab == SpaceSubTab.CHAT_STREAM) 0 else 1,
                onSelectIndex = { idx ->
                    onSelectSubTab(if (idx == 0) SpaceSubTab.CHAT_STREAM else SpaceSubTab.PERSISTENT_NOTES)
                },
                testTags = listOf("subtab_chat", "subtab_notes")
            )

            // Clean Offline Queuing Banner
            if (selectedDevice != null && !selectedDevice.isReachable && selectedDevice.pairState == PairState.PAIRED) {
                Spacer(modifier = Modifier.height(10.dp))
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("space_offline_banner")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.CloudOff,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Device offline — items are saved and will sync automatically.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                }
            }

            if (selectedDevice == null) {
                Spacer(modifier = Modifier.height(18.dp))
                AppleGroupedSection(modifier = Modifier.testTag("space_no_device_card")) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(26.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        AppleSquircleIcon(
                            icon = Icons.AutoMirrored.Filled.Chat,
                            tintColor = AppleBlue,
                            sizeDp = 46
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = "Connect a Device First",
                            style = MaterialTheme.typography.titleLarge,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Pair with a computer or phone over Wi-Fi to start a persistent encrypted conversation and shared notes board.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = onBackToHub,
                            shape = CircleShape,
                            modifier = Modifier.testTag("space_open_devices_button")
                        ) {
                            Text("Open Devices")
                        }
                    }
                }
            } else if (selectedDevice.pairState != PairState.PAIRED) {
                Spacer(modifier = Modifier.height(18.dp))
                AppleGroupedSection {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Pair with ${selectedDevice.deviceName} to send messages & notes",
                            style = MaterialTheme.typography.titleMedium,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Button(
                            onClick = { onRequestPair(selectedDevice.deviceId) },
                            shape = CircleShape,
                            modifier = Modifier.testTag("space_pair_button")
                        ) {
                            Text(stringResource(R.string.action_pair))
                        }
                    }
                }
            } else if (activeSubTab == SpaceSubTab.CHAT_STREAM) {
                // APPLE iMESSAGE-INSPIRED CONVERSATION STREAM
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(top = 12.dp)
                        .testTag("chat_messages_list"),
                    contentPadding = PaddingValues(bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (messages.isEmpty()) {
                        item {
                            AppleEmptySpaceState(
                                icon = Icons.AutoMirrored.Filled.Chat,
                                tint = AppleBlue,
                                title = "No Messages Yet",
                                subtitle = "Send text snippets, links, or photos directly to ${selectedDevice?.deviceName ?: "your peer"}."
                            )
                        }
                    } else {
                        items(messages, key = { it.messageUuid }) { msg ->
                            IMessageBubble(
                                message = msg,
                                onOpenImage = { file, mime ->
                                    onOpenAttachment(context, file, mime)
                                },
                                onDelete = { onDeleteMessage(msg) }
                            )
                        }
                    }
                }

                // Authentic iMessage Pill Composer Bar (`+` circle on left, pill input with upward blue arrow on right)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surface,
                        shadowElevation = 0.5.dp,
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .clickable {
                                photoPickerLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            }
                            .testTag("attach_image_button")
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Filled.Add,
                                contentDescription = stringResource(R.string.space_attach_image),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Surface(
                        shape = RoundedCornerShape(22.dp),
                        color = MaterialTheme.colorScheme.surface,
                        shadowElevation = 0.5.dp,
                        modifier = Modifier.weight(1f)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(vertical = 6.dp)
                            ) {
                                if (draftMessage.isEmpty()) {
                                    Text(
                                        text = "iMessage / LAN Message",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                BasicTextField(
                                    value = draftMessage,
                                    onValueChange = { draftMessage = it },
                                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                                        color = MaterialTheme.colorScheme.onSurface
                                    ),
                                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                    maxLines = 4,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("chat_message_input")
                                )
                            }

                            Spacer(modifier = Modifier.width(6.dp))

                            val canSend = draftMessage.isNotBlank()
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (canSend) AppleBlue
                                        else MaterialTheme.colorScheme.surfaceVariant
                                    )
                                    .clickable(enabled = canSend) {
                                        onSendTextMessage(draftMessage)
                                        draftMessage = ""
                                    }
                                    .testTag("send_message_button"),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.ArrowUpward,
                                    contentDescription = stringResource(R.string.space_send),
                                    tint = if (canSend) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            } else {
                // APPLE NOTES INSPIRED CLEAN CARDS
                Box(modifier = Modifier.fillMaxSize()) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = 12.dp)
                            .testTag("persistent_notes_list"),
                        contentPadding = PaddingValues(bottom = 88.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (notes.isEmpty()) {
                            item {
                                AppleEmptySpaceState(
                                    icon = Icons.Filled.EditNote,
                                    tint = AppleOrange,
                                    title = "No Shared Notes",
                                    subtitle = "Create persistent notes, Wi-Fi passwords, or terminal snippets synced with this device."
                                )
                            }
                        } else {
                            items(notes, key = { it.noteUuid }) { note ->
                                AppleNoteCard(
                                    note = note,
                                    onEdit = {
                                        editingNote = note
                                        showNoteEditor = true
                                    },
                                    onTogglePin = { onToggleNotePin(note) },
                                    onCopy = { onCopyNote(note.title, note.content) },
                                    onDelete = { onDeleteNote(note) }
                                )
                            }
                        }
                    }

                    FloatingActionButton(
                        onClick = {
                            editingNote = null
                            showNoteEditor = true
                        },
                        shape = CircleShape,
                        containerColor = AppleYellow,
                        contentColor = Color(0xFF1C1C1E),
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(bottom = 18.dp)
                            .testTag("new_note_fab")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.NoteAdd,
                            contentDescription = stringResource(R.string.space_new_note)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun IMessageBubble(
    message: DeviceMessageEntity,
    onOpenImage: (File, String?) -> Unit,
    onDelete: () -> Unit
) {
    val isOutgoing = message.isOutgoing
    val bubbleShape = if (isOutgoing) {
        RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 5.dp)
    } else {
        RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 5.dp, bottomEnd = 20.dp)
    }

    val bubbleColor = if (isOutgoing) AppleBlue else MaterialTheme.colorScheme.surface
    val contentColor = if (isOutgoing) Color.White else MaterialTheme.colorScheme.onSurface

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("message_item_${message.messageUuid}"),
        horizontalAlignment = if (isOutgoing) Alignment.End else Alignment.Start
    ) {
        Surface(
            shape = bubbleShape,
            color = bubbleColor,
            shadowElevation = if (isOutgoing) 0.dp else 0.5.dp,
            modifier = Modifier.widthIn(max = 300.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                if (message.contentType == MessageContentType.IMAGE) {
                    val localFile = message.attachmentLocalPath?.let(::File)
                    val fileExists = localFile != null && localFile.exists() &&
                        message.attachmentAvailability == AttachmentAvailability.READY

                    if (fileExists) {
                        AsyncImage(
                            model = localFile,
                            contentDescription = message.attachmentFileName ?: "Photo",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(176.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .clickable { onOpenImage(localFile, message.attachmentMimeType) }
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "${message.attachmentFileName ?: "Image"} • ${formatByteSize(message.attachmentSizeBytes)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = contentColor.copy(alpha = 0.8f)
                        )
                    } else {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.errorContainer)
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Filled.BrokenImage,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Attachment unavailable",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                    if (message.textBody.isNotBlank()) {
                        Spacer(modifier = Modifier.height(6.dp))
                    }
                }

                if (message.textBody.isNotBlank()) {
                    Text(
                        text = message.textBody,
                        style = MaterialTheme.typography.bodyLarge,
                        color = contentColor
                    )
                }
            }
        }

        // Subtle caption line below bubble (iMessage style delivery & timestamp)
        Row(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = formatShortTime(message.createdAtEpochMs),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            AppleDeliveryLabel(message.syncStatus)
            Text(
                text = "Delete",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier
                    .clickable(onClick = onDelete)
                    .testTag("delete_msg_${message.messageUuid}")
            )
        }
    }
}

@Composable
private fun AppleDeliveryLabel(status: SyncStatus) {
    val (icon, text, color) = when (status) {
        SyncStatus.QUEUED_OFFLINE -> Triple(Icons.Filled.Schedule, "Queued", AppleOrange)
        SyncStatus.SENDING -> Triple(Icons.Filled.Sync, "Sending…", AppleBlue)
        SyncStatus.DELIVERED -> Triple(Icons.Filled.Check, "Delivered", MaterialTheme.colorScheme.onSurfaceVariant)
        SyncStatus.SYNCED -> Triple(Icons.Filled.DoneAll, "Synced", AppleGreen)
        SyncStatus.FAILED -> Triple(Icons.Filled.ErrorOutline, "Not Delivered", AppleRed)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(12.dp))
        Spacer(modifier = Modifier.width(3.dp))
        Text(text = text, style = MaterialTheme.typography.bodySmall, color = color)
    }
}

@Composable
private fun AppleNoteCard(
    note: DeviceNoteEntity,
    onEdit: () -> Unit,
    onTogglePin: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit
) {
    AppleGroupedSection(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onEdit)
            .testTag("note_card_${note.noteUuid}")
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    if (note.isPinned) {
                        Icon(
                            imageVector = Icons.Filled.PushPin,
                            contentDescription = "Pinned",
                            tint = AppleOrange,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text(
                        text = note.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onTogglePin, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = if (note.isPinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                            contentDescription = stringResource(R.string.note_pin),
                            tint = if (note.isPinned) AppleOrange else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                    IconButton(onClick = onCopy, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = Icons.Filled.ContentCopy,
                            contentDescription = "Copy",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier
                            .size(32.dp)
                            .testTag("delete_note_${note.noteUuid}")
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.DeleteOutline,
                            contentDescription = stringResource(R.string.action_delete),
                            tint = AppleRed,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            if (note.content.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = note.content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = formatDateAndTime(note.updatedAtEpochMs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                AppleDeliveryLabel(note.syncStatus)
            }
        }
    }
}

@Composable
private fun AppleNoteSheetDialog(
    initialNote: DeviceNoteEntity?,
    onDismiss: () -> Unit,
    onSave: (String?, String, String, Boolean) -> Unit
) {
    var title by remember { mutableStateOf(initialNote?.title ?: "") }
    var content by remember { mutableStateOf(initialNote?.content ?: "") }
    var isPinned by remember { mutableStateOf(initialNote?.isPinned ?: false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initialNote == null) "New Shared Note" else "Edit Note") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    placeholder = { Text(stringResource(R.string.note_title_hint)) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("note_title_input")
                )
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    placeholder = { Text(stringResource(R.string.note_body_hint)) },
                    minLines = 4,
                    maxLines = 8,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("note_content_input")
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { isPinned = !isPinned }
                ) {
                    Checkbox(checked = isPinned, onCheckedChange = { isPinned = it })
                    Text("Pin Note")
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(initialNote?.noteUuid, title, content, isPinned) },
                enabled = title.isNotBlank() || content.isNotBlank(),
                shape = CircleShape,
                modifier = Modifier.testTag("save_note_confirm_button")
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

@Composable
private fun AppleEmptySpaceState(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    title: String,
    subtitle: String
) {
    AppleGroupedSection(modifier = Modifier.padding(top = 12.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 36.dp, horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            AppleSquircleIcon(icon = icon, tintColor = tint, sizeDp = 48)
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}
