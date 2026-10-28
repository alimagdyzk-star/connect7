package com.example.ui

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.core.network.LanLinkEngine
import com.example.core.network.RemoteCommandItem
import com.example.core.network.RemoteFileEntry
import com.example.core.security.CryptoIdentityManager
import com.example.data.local.ApexDatabase
import com.example.data.local.DeviceMessageEntity
import com.example.data.local.DeviceNoteEntity
import com.example.data.local.PairState
import com.example.data.local.PairedDeviceEntity
import com.example.data.local.TransferRecordEntity
import com.example.data.repository.ActiveTransferProgress
import com.example.data.repository.ApexRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

enum class ApexDestination {
    HUB,
    REMOTE_FILES,
    DEVICE_SPACE,
    PLUGINS_TOOLS
}

enum class SpaceSubTab {
    CHAT_STREAM,
    PERSISTENT_NOTES
}

sealed class RemoteDirectoryState {
    data object Idle : RemoteDirectoryState()
    data object Loading : RemoteDirectoryState()
    data class Loaded(
        val currentPath: String,
        val entries: List<RemoteFileEntry>
    ) : RemoteDirectoryState()
    data class Unsupported(val reason: String) : RemoteDirectoryState()
    data class Error(val message: String) : RemoteDirectoryState()
}

data class ApexUiState(
    val currentTab: ApexDestination = ApexDestination.HUB,
    val selectedDeviceId: String = LanLinkEngine.WORKSTATION_PEER_ID,
    val spaceSubTab: SpaceSubTab = SpaceSubTab.CHAT_STREAM,
    val remoteDirectoryState: RemoteDirectoryState = RemoteDirectoryState.Idle,
    val currentRemotePath: String = "/",
    val localNodeName: String = "",
    val localFingerprint: String = "",
    val isScanningLan: Boolean = false
)

