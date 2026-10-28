package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
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
import com.example.ui.components.formatByteSize
import com.example.ui.components.formatDateAndTime
import com.example.ui.components.formatShortTime
import com.example.ui.theme.AmberAccent
import com.example.ui.theme.BreezeCyan
import com.example.ui.theme.BreezeTeal
import com.example.ui.theme.EmeraldSuccess
import com.example.ui.theme.RoseError
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

    // Zero-permission Android Photo Picker per Google Play policy
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            onSendImageMessage(draftMessage, uri)
            draftMessage = ""
        }
    }

    if (showNoteEditor) {
        PersistentNoteEditorDialog(
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
                .widthIn(max = 760.dp)
                .padding(horizontal = 16.dp)
                .testTag("device_space_screen")
        ) {
            // Header & Actions
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = selectedDevice?.let { "${it.deviceName} Space" }
                            ?: stringResource(R.string.space_title),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "Device-isolated persistent chat, images & synced notes",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
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

            // Mode switcher clearly distinguishing transient/chat stream vs persistent bulletin notes
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                FilterChip(
                    selected = activeSubTab == SpaceSubTab.CHAT_STREAM,
                    onClick = { onSelectSubTab(SpaceSubTab.CHAT_STREAM) },
                    label = { Text("${stringResource(R.string.space_tab_chat)} (${messages.size})") },
                    modifier = Modifier.testTag("subtab_chat")
                )
                FilterChip(
                    selected = activeSubTab == SpaceSubTab.PERSISTENT_NOTES,
                    onClick = { onSelectSubTab(SpaceSubTab.PERSISTENT_NOTES) },
                    label = { Text("${stringResource(R.string.space_tab_notes)} (${notes.size})") },
                    modifier = Modifier.testTag("subtab_notes")
                )
            }

            // Offline Queueing Notice Banner
            if (selectedDevice != null && !selectedDevice.isReachable && selectedDevice.pairState == PairState.PAIRED) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("space_offline_banner")
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.CloudOff,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = stringResource(R.string.space_offline_banner),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                }
            }

            if (selectedDevice != null && selectedDevice.pairState != PairState.PAIRED) {
                Spacer(modifier = Modifier.height(16.dp))
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Pair with ${selectedDevice.deviceName} to unlock encrypted Device Space",
                            style = MaterialTheme.typography.titleMedium,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = { onRequestPair(selectedDevice.deviceId) },
                            modifier = Modifier.testTag("space_pair_button")
                        ) {
                            Text(stringResource(R.string.action_pair))
                        }
                    }
                }
            } else if (activeSubTab == SpaceSubTab.CHAT_STREAM) {
                // CHAT & IMAGE STREAM VIEW
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(top = 8.dp)
                        .testTag("chat_messages_list"),
                    contentPadding = PaddingValues(bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (messages.isEmpty()) {
                        item {
                            EmptySpacePlaceholder(
                                message = stringResource(R.string.space_empty_chat)
                            )
                        }
                    } else {
                        items(messages, key = { it.messageUuid }) { msg ->
                            MessageBubbleItem(
                                message = msg,
                                onOpenImage = { file, mime ->
                                    onOpenAttachment(context, file, mime)
                                },
                                onDelete = { onDeleteMessage(msg) }
                            )
                        }
                    }
                }

                // Composer Bar
                Surface(
                    shape = RoundedCornerShape(22.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = {
                                photoPickerLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            },
                            modifier = Modifier.testTag("attach_image_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.AddPhotoAlternate,
                                contentDescription = stringResource(R.string.space_attach_image),
                                tint = BreezeTeal
                            )
                        }

                        OutlinedTextField(
                            value = draftMessage,
                            onValueChange = { draftMessage = it },
                            placeholder = { Text(stringResource(R.string.space_composer_hint)) },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("chat_message_input"),
                            maxLines = 4,
                            shape = RoundedCornerShape(16.dp)
                        )

                        Spacer(modifier = Modifier.width(6.dp))

                        IconButton(
                            onClick = {
                                if (draftMessage.isNotBlank()) {
                                    onSendTextMessage(draftMessage)
                                    draftMessage = ""
                                }
                            },
                            enabled = draftMessage.isNotBlank(),
                            modifier = Modifier.testTag("send_message_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = stringResource(R.string.space_send),
                                tint = if (draftMessage.isNotBlank()) BreezeCyan else MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                }
            } else {
                // PERSISTENT BULLETIN NOTES VIEW
                Box(modifier = Modifier.fillMaxSize()) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = 8.dp)
                            .testTag("persistent_notes_list"),
                        contentPadding = PaddingValues(bottom = 88.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (notes.isEmpty()) {
                            item {
                                EmptySpacePlaceholder(
                                    message = stringResource(R.string.space_empty_notes)
                                )
                            }
                        } else {
                            items(notes, key = { it.noteUuid }) { note ->
                                PersistentNoteCard(
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

                    ExtendedFloatingActionButton(
                        onClick = {
                            editingNote = null
                            showNoteEditor = true
                        },
                        icon = { Icon(Icons.Filled.NoteAdd, contentDescription = null) },
                        text = { Text(stringResource(R.string.space_new_note)) },
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(bottom = 16.dp)
                            .testTag("new_note_fab")
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageBubbleItem(
    message: DeviceMessageEntity,
    onOpenImage: (File, String?) -> Unit,
    onDelete: () -> Unit
) {
    val isOutgoing = message.isOutgoing
    val bubbleShape = if (isOutgoing) {
        RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 4.dp)
    } else {
        RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 4.dp, bottomEnd = 20.dp)
    }

    val containerColor = if (isOutgoing) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val textColor = if (isOutgoing) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("message_item_${message.messageUuid}"),
        horizontalAlignment = if (isOutgoing) Alignment.End else Alignment.Start
    ) {
        Surface(
            shape = bubbleShape,
            color = containerColor,
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                if (message.contentType == MessageContentType.IMAGE) {
                    val localFile = message.attachmentLocalPath?.let(::File)
                    val fileExists = localFile != null && localFile.exists() &&
                        message.attachmentAvailability == AttachmentAvailability.READY

                    if (fileExists && localFile != null) {
                        AsyncImage(
                            model = localFile,
                            contentDescription = message.attachmentFileName ?: "Shared image",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(180.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onOpenImage(localFile, message.attachmentMimeType) }
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "${message.attachmentFileName ?: "Attachment"} • ${formatByteSize(message.attachmentSizeBytes)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = textColor.copy(alpha = 0.75f)
                        )
                    } else {
                        // Explicit missing/inaccessible attachment handling per spec
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.errorContainer)
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Filled.BrokenImage,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Image attachment unavailable or removed from local storage.",
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
                        color = textColor
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (isOutgoing) "Sent • ${formatShortTime(message.createdAtEpochMs)}"
                            else "Peer • ${formatShortTime(message.createdAtEpochMs)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = textColor.copy(alpha = 0.72f)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        SyncStatusBadge(message.syncStatus)
                    }

                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier
                            .size(28.dp)
                            .testTag("delete_msg_${message.messageUuid}")
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.DeleteOutline,
                            contentDescription = stringResource(R.string.action_delete),
                            tint = textColor.copy(alpha = 0.65f),
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }

                if (!message.errorReason.isNullOrBlank()) {
                    Text(
                        text = message.errorReason,
                        style = MaterialTheme.typography.labelSmall,
                        color = RoseError
                    )
                }
            }
        }
    }
}

@Composable
private fun SyncStatusBadge(status: SyncStatus) {
    val (icon, label, tint) = when (status) {
        SyncStatus.QUEUED_OFFLINE -> Triple(Icons.Filled.Schedule, "Queued", AmberAccent)
        SyncStatus.SENDING -> Triple(Icons.Filled.Sync, "Sending", BreezeCyan)
        SyncStatus.DELIVERED -> Triple(Icons.Filled.Check, "Delivered", BreezeTeal)
        SyncStatus.SYNCED -> Triple(Icons.Filled.DoneAll, "Synced", EmeraldSuccess)
        SyncStatus.FAILED -> Triple(Icons.Filled.ErrorOutline, "Retry Pending", RoseError)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(13.dp)
        )
        Spacer(modifier = Modifier.width(3.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = tint
        )
    }
}

@Composable
private fun PersistentNoteCard(
    note: DeviceNoteEntity,
    onEdit: () -> Unit,
    onTogglePin: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (note.isPinned) {
                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            }
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
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
                            tint = BreezeTeal,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text(
                        text = note.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onTogglePin) {
                        Icon(
                            imageVector = if (note.isPinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                            contentDescription = stringResource(R.string.note_pin),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(onClick = onCopy) {
                        Icon(
                            imageVector = Icons.Filled.ContentCopy,
                            contentDescription = "Copy note",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.testTag("delete_note_${note.noteUuid}")
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.DeleteOutline,
                            contentDescription = stringResource(R.string.action_delete),
                            tint = MaterialTheme.colorScheme.error,
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
                    text = "Rev ${note.revision} • Updated ${formatDateAndTime(note.updatedAtEpochMs)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                SyncStatusBadge(note.syncStatus)
            }
        }
    }
}

@Composable
private fun PersistentNoteEditorDialog(
    initialNote: DeviceNoteEntity?,
    onDismiss: () -> Unit,
    onSave: (String?, String, String, Boolean) -> Unit
) {
    var title by remember { mutableStateOf(initialNote?.title ?: "") }
    var content by remember { mutableStateOf(initialNote?.content ?: "") }
    var isPinned by remember { mutableStateOf(initialNote?.isPinned ?: false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.EditNote, contentDescription = null, tint = BreezeTeal)
                Spacer(modifier = Modifier.width(8.dp))
                Text(if (initialNote == null) "New Persistent Note" else "Edit Shared Note")
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.note_title_hint)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("note_title_input")
                )
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text(stringResource(R.string.note_body_hint)) },
                    minLines = 4,
                    maxLines = 8,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("note_content_input")
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { isPinned = !isPinned }
                ) {
                    Checkbox(checked = isPinned, onCheckedChange = { isPinned = it })
                    Text("Pin to top of Device Space")
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(initialNote?.noteUuid, title, content, isPinned) },
                enabled = title.isNotBlank() || content.isNotBlank(),
                modifier = Modifier.testTag("save_note_confirm_button")
            ) {
                Text(stringResource(R.string.note_save))
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

@Composable
private fun EmptySpacePlaceholder(message: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(
            painter = painterResource(id = R.drawable.img_empty_vault),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(144.dp)
                .clip(RoundedCornerShape(22.dp))
        )
        Spacer(modifier = Modifier.height(14.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp)
        )
    }
}
