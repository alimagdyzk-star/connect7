package com.example.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class PairState {
    UNPAIRED,
    PAIR_REQUESTED_BY_US,
    PAIR_REQUESTED_BY_PEER,
    PAIRED
}

enum class SyncStatus {
    QUEUED_OFFLINE,
    SENDING,
    DELIVERED,
    SYNCED,
    FAILED
}

enum class MessageContentType {
    TEXT,
    IMAGE
}

enum class AttachmentAvailability {
    NONE,
    READY,
    TRANSFERRING,
    MISSING_OR_DELETED
}

@Entity(tableName = "paired_devices")
data class PairedDeviceEntity(
    @PrimaryKey val deviceId: String,
    val deviceName: String,
    val deviceType: String,
    val platformOs: String,
    val ipAddress: String,
    val tcpPort: Int = 1716,
    val protocolVersion: Int = 8,
    val pairState: PairState = PairState.UNPAIRED,
    val isReachable: Boolean = false,
    val certificateFingerprint: String,
    val verificationCode: String,
    val pinnedCertificateBase64: String? = null,
    val batteryLevel: Int = -1, // -1 means peer hasn't reported battery yet
    val isCharging: Boolean = false,
    val supportsRemoteFs: Boolean = false,
    val supportsKdeSftp: Boolean = false,
    val supportsApexVfs: Boolean = false,
    val supportsUploadFs: Boolean = true,
    val supportsSpaceSync: Boolean = false,
    val supportsMousepad: Boolean = true,
    val supportsMpris: Boolean = true,
    val mediaPlayerName: String = "",
    val mediaTitle: String = "",
    val mediaArtist: String = "",
    val isMediaPlaying: Boolean = false,
    val mediaVolume: Int = 50,
    val lastClipboardSnippet: String = "",
    val remoteCommandsJson: String = "[]", // Real commands received via `kdeconnect.runcommand`
    val lastSeenEpochMs: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "device_messages",
    indices = [Index(value = ["deviceId", "createdAtEpochMs"])]
)
data class DeviceMessageEntity(
    @PrimaryKey val messageUuid: String,
    val deviceId: String,
    val senderDeviceId: String,
    val isOutgoing: Boolean,
    val contentType: MessageContentType,
    val textBody: String,
    val attachmentLocalPath: String? = null,
    val attachmentFileName: String? = null,
    val attachmentMimeType: String? = null,
    val attachmentSizeBytes: Long = 0L,
    val attachmentSha256: String? = null,
    val attachmentAvailability: AttachmentAvailability = AttachmentAvailability.NONE,
    val syncStatus: SyncStatus = SyncStatus.QUEUED_OFFLINE,
    val errorReason: String? = null,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    val updatedAtEpochMs: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "device_notes",
    indices = [Index(value = ["deviceId", "updatedAtEpochMs"])]
)
data class DeviceNoteEntity(
    @PrimaryKey val noteUuid: String,
    val deviceId: String,
    val authorDeviceId: String,
    val title: String,
    val content: String,
    val isPinned: Boolean = false,
    val isDeletedTombstone: Boolean = false,
    val revision: Int = 1,
    val syncStatus: SyncStatus = SyncStatus.QUEUED_OFFLINE,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    val updatedAtEpochMs: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "transfer_records",
    indices = [Index(value = ["deviceId", "startedAtEpochMs"])]
)
data class TransferRecordEntity(
    @PrimaryKey val transferId: String,
    val deviceId: String,
    val fileName: String,
    val remotePath: String,
    val localPathOrUri: String?,
    val direction: String,
    val totalBytes: Long,
    val transferredBytes: Long,
    val status: String,
    val sha256Checksum: String? = null,
    val errorMessage: String? = null,
    val startedAtEpochMs: Long = System.currentTimeMillis()
)
