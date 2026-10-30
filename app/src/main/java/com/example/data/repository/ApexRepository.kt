package com.example.data.repository

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.RingtoneManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.OpenableColumns
import android.util.Base64
import com.example.core.network.LanLinkEngine
import com.example.core.network.RemoteCommandItem
import com.example.core.network.RemoteFileEntry
import com.example.core.protocol.NetworkPacket
import com.example.core.security.CryptoIdentityManager
import com.example.core.security.PathValidator
import com.example.data.local.ApexDatabase
import com.example.data.local.AttachmentAvailability
import com.example.data.local.DeviceMessageEntity
import com.example.data.local.DeviceNoteEntity
import com.example.data.local.MessageContentType
import com.example.data.local.PairState
import com.example.data.local.PairedDeviceEntity
import com.example.data.local.SyncStatus
import com.example.data.local.TransferRecordEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class ActiveTransferProgress(
    val transferId: String,
    val fileName: String,
    val direction: String,
    val transferredBytes: Long,
    val totalBytes: Long
) {
    val fraction: Float
        get() = if (totalBytes <= 0L) 0f else (transferredBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
}

/**
 * Production Repository with ZERO pre-seeded fake/mock devices.
 *
 * Connects exclusively to real KDE Connect / ApexConnect devices discovered via UDP 1716, mDNS,
 * or direct IP connection, performs real X.509 certificate pinning & verification, exchanges
 * real Android hardware/OS telemetry (BatteryManager, ClipboardManager, Ringtone/Vibrator, SAF),
 * and synchronizes persistent Device Space items over mutual TLS.
 */
class ApexRepository(
    private val context: Context,
    private val database: ApexDatabase,
    val identityManager: CryptoIdentityManager
) {
    private val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val deviceDao = database.deviceDao()
    private val spaceDao = database.spaceDao()
    private val transferDao = database.transferDao()

    private val _activeTransfer = MutableStateFlow<ActiveTransferProgress?>(null)
    val activeTransfer: StateFlow<ActiveTransferProgress?> = _activeTransfer.asStateFlow()

    private val _protocolEvents = MutableStateFlow<List<String>>(emptyList())
    val protocolEvents: StateFlow<List<String>> = _protocolEvents.asStateFlow()

    private val _incomingAlerts = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val incomingAlerts: SharedFlow<String> = _incomingAlerts.asSharedFlow()

    val lanEngine: LanLinkEngine = LanLinkEngine(
        context = context,
        identityManager = identityManager,
        onPeerConnectedOrUpdated = { deviceId, deviceName, deviceType, ipAddress, tcpPort, certBytes, inCaps, outCaps ->
            handleRealTlsPeerConnected(deviceId, deviceName, deviceType, ipAddress, tcpPort, certBytes, inCaps, outCaps)
        },
        onPeerDisconnected = { deviceId ->
            runCatching {
                deviceDao.updateReachability(deviceId, false)
                logProtocolEvent("TLS link closed for peer $deviceId")
            }
        },
        onPacketReceived = { senderId, packet, payload ->
            handleIncomingPacketFromPeer(senderId, packet, payload)
        }
    )

    fun startEngine() {
        repoScope.launch {
            // Reset reachability on cold boot so only genuinely connected LAN peers show Online
            deviceDao.markAllDisconnectedOnStartup()
            lanEngine.start()
            broadcastDiscovery()
        }
    }

    fun observeDevices(): Flow<List<PairedDeviceEntity>> = deviceDao.observeAllDevices()

    fun observeMessages(deviceId: String): Flow<List<DeviceMessageEntity>> =
        spaceDao.observeMessagesForDevice(deviceId)

    fun observeNotes(deviceId: String): Flow<List<DeviceNoteEntity>> =
        spaceDao.observeNotesForDevice(deviceId)

    fun observeTransfers(deviceId: String): Flow<List<TransferRecordEntity>> =
        transferDao.observeTransfersForDevice(deviceId)

    fun getLocalIpAddresses(): List<String> = lanEngine.getLocalLanIpAddresses()

    /**
     * Parses real remote commands advertised by the connected KDE Connect desktop (`kdeconnect.runcommand`).
     */
    fun getRemoteCommandsForDevice(device: PairedDeviceEntity): List<RemoteCommandItem> {
        return runCatching {
            val arr = JSONArray(device.remoteCommandsJson)
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    add(
                        RemoteCommandItem(
                            key = obj.optString("key"),
                            name = obj.optString("name"),
                            command = obj.optString("command")
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun logProtocolEvent(summary: String) {
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        _protocolEvents.value = (listOf("[$stamp] $summary") + _protocolEvents.value).take(40)
    }

    suspend fun broadcastDiscovery(customIp: String? = null): Boolean = withContext(Dispatchers.IO) {
        val ok = lanEngine.broadcastUdpIdentity(customIp)
        if (!customIp.isNullOrBlank()) {
            logProtocolEvent("Probing KDE Connect node at ${customIp.trim()}:1716 (UDP + TCP/TLS)")
        } else {
            logProtocolEvent("Broadcasted KDE Connect v8 identity on UDP 1716")
        }
        ok
    }

    // =========================================================================
    // REAL TLS PEER HANDSHAKE & CERTIFICATE PINNING
    // =========================================================================

    private suspend fun handleRealTlsPeerConnected(
        deviceId: String,
        deviceName: String,
        deviceType: String,
        ipAddress: String,
        tcpPort: Int,
        certBytes: ByteArray?,
        inCaps: List<String>,
        outCaps: List<String>
    ) {
        val existing = deviceDao.getDeviceById(deviceId)
        val certBase64 = certBytes?.let { Base64.encodeToString(it, Base64.NO_WRAP) }

        // Enforce certificate pinning if this device was already paired!
        if (existing != null &&
            existing.pairState == PairState.PAIRED &&
            !existing.pinnedCertificateBase64.isNullOrBlank() &&
            certBase64 != null &&
            existing.pinnedCertificateBase64 != certBase64
        ) {
            logProtocolEvent("SECURITY ALERT: Certificate mismatch for paired peer $deviceName — rejecting trust!")
            deviceDao.updatePairState(deviceId, PairState.UNPAIRED)
            return
        }

        val fingerprint = if (certBytes != null) {
            CryptoIdentityManager.formatSha256Hex(CryptoIdentityManager.sha256(certBytes))
        } else {
            existing?.certificateFingerprint ?: "Unknown"
        }

        val verCode = if (certBytes != null) {
            identityManager.computePairingVerificationCode(certBytes)
        } else {
            existing?.verificationCode ?: ""
        }

        val supportsKdeSftp = outCaps.contains(NetworkPacket.TYPE_SFTP) || inCaps.contains(NetworkPacket.TYPE_SFTP_REQUEST)
        val supportsApexVfs = inCaps.contains(NetworkPacket.TYPE_VFS_LIST_REQUEST)
        val supportsSpace = inCaps.contains(NetworkPacket.TYPE_SPACE_MESSAGE)
        val supportsMousepad = inCaps.contains(NetworkPacket.TYPE_MOUSEPAD_REQUEST)
        val supportsMpris = outCaps.contains(NetworkPacket.TYPE_MPRIS) || inCaps.contains(NetworkPacket.TYPE_MPRIS_REQUEST)

        val peerEntity = PairedDeviceEntity(
            deviceId = deviceId,
            deviceName = deviceName,
            deviceType = deviceType,
            platformOs = inferPeerLabel(deviceType, supportsKdeSftp, supportsApexVfs),
            ipAddress = ipAddress,
            tcpPort = tcpPort,
            pairState = existing?.pairState ?: PairState.UNPAIRED,
            isReachable = true,
            certificateFingerprint = fingerprint,
            verificationCode = verCode,
            pinnedCertificateBase64 = existing?.pinnedCertificateBase64 ?: certBase64,
            batteryLevel = existing?.batteryLevel ?: -1,
            isCharging = existing?.isCharging ?: false,
            supportsRemoteFs = supportsKdeSftp || supportsApexVfs,
            supportsKdeSftp = supportsKdeSftp,
            supportsApexVfs = supportsApexVfs,
            supportsUploadFs = true,
            supportsSpaceSync = supportsSpace,
            supportsMousepad = supportsMousepad,
            supportsMpris = supportsMpris,
            mediaPlayerName = existing?.mediaPlayerName ?: "",
            mediaTitle = existing?.mediaTitle ?: "",
            mediaArtist = existing?.mediaArtist ?: "",
            isMediaPlaying = existing?.isMediaPlaying ?: false,
            mediaVolume = existing?.mediaVolume ?: 50,
            lastClipboardSnippet = existing?.lastClipboardSnippet ?: "",
            remoteCommandsJson = existing?.remoteCommandsJson ?: "[]",
            lastSeenEpochMs = System.currentTimeMillis()
        )
        deviceDao.upsertDevice(peerEntity)
        logProtocolEvent("TLS 1.3 link established with '$deviceName' ($ipAddress)")

        if (peerEntity.pairState == PairState.PAIRED) {
            onTrustedPeerSessionReady(deviceId)
        }
    }

    private fun inferPeerLabel(deviceType: String, kdeSftp: Boolean, apexVfs: Boolean): String {
        val base = when (deviceType.lowercase()) {
            "desktop" -> "Desktop Workstation"
            "laptop" -> "Laptop"
            "phone", "smartphone" -> "Phone"
            "tablet" -> "Tablet"
            "tv" -> "TV"
            else -> "KDE Connect Node"
        }
        return when {
            apexVfs -> "$base • ApexConnect VFS"
            kdeSftp -> "$base • KDE Connect SFTP"
            else -> "$base • KDE Connect v8"
        }
    }

    /**
     * Immediately exchanges real Android battery state, requests remote battery, MPRIS player list,
     * and remote RunCommands, and flushes any offline-queued Device Space messages/notes.
     */
    private suspend fun onTrustedPeerSessionReady(deviceId: String) {
        sendLocalAndroidBatteryToPeer(deviceId)
        lanEngine.sendPacketToConnectedPeer(
            deviceId,
            NetworkPacket(type = NetworkPacket.TYPE_BATTERY_REQUEST, body = JSONObject().put("request", true))
        )
        lanEngine.sendPacketToConnectedPeer(
            deviceId,
            NetworkPacket(type = NetworkPacket.TYPE_MPRIS_REQUEST, body = JSONObject().put("requestPlayerList", true))
        )
        lanEngine.sendPacketToConnectedPeer(
            deviceId,
            NetworkPacket(type = NetworkPacket.TYPE_RUNCOMMAND_REQUEST, body = JSONObject().put("requestCommandList", true))
        )
        synchronizePendingSpaceItems(deviceId)
    }

    private suspend fun sendLocalAndroidBatteryToPeer(deviceId: String) {
        runCatching {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            if (level >= 0 && scale > 0) {
                val pct = (level * 100) / scale
                val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL
                val pkt = NetworkPacket(
                    type = NetworkPacket.TYPE_BATTERY,
                    body = JSONObject().apply {
                        put("currentCharge", pct)
                        put("isCharging", charging)
                        put("thresholdEvent", 0)
                    }
                )
                lanEngine.sendPacketToConnectedPeer(deviceId, pkt)
            }
        }
    }

    // =========================================================================
    // PAIRING LIFECYCLE (`kdeconnect.pair`)
    // =========================================================================

    suspend fun requestPairing(deviceId: String) = withContext(Dispatchers.IO) {
        val device = deviceDao.getDeviceById(deviceId) ?: return@withContext
        val packet = NetworkPacket(
            type = NetworkPacket.TYPE_PAIR,
            body = JSONObject().apply {
                put("pair", true)
                put("timestamp", System.currentTimeMillis() / 1000L)
            }
        )
        val res = lanEngine.sendPacketToConnectedPeer(deviceId, packet)
        if (res.isSuccess) {
            deviceDao.updatePairState(deviceId, PairState.PAIR_REQUESTED_BY_US)
            logProtocolEvent("Sent kdeconnect.pair request to ${device.deviceName} — accept on remote device")
        } else {
            _incomingAlerts.tryEmit("Cannot reach ${device.deviceName}. Tap Scan to reconnect.")
        }
    }

    suspend fun acceptPairing(deviceId: String) = withContext(Dispatchers.IO) {
        val device = deviceDao.getDeviceById(deviceId) ?: return@withContext
        val packet = NetworkPacket(
            type = NetworkPacket.TYPE_PAIR,
            body = JSONObject().put("pair", true)
        )
        val res = lanEngine.sendPacketToConnectedPeer(deviceId, packet)
        if (res.isSuccess) {
            deviceDao.updatePairState(deviceId, PairState.PAIRED)
            logProtocolEvent("Paired & pinned X.509 certificate for ${device.deviceName}")
            onTrustedPeerSessionReady(deviceId)
        } else {
            _incomingAlerts.tryEmit("Pairing failed: peer socket closed.")
        }
    }

    suspend fun unpairDevice(deviceId: String) = withContext(Dispatchers.IO) {
        val device = deviceDao.getDeviceById(deviceId) ?: return@withContext
        if (device.isReachable) {
            lanEngine.sendPacketToConnectedPeer(
                deviceId,
                NetworkPacket(type = NetworkPacket.TYPE_PAIR, body = JSONObject().put("pair", false))
            )
        }
        deviceDao.updatePairState(deviceId, PairState.UNPAIRED)
        logProtocolEvent("Unpaired ${device.deviceName}")
    }

    suspend fun reconnectDevice(deviceId: String) = withContext(Dispatchers.IO) {
        val device = deviceDao.getDeviceById(deviceId) ?: return@withContext
        broadcastDiscovery(device.ipAddress)
    }

    // =========================================================================
    // FEATURE 1: REAL REMOTE FILE BROWSING & SHARE/UPLOAD
    // =========================================================================

    suspend fun listRemoteDirectory(
        deviceId: String,
        requestedPath: String
    ): Result<Pair<String, List<RemoteFileEntry>>> = withContext(Dispatchers.IO) {
        val device = deviceDao.getDeviceById(deviceId)
            ?: return@withContext Result.failure(IllegalArgumentException("Select a connected device first."))

        if (device.pairState != PairState.PAIRED) {
            return@withContext Result.failure(
                SecurityException("Pair with ${device.deviceName} first before browsing remote files.")
            )
        }
        if (!device.isReachable) {
            return@withContext Result.failure(
                IllegalStateException("${device.deviceName} is currently offline on Wi-Fi/LAN.")
            )
        }

        lanEngine.requestSftpAndBrowseDirectory(
            deviceId = deviceId,
            requestedPath = requestedPath,
            supportsKdeSftp = device.supportsKdeSftp,
            supportsApexVfs = device.supportsApexVfs
        )
    }

    suspend fun downloadRemoteFile(
        deviceId: String,
        entry: RemoteFileEntry,
        safDestinationUri: Uri? = null
    ): Result<File?> = withContext(Dispatchers.IO) {
        val device = deviceDao.getDeviceById(deviceId)
            ?: return@withContext Result.failure(IllegalArgumentException("Device not found."))

        if (device.pairState != PairState.PAIRED || !device.isReachable) {
            return@withContext Result.failure(IllegalStateException("Device must be paired and online."))
        }

        val transferId = UUID.randomUUID().toString()
        val safeName = PathValidator.sanitizeFileName(entry.name)
        val localCacheTarget = File(File(context.cacheDir, "remote_downloads").apply { mkdirs() }, safeName)

        transferDao.upsertTransfer(
            TransferRecordEntity(
                transferId = transferId,
                deviceId = deviceId,
                fileName = safeName,
                remotePath = entry.relativePath,
                localPathOrUri = safDestinationUri?.toString() ?: localCacheTarget.absolutePath,
                direction = "DOWNLOAD",
                totalBytes = entry.sizeBytes,
                transferredBytes = 0L,
                status = "IN_PROGRESS"
            )
        )

        _activeTransfer.value = ActiveTransferProgress(
            transferId = transferId,
            fileName = safeName,
            direction = "DOWNLOAD",
            transferredBytes = 0L,
            totalBytes = entry.sizeBytes.coerceAtLeast(1L)
        )

        val streamResult = try {
            val outStream = if (safDestinationUri != null) {
                context.contentResolver.openOutputStream(safDestinationUri)
                    ?: return@withContext Result.failure(IllegalStateException("Cannot open destination URI."))
            } else {
                FileOutputStream(localCacheTarget)
            }

            outStream.use { output ->
                lanEngine.downloadRemoteFile(
                    deviceId = deviceId,
                    remoteRelativePath = entry.relativePath,
                    supportsApexVfs = device.supportsApexVfs,
                    destinationOutput = output,
                    onProgress = { transferred, total ->
                        _activeTransfer.value = ActiveTransferProgress(
                            transferId = transferId,
                            fileName = safeName,
                            direction = "DOWNLOAD",
                            transferredBytes = transferred,
                            totalBytes = total.coerceAtLeast(1L)
                        )
                    }
                )
            }
        } catch (ce: kotlinx.coroutines.CancellationException) {
            transferDao.updateProgress(transferId, 0L, "CANCELLED", "Cancelled")
            _activeTransfer.value = null
            throw ce
        } finally {
            _activeTransfer.value = null
        }

        streamResult.fold(
            onSuccess = { sha256 ->
                transferDao.upsertTransfer(
                    TransferRecordEntity(
                        transferId = transferId,
                        deviceId = deviceId,
                        fileName = safeName,
                        remotePath = entry.relativePath,
                        localPathOrUri = safDestinationUri?.toString() ?: localCacheTarget.absolutePath,
                        direction = "DOWNLOAD",
                        totalBytes = entry.sizeBytes,
                        transferredBytes = entry.sizeBytes,
                        status = "COMPLETED",
                        sha256Checksum = sha256
                    )
                )
                logProtocolEvent("Downloaded '$safeName' (SHA-256 verified)")
                Result.success(if (safDestinationUri == null) localCacheTarget else null)
            },
            onFailure = { err ->
                transferDao.updateProgress(transferId, 0L, "FAILED", err.message)
                Result.failure(err)
            }
        )
    }

    /**
     * Sends a real file to the paired peer:
     * - Uses `kdeconnect.vfs.write.request` if the peer is another ApexConnect device browsing a specific folder.
     * - Otherwise sends standard KDE Connect `kdeconnect.share.request` with TLS payload streaming so
     *   desktop Linux/Windows/macOS KDE Connect saves the file directly into its Downloads folder!
     */
    suspend fun uploadFileToRemoteDirectory(
        deviceId: String,
        targetRemoteDir: String,
        sourceUri: Uri
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val device = deviceDao.getDeviceById(deviceId)
            ?: return@withContext Result.failure(IllegalArgumentException("Select a paired device first."))

        if (device.pairState != PairState.PAIRED) {
            return@withContext Result.failure(SecurityException("Pair with ${device.deviceName} first."))
        }
        if (!device.isReachable) {
            return@withContext Result.failure(IllegalStateException("${device.deviceName} is offline."))
        }

        val (displayName, _) = resolveUriMetadata(sourceUri)
        val safeName = PathValidator.sanitizeFileName(displayName)
        val payloadBytes = runCatching {
            context.contentResolver.openInputStream(sourceUri)?.use { it.readBytes() }
        }.getOrNull() ?: return@withContext Result.failure(IllegalStateException("Cannot read selected file."))

        val transferId = UUID.randomUUID().toString()
        val total = payloadBytes.size.toLong()
        _activeTransfer.value = ActiveTransferProgress(
            transferId = transferId,
            fileName = safeName,
            direction = "UPLOAD",
            transferredBytes = 0L,
            totalBytes = total.coerceAtLeast(1L)
        )

        val packet = if (device.supportsApexVfs) {
            NetworkPacket(
                type = NetworkPacket.TYPE_VFS_WRITE_REQUEST,
                body = JSONObject().apply {
                    put("requestId", transferId)
                    put("directory", targetRemoteDir)
                    put("filename", safeName)
                }
            )
        } else {
            // Standard KDE Connect SharePlugin file transfer — works out of the box with PC/Mac/Phone KDE Connect!
            NetworkPacket(
                type = NetworkPacket.TYPE_SHARE_REQUEST,
                body = JSONObject().apply {
                    put("filename", safeName)
                    put("creationTime", System.currentTimeMillis())
                    put("lastModified", System.currentTimeMillis())
                }
            )
        }

        val res = try {
            lanEngine.sendPacketToConnectedPeer(
                deviceId = deviceId,
                packet = packet,
                payloadBytes = payloadBytes,
                onProgress = { sent, max ->
                    _activeTransfer.value = ActiveTransferProgress(
                        transferId = transferId,
                        fileName = safeName,
                        direction = "UPLOAD",
                        transferredBytes = sent,
                        totalBytes = max.coerceAtLeast(1L)
                    )
                }
            )
        } finally {
            _activeTransfer.value = null
        }

        res.fold(
            onSuccess = {
                transferDao.upsertTransfer(
                    TransferRecordEntity(
                        transferId = transferId,
                        deviceId = deviceId,
                        fileName = safeName,
                        remotePath = targetRemoteDir,
                        localPathOrUri = sourceUri.toString(),
                        direction = "UPLOAD",
                        totalBytes = total,
                        transferredBytes = total,
                        status = "COMPLETED",
                        sha256Checksum = CryptoIdentityManager.bytesToHex(CryptoIdentityManager.sha256(payloadBytes))
                    )
                )
                logProtocolEvent("Sent file '$safeName' ($total bytes) -> ${device.deviceName}")
                Result.success(Unit)
            },
            onFailure = { err ->
                transferDao.upsertTransfer(
                    TransferRecordEntity(
                        transferId = transferId,
                        deviceId = deviceId,
                        fileName = safeName,
                        remotePath = targetRemoteDir,
                        localPathOrUri = sourceUri.toString(),
                        direction = "UPLOAD",
                        totalBytes = total,
                        transferredBytes = 0L,
                        status = "FAILED",
                        errorMessage = err.message
                    )
                )
                Result.failure(err)
            }
        )
    }

    // =========================================================================
    // FEATURE 2: PERSISTENT DEVICE SPACE (CHAT, IMAGES & NOTES)
    // =========================================================================

    suspend fun sendTextMessage(deviceId: String, rawText: String): Result<Unit> = withContext(Dispatchers.IO) {
        val trimmed = rawText.trim()
        if (trimmed.isEmpty()) return@withContext Result.failure(IllegalArgumentException("Message is empty."))
        val device = deviceDao.getDeviceById(deviceId)
            ?: return@withContext Result.failure(IllegalArgumentException("Select a paired device first."))

        if (device.pairState != PairState.PAIRED) {
            return@withContext Result.failure(SecurityException("Pair with ${device.deviceName} first."))
        }

        val now = System.currentTimeMillis()
        val uuid = UUID.randomUUID().toString()
        val entity = DeviceMessageEntity(
            messageUuid = uuid,
            deviceId = deviceId,
            senderDeviceId = identityManager.localDeviceId,
            isOutgoing = true,
            contentType = MessageContentType.TEXT,
            textBody = trimmed,
            syncStatus = if (device.isReachable) SyncStatus.SENDING else SyncStatus.QUEUED_OFFLINE,
            createdAtEpochMs = now,
            updatedAtEpochMs = now
        )
        spaceDao.upsertMessage(entity)

        if (device.isReachable) {
            dispatchMessageOverProtocol(device, entity)
        }
        Result.success(Unit)
    }

    suspend fun sendImageAttachmentMessage(
        deviceId: String,
        caption: String,
        sourceImageUri: Uri
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val device = deviceDao.getDeviceById(deviceId)
            ?: return@withContext Result.failure(IllegalArgumentException("Select a paired device first."))
        if (device.pairState != PairState.PAIRED) {
            return@withContext Result.failure(SecurityException("Pair with ${device.deviceName} first."))
        }

        val (displayName, mimeType) = resolveUriMetadata(sourceImageUri)
        val uuid = UUID.randomUUID().toString()
        val safeName = "${uuid.take(8)}_${PathValidator.sanitizeFileName(displayName)}"
        val durableFile = File(lanEngine.persistentAttachmentDir, safeName)

        val copyOk = runCatching {
            context.contentResolver.openInputStream(sourceImageUri)?.use { input ->
                durableFile.outputStream().use { out -> input.copyTo(out) }
            } ?: throw IllegalStateException("Cannot read selected image.")
        }
        if (copyOk.isFailure || !durableFile.exists() || durableFile.length() == 0L) {
            return@withContext Result.failure(
                copyOk.exceptionOrNull() ?: IllegalStateException("Image file empty or unreadable.")
            )
        }

        val sha256 = CryptoIdentityManager.sha256File(durableFile)
        val now = System.currentTimeMillis()
        val entity = DeviceMessageEntity(
            messageUuid = uuid,
            deviceId = deviceId,
            senderDeviceId = identityManager.localDeviceId,
            isOutgoing = true,
            contentType = MessageContentType.IMAGE,
            textBody = caption.trim(),
            attachmentLocalPath = durableFile.absolutePath,
            attachmentFileName = displayName,
            attachmentMimeType = mimeType.ifBlank { "image/jpeg" },
            attachmentSizeBytes = durableFile.length(),
            attachmentSha256 = sha256,
            attachmentAvailability = AttachmentAvailability.READY,
            syncStatus = if (device.isReachable) SyncStatus.SENDING else SyncStatus.QUEUED_OFFLINE,
            createdAtEpochMs = now,
            updatedAtEpochMs = now
        )
        spaceDao.upsertMessage(entity)

        if (device.isReachable) {
            dispatchMessageOverProtocol(device, entity)
        }
        Result.success(Unit)
    }

    private suspend fun dispatchMessageOverProtocol(
        device: PairedDeviceEntity,
        message: DeviceMessageEntity
    ) {
        if (!device.supportsSpaceSync) {
            // Interoperate natively with standard desktop/mobile KDE Connect using `kdeconnect.share.request` / `kdeconnect.ping`!
            val bytes = message.attachmentLocalPath?.let { File(it).takeIf(File::exists)?.readBytes() }
            val fallbackPacket = if (message.contentType == MessageContentType.IMAGE && bytes != null) {
                NetworkPacket(
                    type = NetworkPacket.TYPE_SHARE_REQUEST,
                    body = JSONObject().put("filename", message.attachmentFileName ?: "image.jpg")
                )
            } else {
                NetworkPacket(
                    type = NetworkPacket.TYPE_SHARE_REQUEST,
                    body = JSONObject().put("text", message.textBody)
                )
            }
            val res = lanEngine.sendPacketToConnectedPeer(device.deviceId, fallbackPacket, bytes)
            if (res.isSuccess) {
                spaceDao.updateMessageStatus(message.messageUuid, SyncStatus.DELIVERED)
                logProtocolEvent("Delivered message to KDE Connect SharePlugin on ${device.deviceName}")
            } else {
                spaceDao.updateMessageStatus(message.messageUuid, SyncStatus.FAILED, res.exceptionOrNull()?.message)
            }
            return
        }

        val payloadBytes = if (message.contentType == MessageContentType.IMAGE) {
            val file = message.attachmentLocalPath?.let(::File)
            if (file == null || !file.exists()) {
                spaceDao.upsertMessage(
                    message.copy(
                        attachmentAvailability = AttachmentAvailability.MISSING_OR_DELETED,
                        syncStatus = SyncStatus.FAILED,
                        errorReason = "Attachment missing from local disk."
                    )
                )
                return
            }
            file.readBytes()
        } else null

        val packet = NetworkPacket(
            type = NetworkPacket.TYPE_SPACE_MESSAGE,
            body = JSONObject().apply {
                put("uuid", message.messageUuid)
                put("senderDeviceId", message.senderDeviceId)
                put("contentType", message.contentType.name)
                put("text", message.textBody)
                put("createdAt", message.createdAtEpochMs)
                put("updatedAt", message.updatedAtEpochMs)
                if (message.contentType == MessageContentType.IMAGE) {
                    put("filename", message.attachmentFileName ?: "image.jpg")
                    put("mimeType", message.attachmentMimeType ?: "image/jpeg")
                    put("sizeBytes", message.attachmentSizeBytes)
                    put("sha256", message.attachmentSha256 ?: "")
                }
            }
        )

        val res = lanEngine.sendPacketToConnectedPeer(device.deviceId, packet, payloadBytes)
        if (res.isSuccess) {
            spaceDao.updateMessageStatus(message.messageUuid, SyncStatus.DELIVERED)
        } else {
            spaceDao.updateMessageStatus(message.messageUuid, SyncStatus.FAILED, res.exceptionOrNull()?.message)
        }
    }

    suspend fun saveOrUpdateNote(
        deviceId: String,
        existingNoteUuid: String?,
        title: String,
        content: String,
        isPinned: Boolean
    ): Result<Unit> = withContext(Dispatchers.IO) {
        if (title.isBlank() && content.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Enter a title or note body."))
        }
        val device = deviceDao.getDeviceById(deviceId)
            ?: return@withContext Result.failure(IllegalArgumentException("Select a paired device first."))

        val now = System.currentTimeMillis()
        val existing = existingNoteUuid?.let { spaceDao.getNoteByUuid(it) }
        val note = DeviceNoteEntity(
            noteUuid = existing?.noteUuid ?: UUID.randomUUID().toString(),
            deviceId = deviceId,
            authorDeviceId = identityManager.localDeviceId,
            title = title.trim().ifBlank { "Untitled Note" },
            content = content.trim(),
            isPinned = isPinned,
            isDeletedTombstone = false,
            revision = (existing?.revision ?: 0) + 1,
            syncStatus = if (device.isReachable && device.pairState == PairState.PAIRED) {
                SyncStatus.SENDING
            } else {
                SyncStatus.QUEUED_OFFLINE
            },
            createdAtEpochMs = existing?.createdAtEpochMs ?: now,
            updatedAtEpochMs = now
        )
        spaceDao.upsertNote(note)

        if (device.isReachable && device.pairState == PairState.PAIRED) {
            dispatchNoteOverProtocol(device, note)
        }
        Result.success(Unit)
    }

    suspend fun toggleNotePin(note: DeviceNoteEntity) = withContext(Dispatchers.IO) {
        saveOrUpdateNote(note.deviceId, note.noteUuid, note.title, note.content, !note.isPinned)
    }

    suspend fun deleteNote(note: DeviceNoteEntity) = withContext(Dispatchers.IO) {
        val tombstone = note.copy(
            isDeletedTombstone = true,
            revision = note.revision + 1,
            syncStatus = SyncStatus.QUEUED_OFFLINE,
            updatedAtEpochMs = System.currentTimeMillis()
        )
        spaceDao.upsertNote(tombstone)
        val device = deviceDao.getDeviceById(note.deviceId)
        if (device != null && device.isReachable && device.pairState == PairState.PAIRED) {
            dispatchNoteOverProtocol(device, tombstone)
        }
    }

    private suspend fun dispatchNoteOverProtocol(device: PairedDeviceEntity, note: DeviceNoteEntity) {
        if (!device.supportsSpaceSync) {
            // Also share non-deleted note text to standard KDE Connect desktop SharePlugin!
            if (!note.isDeletedTombstone) {
                val sharePkt = NetworkPacket(
                    type = NetworkPacket.TYPE_SHARE_REQUEST,
                    body = JSONObject().put("text", "${note.title}\n\n${note.content}")
                )
                val sent = lanEngine.sendPacketToConnectedPeer(device.deviceId, sharePkt)
                spaceDao.updateNoteSyncStatus(
                    note.noteUuid,
                    if (sent.isSuccess) SyncStatus.DELIVERED else SyncStatus.FAILED
                )
            } else {
                spaceDao.updateNoteSyncStatus(note.noteUuid, SyncStatus.SYNCED)
            }
            return
        }

        val packet = NetworkPacket(
            type = if (note.isDeletedTombstone) NetworkPacket.TYPE_SPACE_DELETE else NetworkPacket.TYPE_SPACE_NOTE,
            body = JSONObject().apply {
                put("uuid", note.noteUuid)
                put("authorDeviceId", note.authorDeviceId)
                put("title", note.title)
                put("content", note.content)
                put("isPinned", note.isPinned)
                put("isDeleted", note.isDeletedTombstone)
                put("revision", note.revision)
                put("createdAt", note.createdAtEpochMs)
                put("updatedAt", note.updatedAtEpochMs)
            }
        )
        val res = lanEngine.sendPacketToConnectedPeer(device.deviceId, packet)
        spaceDao.updateNoteSyncStatus(
            note.noteUuid,
            if (res.isSuccess) SyncStatus.DELIVERED else SyncStatus.FAILED
        )
    }

    suspend fun deleteSingleMessage(message: DeviceMessageEntity) = withContext(Dispatchers.IO) {
        message.attachmentLocalPath?.let { runCatching { File(it).delete() } }
        spaceDao.deleteMessageByUuid(message.messageUuid)
    }

    suspend fun clearConversationHistory(deviceId: String) = withContext(Dispatchers.IO) {
        spaceDao.clearConversationForDevice(deviceId)
    }

    suspend fun synchronizePendingSpaceItems(deviceId: String) = withContext(Dispatchers.IO) {
        val device = deviceDao.getDeviceById(deviceId) ?: return@withContext
        if (!device.isReachable || device.pairState != PairState.PAIRED) return@withContext

        for (msg in spaceDao.getPendingMessagesForDevice(deviceId)) {
            dispatchMessageOverProtocol(device, msg)
        }
        for (note in spaceDao.getPendingNotesForDevice(deviceId)) {
            dispatchNoteOverProtocol(device, note)
        }
    }

    // =========================================================================
    // INCOMING REAL PACKET HANDLERS FROM CONNECTED PEERS
    // =========================================================================

    suspend fun handleIncomingPacketFromPeer(
        senderDeviceId: String,
        packet: NetworkPacket,
        payloadBytes: ByteArray?
    ) = withContext(Dispatchers.IO) {
        val device = deviceDao.getDeviceById(senderDeviceId) ?: return@withContext

        if (packet.type == NetworkPacket.TYPE_PAIR) {
            val wantsPair = packet.getBoolean("pair", false)
            if (wantsPair) {
                if (device.pairState == PairState.PAIR_REQUESTED_BY_US) {
                    // Mutual pair accepted by remote KDE Connect device!
                    deviceDao.updatePairState(senderDeviceId, PairState.PAIRED)
                    logProtocolEvent("Pairing accepted by ${device.deviceName}!")
                    _incomingAlerts.tryEmit("Paired with ${device.deviceName}")
                    onTrustedPeerSessionReady(senderDeviceId)
                } else {
                    deviceDao.updatePairState(senderDeviceId, PairState.PAIR_REQUESTED_BY_PEER)
                    _incomingAlerts.tryEmit("Pairing request from ${device.deviceName} (${device.verificationCode})")
                }
            } else {
                deviceDao.updatePairState(senderDeviceId, PairState.UNPAIRED)
                _incomingAlerts.tryEmit("${device.deviceName} unpaired")
            }
            return@withContext
        }

        // Reject non-pairing packets from untrusted peers
        if (device.pairState != PairState.PAIRED) return@withContext

        when (packet.type) {
            NetworkPacket.TYPE_BATTERY -> {
                val charge = packet.getInt("currentCharge", -1)
                val charging = packet.getBoolean("isCharging", false)
                if (charge in 0..100) {
                    deviceDao.updateBattery(senderDeviceId, charge, charging)
                }
            }

            NetworkPacket.TYPE_BATTERY_REQUEST -> {
                sendLocalAndroidBatteryToPeer(senderDeviceId)
            }

            NetworkPacket.TYPE_CLIPBOARD, NetworkPacket.TYPE_CLIPBOARD_CONNECT -> {
                val clip = packet.getString("content")
                if (clip.isNotBlank()) {
                    deviceDao.updateClipboard(senderDeviceId, clip)
                    withContext(Dispatchers.Main) {
                        runCatching {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            cm?.setPrimaryClip(ClipData.newPlainText("KDE Connect", clip))
                        }
                    }
                    logProtocolEvent("Received clipboard from ${device.deviceName}")
                }
            }

            NetworkPacket.TYPE_PING -> {
                val msg = packet.getString("message", "Ping!")
                vibrateAndPlayNotificationSound()
                _incomingAlerts.tryEmit("Ping from ${device.deviceName}: $msg")
                // Also record incoming ping with custom message in Device Space so user doesn't miss it
                if (packet.body.has("message") && msg.isNotBlank()) {
                    val now = System.currentTimeMillis()
                    spaceDao.insertMessageIgnoreDuplicate(
                        DeviceMessageEntity(
                            messageUuid = "ping_${packet.id}_$senderDeviceId",
                            deviceId = senderDeviceId,
                            senderDeviceId = senderDeviceId,
                            isOutgoing = false,
                            contentType = MessageContentType.TEXT,
                            textBody = msg,
                            syncStatus = SyncStatus.SYNCED,
                            createdAtEpochMs = now,
                            updatedAtEpochMs = now
                        )
                    )
                }
            }

            NetworkPacket.TYPE_FIND_MY_PHONE -> {
                vibrateAndPlayRingtoneAlert()
                _incomingAlerts.tryEmit("${device.deviceName} is ringing your phone!")
            }

            NetworkPacket.TYPE_MPRIS -> {
                val players = packet.getStringList("playerList")
                if (players.isNotEmpty() && device.mediaPlayerName.isBlank()) {
                    val chosen = players.first()
                    deviceDao.updateMediaState(
                        senderDeviceId,
                        chosen,
                        device.isMediaPlaying,
                        device.mediaVolume,
                        device.mediaTitle,
                        device.mediaArtist
                    )
                    // Subscribe to metadata updates from this player
                    lanEngine.sendPacketToConnectedPeer(
                        senderDeviceId,
                        NetworkPacket(
                            type = NetworkPacket.TYPE_MPRIS_REQUEST,
                            body = JSONObject()
                                .put("player", chosen)
                                .put("requestNowPlaying", true)
                                .put("requestVolume", true)
                        )
                    )
                } else {
                    val player = packet.getString("player", device.mediaPlayerName)
                    val title = packet.getString("title", packet.getString("nowPlaying", device.mediaTitle))
                    val artist = packet.getString("artist", device.mediaArtist)
                    val playing = if (packet.body.has("isPlaying")) packet.getBoolean("isPlaying") else device.isMediaPlaying
                    val vol = if (packet.body.has("volume")) packet.getInt("volume", device.mediaVolume) else device.mediaVolume
                    deviceDao.updateMediaState(senderDeviceId, player, playing, vol, title, artist)
                }
            }

            NetworkPacket.TYPE_RUNCOMMAND -> {
                val cmdObj = packet.body.optJSONObject("commandList")
                if (cmdObj != null) {
                    val parsedArray = JSONArray()
                    val keys = cmdObj.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val item = cmdObj.optJSONObject(key) ?: continue
                        parsedArray.put(
                            JSONObject().apply {
                                put("key", key)
                                put("name", item.optString("name", key))
                                put("command", item.optString("command", ""))
                            }
                        )
                    }
                    deviceDao.updateRemoteCommands(senderDeviceId, parsedArray.toString())
                }
            }

            // Incoming standard KDE Connect SharePlugin (text, URL, or binary file/image from PC/Mac!)
            NetworkPacket.TYPE_SHARE_REQUEST -> {
                val now = System.currentTimeMillis()
                val uuid = "share_${packet.id}_${UUID.randomUUID().toString().take(6)}"
                if (payloadBytes != null && payloadBytes.isNotEmpty()) {
                    val rawName = packet.getString("filename", "received_${now}.bin")
                    val safeName = PathValidator.sanitizeFileName(rawName)
                    val downloadsDir = File(lanEngine.authorizedVaultRoot, "Downloads").apply { mkdirs() }
                    val savedFile = File(downloadsDir, safeName)
                    savedFile.writeBytes(payloadBytes)

                    val isImg = safeName.endsWith(".png", true) ||
                        safeName.endsWith(".jpg", true) ||
                        safeName.endsWith(".jpeg", true) ||
                        safeName.endsWith(".webp", true)

                    if (isImg) {
                        val spaceCopy = File(lanEngine.persistentAttachmentDir, "${uuid}_$safeName")
                        savedFile.copyTo(spaceCopy, overwrite = true)
                        spaceDao.insertMessageIgnoreDuplicate(
                            DeviceMessageEntity(
                                messageUuid = uuid,
                                deviceId = senderDeviceId,
                                senderDeviceId = senderDeviceId,
                                isOutgoing = false,
                                contentType = MessageContentType.IMAGE,
                                textBody = "",
                                attachmentLocalPath = spaceCopy.absolutePath,
                                attachmentFileName = safeName,
                                attachmentMimeType = "image/*",
                                attachmentSizeBytes = spaceCopy.length(),
                                attachmentSha256 = CryptoIdentityManager.sha256File(spaceCopy),
                                attachmentAvailability = AttachmentAvailability.READY,
                                syncStatus = SyncStatus.SYNCED,
                                createdAtEpochMs = now,
                                updatedAtEpochMs = now
                            )
                        )
                    } else {
                        spaceDao.insertMessageIgnoreDuplicate(
                            DeviceMessageEntity(
                                messageUuid = uuid,
                                deviceId = senderDeviceId,
                                senderDeviceId = senderDeviceId,
                                isOutgoing = false,
                                contentType = MessageContentType.TEXT,
                                textBody = "Received file: $safeName (${payloadBytes.size} bytes)",
                                syncStatus = SyncStatus.SYNCED,
                                createdAtEpochMs = now,
                                updatedAtEpochMs = now
                            )
                        )
                    }
                    _incomingAlerts.tryEmit("Received '$safeName' from ${device.deviceName}")
                } else {
                    val sharedTextOrUrl = packet.getString("text").ifBlank { packet.getString("url") }
                    if (sharedTextOrUrl.isNotBlank()) {
                        spaceDao.insertMessageIgnoreDuplicate(
                            DeviceMessageEntity(
                                messageUuid = uuid,
                                deviceId = senderDeviceId,
                                senderDeviceId = senderDeviceId,
                                isOutgoing = false,
                                contentType = MessageContentType.TEXT,
                                textBody = sharedTextOrUrl,
                                syncStatus = SyncStatus.SYNCED,
                                createdAtEpochMs = now,
                                updatedAtEpochMs = now
                            )
                        )
                        _incomingAlerts.tryEmit("New shared message from ${device.deviceName}")
                    }
                }
            }

            NetworkPacket.TYPE_SPACE_ACK -> {
                val uuid = packet.getString("uuid")
                if (uuid.isNotBlank()) {
                    spaceDao.updateMessageStatus(uuid, SyncStatus.SYNCED)
                    spaceDao.updateNoteSyncStatus(uuid, SyncStatus.SYNCED)
                }
            }

            NetworkPacket.TYPE_SPACE_MESSAGE -> {
                val uuid = packet.getString("uuid")
                if (uuid.isBlank()) return@withContext
                if (spaceDao.getMessageByUuid(uuid) == null) {
                    val cType = runCatching {
                        MessageContentType.valueOf(packet.getString("contentType", "TEXT"))
                    }.getOrDefault(MessageContentType.TEXT)

                    var savedPath: String? = null
                    var availability = AttachmentAvailability.NONE
                    if (cType == MessageContentType.IMAGE) {
                        if (payloadBytes != null && payloadBytes.isNotEmpty()) {
                            val safeFile = PathValidator.sanitizeFileName(packet.getString("filename", "img.jpg"))
                            val dest = File(lanEngine.persistentAttachmentDir, "${uuid.take(8)}_$safeFile")
                            dest.writeBytes(payloadBytes)
                            savedPath = dest.absolutePath
                            availability = AttachmentAvailability.READY
                        } else {
                            availability = AttachmentAvailability.MISSING_OR_DELETED
                        }
                    }

                    spaceDao.insertMessageIgnoreDuplicate(
                        DeviceMessageEntity(
                            messageUuid = uuid,
                            deviceId = senderDeviceId,
                            senderDeviceId = senderDeviceId,
                            isOutgoing = false,
                            contentType = cType,
                            textBody = packet.getString("text"),
                            attachmentLocalPath = savedPath,
                            attachmentFileName = packet.getString("filename").ifBlank { null },
                            attachmentMimeType = packet.getString("mimeType").ifBlank { null },
                            attachmentSizeBytes = packet.getLong("sizeBytes", payloadBytes?.size?.toLong() ?: 0L),
                            attachmentSha256 = packet.getString("sha256").ifBlank { null },
                            attachmentAvailability = availability,
                            syncStatus = SyncStatus.SYNCED,
                            createdAtEpochMs = packet.getLong("createdAt", System.currentTimeMillis()),
                            updatedAtEpochMs = packet.getLong("updatedAt", System.currentTimeMillis())
                        )
                    )
                }
                lanEngine.sendPacketToConnectedPeer(
                    senderDeviceId,
                    NetworkPacket(type = NetworkPacket.TYPE_SPACE_ACK, body = JSONObject().put("uuid", uuid))
                )
            }

            NetworkPacket.TYPE_SPACE_NOTE, NetworkPacket.TYPE_SPACE_DELETE -> {
                val uuid = packet.getString("uuid")
                if (uuid.isBlank()) return@withContext
                val incomingUpdatedAt = packet.getLong("updatedAt", System.currentTimeMillis())
                val incomingRev = packet.getInt("revision", 1)
                val incomingAuthor = packet.getString("authorDeviceId", senderDeviceId)
                val existing = spaceDao.getNoteByUuid(uuid)

                val shouldAccept = if (existing == null) {
                    true
                } else if (incomingUpdatedAt != existing.updatedAtEpochMs) {
                    incomingUpdatedAt > existing.updatedAtEpochMs
                } else if (incomingRev != existing.revision) {
                    incomingRev > existing.revision
                } else {
                    incomingAuthor >= existing.authorDeviceId
                }

                if (shouldAccept) {
                    spaceDao.upsertNote(
                        DeviceNoteEntity(
                            noteUuid = uuid,
                            deviceId = senderDeviceId,
                            authorDeviceId = incomingAuthor,
                            title = packet.getString("title", "Shared Note"),
                            content = packet.getString("content", ""),
                            isPinned = packet.getBoolean("isPinned", false),
                            isDeletedTombstone = packet.type == NetworkPacket.TYPE_SPACE_DELETE ||
                                packet.getBoolean("isDeleted", false),
                            revision = incomingRev,
                            syncStatus = SyncStatus.SYNCED,
                            createdAtEpochMs = packet.getLong("createdAt", existing?.createdAtEpochMs ?: incomingUpdatedAt),
                            updatedAtEpochMs = incomingUpdatedAt
                        )
                    )
                }
                lanEngine.sendPacketToConnectedPeer(
                    senderDeviceId,
                    NetworkPacket(type = NetworkPacket.TYPE_SPACE_ACK, body = JSONObject().put("uuid", uuid))
                )
            }
        }
    }

    // =========================================================================
    // REAL OUTGOING KDE CONNECT PLUGINS
    // =========================================================================

    suspend fun sendPing(deviceId: String, customMessage: String = ""): Result<Unit> =
        sendTrustedPluginPacket(
            deviceId = deviceId,
            packet = NetworkPacket(
                type = NetworkPacket.TYPE_PING,
                body = JSONObject().apply {
                    if (customMessage.isNotBlank()) put("message", customMessage)
                }
            ),
            eventLog = "Sent Ping"
        )

    suspend fun sendFindMyDeviceRing(deviceId: String): Result<Unit> =
        sendTrustedPluginPacket(
            deviceId = deviceId,
            packet = NetworkPacket(type = NetworkPacket.TYPE_FIND_MY_PHONE, body = JSONObject()),
            eventLog = "Triggered remote ring (Find My Device)"
        )

    suspend fun pushClipboard(deviceId: String, clipboardText: String): Result<Unit> = withContext(Dispatchers.IO) {
        if (clipboardText.isBlank()) return@withContext Result.failure(IllegalArgumentException("Clipboard is empty."))
        val res = sendTrustedPluginPacket(
            deviceId = deviceId,
            packet = NetworkPacket(
                type = NetworkPacket.TYPE_CLIPBOARD,
                body = JSONObject().put("content", clipboardText)
            ),
            eventLog = "Synced clipboard"
        )
        if (res.isSuccess) {
            deviceDao.updateClipboard(deviceId, clipboardText)
        }
        res
    }

    suspend fun sendMousepadInput(
        deviceId: String,
        dx: Float = 0f,
        dy: Float = 0f,
        singleClick: Boolean = false,
        rightClick: Boolean = false,
        keyText: String? = null
    ): Result<Unit> {
        val body = JSONObject().apply {
            if (dx != 0f || dy != 0f) {
                put("dx", dx.toDouble())
                put("dy", dy.toDouble())
            }
            if (singleClick) put("singleclick", true)
            if (rightClick) put("rightclick", true)
            if (!keyText.isNullOrEmpty()) put("key", keyText)
        }
        return sendTrustedPluginPacket(
            deviceId = deviceId,
            packet = NetworkPacket(type = NetworkPacket.TYPE_MOUSEPAD_REQUEST, body = body),
            eventLog = null
        )
    }

    suspend fun sendMprisAction(deviceId: String, action: String, newVolume: Int? = null): Result<Unit> =
        withContext(Dispatchers.IO) {
            val device = deviceDao.getDeviceById(deviceId)
                ?: return@withContext Result.failure(IllegalArgumentException("Select a paired device first."))
            val body = JSONObject().apply {
                if (device.mediaPlayerName.isNotBlank()) {
                    put("player", device.mediaPlayerName)
                }
                if (newVolume != null) {
                    put("setVolume", newVolume.coerceIn(0, 100))
                } else {
                    put("action", action)
                }
            }
            sendTrustedPluginPacket(
                deviceId = deviceId,
                packet = NetworkPacket(type = NetworkPacket.TYPE_MPRIS_REQUEST, body = body),
                eventLog = "MPRIS: ${newVolume?.let { "Volume $it%" } ?: action}"
            )
        }

    suspend fun triggerRemoteCommand(deviceId: String, commandKey: String, commandLabel: String): Result<Unit> =
        sendTrustedPluginPacket(
            deviceId = deviceId,
            packet = NetworkPacket(
                type = NetworkPacket.TYPE_RUNCOMMAND_REQUEST,
                body = JSONObject().put("key", commandKey)
            ),
            eventLog = "Triggered '$commandLabel'"
        )

    private suspend fun sendTrustedPluginPacket(
        deviceId: String,
        packet: NetworkPacket,
        eventLog: String?
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val device = deviceDao.getDeviceById(deviceId)
            ?: return@withContext Result.failure(IllegalArgumentException("Select a device first."))
        if (device.pairState != PairState.PAIRED) {
            return@withContext Result.failure(SecurityException("Pair with ${device.deviceName} first."))
        }
        if (!device.isReachable) {
            return@withContext Result.failure(IllegalStateException("${device.deviceName} is offline."))
        }
        val res = lanEngine.sendPacketToConnectedPeer(deviceId, packet)
        if (res.isSuccess && eventLog != null) {
            logProtocolEvent("$eventLog -> ${device.deviceName}")
        }
        res
    }

    private fun vibrateAndPlayNotificationSound() {
        runCatching {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            RingtoneManager.getRingtone(context, uri)?.play()
            vibratePattern(80L)
        }
    }

    private fun vibrateAndPlayRingtoneAlert() {
        runCatching {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            RingtoneManager.getRingtone(context, uri)?.play()
            vibratePattern(500L)
        }
    }

    private fun vibratePattern(durationMs: Long) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator?.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                val v = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v?.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
                }
            }
        }
    }

    private fun resolveUriMetadata(uri: Uri): Pair<String, String> {
        val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
        var name = "file_${System.currentTimeMillis()}"
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) {
                            val resolved = cursor.getString(idx)
                            if (!resolved.isNullOrBlank()) name = resolved
                        }
                    }
                }
        }
        return name to mime
    }
}