@OptIn(ExperimentalCoroutinesApi::class)
class ApexViewModel(
    application: Application,
    val repository: ApexRepository
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(
        ApexUiState(
            localNodeName = repository.identityManager.localDeviceName,
            localFingerprint = repository.identityManager.localFingerprintFormatted
        )
    )
    val uiState: StateFlow<ApexUiState> = _uiState.asStateFlow()

    private val _snackbarEvents = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val snackbarEvents = _snackbarEvents.asSharedFlow()

    private var activeTransferJob: Job? = null

    val devices: StateFlow<List<PairedDeviceEntity>> = repository.observeDevices()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val selectedDeviceIdFlow = MutableStateFlow(LanLinkEngine.WORKSTATION_PEER_ID)

    val messagesForSelectedDevice: StateFlow<List<DeviceMessageEntity>> = selectedDeviceIdFlow
        .flatMapLatest { id -> if (id.isBlank()) flowOf(emptyList()) else repository.observeMessages(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val notesForSelectedDevice: StateFlow<List<DeviceNoteEntity>> = selectedDeviceIdFlow
        .flatMapLatest { id -> if (id.isBlank()) flowOf(emptyList()) else repository.observeNotes(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val transfersForSelectedDevice: StateFlow<List<TransferRecordEntity>> = selectedDeviceIdFlow
        .flatMapLatest { id -> if (id.isBlank()) flowOf(emptyList()) else repository.observeTransfers(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val activeTransfer: StateFlow<ActiveTransferProgress?> = repository.activeTransfer
    val protocolEvents: StateFlow<List<String>> = repository.protocolEvents

    init {
        repository.startEngine()
    }

    fun selectTab(destination: ApexDestination) {
        _uiState.update { it.copy(currentTab = destination) }
        if (destination == ApexDestination.REMOTE_FILES) {
            refreshRemoteDirectory(_uiState.value.currentRemotePath)
        }
    }

    fun selectDevice(deviceId: String, jumpToTab: ApexDestination? = null) {
        selectedDeviceIdFlow.value = deviceId
        _uiState.update { state ->
            state.copy(
                selectedDeviceId = deviceId,
                currentTab = jumpToTab ?: state.currentTab,
                currentRemotePath = "/",
                remoteDirectoryState = RemoteDirectoryState.Idle
            )
        }
        val targetTab = jumpToTab ?: _uiState.value.currentTab
        if (targetTab == ApexDestination.REMOTE_FILES) {
            refreshRemoteDirectory("/")
        }
    }

    fun selectSpaceSubTab(subTab: SpaceSubTab) {
        _uiState.update { it.copy(spaceSubTab = subTab) }
    }

    // =========================================================================
    // LAN DISCOVERY & PAIRING ACTIONS
    // =========================================================================

    fun scanLanDevices(customIp: String? = null) {
        viewModelScope.launch {
            _uiState.update { it.copy(isScanningLan = true) }
            val ok = repository.broadcastDiscovery(customIp)
            _uiState.update { it.copy(isScanningLan = false) }
            _snackbarEvents.tryEmit(
                if (!customIp.isNullOrBlank()) {
                    "Sent KDE Connect v8 identity probe to $customIp:1716"
                } else if (ok) {
                    "Broadcasted UDP 1716 KDE Connect discovery packet"
                } else {
                    "LAN discovery completed (local responder active)"
                }
            )
        }
    }

    fun requestPairing(deviceId: String) {
        viewModelScope.launch {
            repository.requestPairing(deviceId)
            _snackbarEvents.tryEmit("Pairing requested — verify SHA-256 code to establish TLS trust")
        }
    }

    fun acceptPairing(deviceId: String) {
        viewModelScope.launch {
            repository.acceptPairing(deviceId)
            _snackbarEvents.tryEmit("TLS certificate pinned & device paired")
            if (_uiState.value.currentTab == ApexDestination.REMOTE_FILES && _uiState.value.selectedDeviceId == deviceId) {
                refreshRemoteDirectory("/")
            }
        }
    }

    fun unpairDevice(deviceId: String) {
        viewModelScope.launch {
            repository.unpairDevice(deviceId)
            if (_uiState.value.selectedDeviceId == deviceId) {
                _uiState.update {
                    it.copy(remoteDirectoryState = RemoteDirectoryState.Error("Device unpaired."))
                }
            }
            _snackbarEvents.tryEmit("Device unpaired")
        }
    }

    fun togglePeerReachability(deviceId: String) {
        viewModelScope.launch {
            repository.toggleDeviceReachability(deviceId)
            if (_uiState.value.currentTab == ApexDestination.REMOTE_FILES && _uiState.value.selectedDeviceId == deviceId) {
                refreshRemoteDirectory(_uiState.value.currentRemotePath)
            }
        }
    }

    // =========================================================================
    // FEATURE 1: REMOTE FILE BROWSER ACTIONS
    // =========================================================================

    fun refreshRemoteDirectory(targetPath: String = _uiState.value.currentRemotePath) {
        val deviceId = _uiState.value.selectedDeviceId
        if (deviceId.isBlank()) return
        viewModelScope.launch {
            _uiState.update { it.copy(remoteDirectoryState = RemoteDirectoryState.Loading) }
            val result = repository.listRemoteDirectory(deviceId, targetPath)
            result.fold(
                onSuccess = { (canonicalPath, items) ->
                    _uiState.update {
                        it.copy(
                            currentRemotePath = canonicalPath,
                            remoteDirectoryState = RemoteDirectoryState.Loaded(canonicalPath, items)
                        )
                    }
                },
                onFailure = { err ->
                    val state = if (err is UnsupportedOperationException) {
                        RemoteDirectoryState.Unsupported(err.message ?: "Remote filesystem unsupported.")
                    } else {
                        RemoteDirectoryState.Error(err.message ?: "Unable to list remote directory.")
                    }
                    _uiState.update { it.copy(remoteDirectoryState = state) }
                }
            )
        }
    }

    fun navigateUpDirectory() {
        val current = _uiState.value.currentRemotePath
        if (current == "/" || current.isBlank()) return
        val parent = current.substringBeforeLast('/').ifBlank { "/" }
        refreshRemoteDirectory(parent)
    }

    fun downloadRemoteFileToSaf(entry: RemoteFileEntry, destinationUri: Uri) {
        val deviceId = _uiState.value.selectedDeviceId
        activeTransferJob?.cancel()
        activeTransferJob = viewModelScope.launch {
            val res = repository.downloadRemoteFile(deviceId, entry, destinationUri)
            res.fold(
                onSuccess = {
                    _snackbarEvents.tryEmit("Saved '${entry.name}' with SHA-256 verification")
                },
                onFailure = { err ->
                    _snackbarEvents.tryEmit("Download failed: ${err.message}")
                }
            )
        }
    }

    fun downloadAndOpenRemoteFile(context: Context, entry: RemoteFileEntry) {
        val deviceId = _uiState.value.selectedDeviceId
        activeTransferJob?.cancel()
        activeTransferJob = viewModelScope.launch {
            val res = repository.downloadRemoteFile(deviceId, entry, safDestinationUri = null)
            res.fold(
                onSuccess = { localFile ->
                    if (localFile != null && localFile.exists()) {
                        openLocalFileWithIntent(context, localFile, entry.mimeType)
                    }
                },
                onFailure = { err ->
                    _snackbarEvents.tryEmit("Unable to open remote file: ${err.message}")
                }
            )
        }
    }

    fun uploadFileToCurrentRemoteDir(sourceUri: Uri) {
        val deviceId = _uiState.value.selectedDeviceId
        val targetDir = _uiState.value.currentRemotePath
        activeTransferJob?.cancel()
        activeTransferJob = viewModelScope.launch {
            val res = repository.uploadFileToRemoteDirectory(deviceId, targetDir, sourceUri)
            res.fold(
                onSuccess = {
                    _snackbarEvents.tryEmit("File uploaded to $targetDir")
                    refreshRemoteDirectory(targetDir)
                },
                onFailure = { err ->
                    _snackbarEvents.tryEmit("Upload failed: ${err.message}")
                }
            )
        }
    }

    fun cancelActiveTransfer() {
        activeTransferJob?.cancel()
        activeTransferJob = null
        _snackbarEvents.tryEmit("Transfer cancelled")
    }

    fun openLocalFileWithIntent(context: Context, file: File, mimeType: String?) {
        if (!file.exists()) {
            _snackbarEvents.tryEmit("Attachment file is no longer available on disk.")
            return
        }
        runCatching {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType ?: "*/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(intent, file.name))
        }.onFailure {
            _snackbarEvents.tryEmit("Downloaded '${file.name}' (${file.length()} bytes) — no external viewer installed for ${mimeType ?: "this type"}.")
        }
    }

    // =========================================================================
    // FEATURE 2: PERSISTENT DEVICE CHAT, IMAGES & NOTES ACTIONS
    // =========================================================================

    fun sendChatMessage(text: String) {
        val deviceId = _uiState.value.selectedDeviceId
        viewModelScope.launch {
            val res = repository.sendTextMessage(deviceId, text)
            res.onFailure { err ->
                _snackbarEvents.tryEmit(err.message ?: "Failed to send message")
            }
        }
    }

    fun sendImageMessage(caption: String, imageUri: Uri) {
        val deviceId = _uiState.value.selectedDeviceId
        viewModelScope.launch {
            val res = repository.sendImageAttachmentMessage(deviceId, caption, imageUri)
            res.fold(
                onSuccess = { _snackbarEvents.tryEmit("Image stored & dispatched to Device Space") },
                onFailure = { err -> _snackbarEvents.tryEmit(err.message ?: "Image share failed") }
            )
        }
    }

    fun savePersistentNote(existingUuid: String?, title: String, content: String, isPinned: Boolean) {
        val deviceId = _uiState.value.selectedDeviceId
        viewModelScope.launch {
            val res = repository.saveOrUpdateNote(deviceId, existingUuid, title, content, isPinned)
            res.fold(
                onSuccess = { _snackbarEvents.tryEmit("Note saved & synchronized") },
                onFailure = { err -> _snackbarEvents.tryEmit(err.message ?: "Could not save note") }
            )
        }
    }

    fun toggleNotePin(note: DeviceNoteEntity) {
        viewModelScope.launch {
            repository.toggleNotePin(note)
        }
    }

    fun deleteNote(note: DeviceNoteEntity) {
        viewModelScope.launch {
            repository.deleteNote(note)
            _snackbarEvents.tryEmit("Note deleted")
        }
    }

    fun deleteMessage(message: DeviceMessageEntity) {
        viewModelScope.launch {
            repository.deleteSingleMessage(message)
            _snackbarEvents.tryEmit("Message removed from local history")
        }
    }

    fun clearHistoryForSelectedDevice() {
        val deviceId = _uiState.value.selectedDeviceId
        viewModelScope.launch {
            repository.clearConversationHistory(deviceId)
            _snackbarEvents.tryEmit("Conversation history cleared")
        }
    }

    fun retrySyncForSelectedDevice() {
        val deviceId = _uiState.value.selectedDeviceId
        viewModelScope.launch {
            repository.synchronizePendingSpaceItems(deviceId)
            _snackbarEvents.tryEmit("Triggered Space delta synchronization")
        }
    }

    // =========================================================================
    // CORE KDE CONNECT PLUGIN ACTIONS (Ping, FindMyPhone, Clipboard, Touchpad, MPRIS, Commands)
    // =========================================================================

    fun sendPing(customMessage: String = "") {
        val deviceId = _uiState.value.selectedDeviceId
        viewModelScope.launch {
            repository.sendPing(deviceId, customMessage).fold(
                onSuccess = { _snackbarEvents.tryEmit("Ping delivered over TLS") },
                onFailure = { _snackbarEvents.tryEmit(it.message ?: "Ping failed") }
            )
        }
    }

    fun ringPairedDevice() {
        val deviceId = _uiState.value.selectedDeviceId
        viewModelScope.launch {
            repository.sendFindMyDeviceRing(deviceId).fold(
                onSuccess = {
                    triggerHapticConfirmation()
                    _snackbarEvents.tryEmit("Ringing remote device (`kdeconnect.findmyphone.request`)")
                },
                onFailure = { _snackbarEvents.tryEmit(it.message ?: "Cannot ring device") }
            )
        }
    }

    fun pushCurrentClipboardOrText(customText: String? = null) {
        val deviceId = _uiState.value.selectedDeviceId
        val app = getApplication<Application>()
        val textToPush = if (!customText.isNullOrBlank()) {
            customText
        } else {
            val cm = app.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cm?.primaryClip?.getItemAt(0)?.coerceToText(app)?.toString().orEmpty()
        }

        if (textToPush.isBlank()) {
            _snackbarEvents.tryEmit("System clipboard is empty — enter text to push")
            return
        }

        viewModelScope.launch {
            repository.pushClipboard(deviceId, textToPush).fold(
                onSuccess = { _snackbarEvents.tryEmit("Clipboard pushed to remote peer") },
                onFailure = { _snackbarEvents.tryEmit(it.message ?: "Clipboard sync failed") }
            )
        }
    }

    fun copyToLocalClipboard(label: String, text: String) {
        val app = getApplication<Application>()
        val cm = app.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText(label, text))
        _snackbarEvents.tryEmit("Copied to Android clipboard")
    }

    fun sendTouchpadDelta(dx: Float, dy: Float) {
        val deviceId = _uiState.value.selectedDeviceId
        viewModelScope.launch {
            repository.sendMousepadInput(deviceId, dx = dx, dy = dy)
        }
    }

    fun sendTouchpadClick(rightClick: Boolean = false) {
        val deviceId = _uiState.value.selectedDeviceId
        viewModelScope.launch {
            repository.sendMousepadInput(
                deviceId = deviceId,
                singleClick = !rightClick,
                rightClick = rightClick
            )
        }
    }

    fun sendRemoteKey(keyText: String) {
        val deviceId = _uiState.value.selectedDeviceId
        viewModelScope.launch {
            repository.sendMousepadInput(deviceId = deviceId, keyText = keyText).onSuccess {
                _snackbarEvents.tryEmit("Sent keyboard input to remote host")
            }
        }
    }

    fun sendMediaCommand(action: String, volume: Int? = null) {
        val deviceId = _uiState.value.selectedDeviceId
        viewModelScope.launch {
            repository.sendMprisAction(deviceId, action, volume).onFailure {
                _snackbarEvents.tryEmit(it.message ?: "MPRIS media action failed")
            }
        }
    }

    fun executeRemoteCommand(item: RemoteCommandItem) {
        val deviceId = _uiState.value.selectedDeviceId
        viewModelScope.launch {
            repository.triggerRemoteCommand(deviceId, item.key, item.name).fold(
                onSuccess = { _snackbarEvents.tryEmit("Executed '${item.name}'") },
                onFailure = { _snackbarEvents.tryEmit(it.message ?: "Command failed") }
            )
        }
    }

    fun getRemoteCommands(device: PairedDeviceEntity?): List<RemoteCommandItem> =
        if (device == null) emptyList() else repository.getRemoteCommandsForDevice(device)

    private fun triggerHapticConfirmation() {
        val app = getApplication<Application>()
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = app.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator?.vibrate(VibrationEffect.createOneShot(45L, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                val v = app.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v?.vibrate(VibrationEffect.createOneShot(45L, VibrationEffect.DEFAULT_AMPLITUDE))
                }
            }
        }
    }

    class Factory(private val application: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val db = ApexDatabase.getInstance(application)
            val crypto = CryptoIdentityManager(application)
            val repo = ApexRepository(application, db, crypto)
            return ApexViewModel(application, repo) as T
        }
    }
}
