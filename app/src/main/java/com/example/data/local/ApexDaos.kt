package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface DeviceDao {
    @Query("SELECT * FROM paired_devices ORDER BY isReachable DESC, pairState DESC, deviceName ASC")
    fun observeAllDevices(): Flow<List<PairedDeviceEntity>>

    @Query("SELECT * FROM paired_devices")
    suspend fun getAllDevicesOnce(): List<PairedDeviceEntity>

    @Query("SELECT * FROM paired_devices WHERE deviceId = :deviceId LIMIT 1")
    suspend fun getDeviceById(deviceId: String): PairedDeviceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDevice(device: PairedDeviceEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDevices(devices: List<PairedDeviceEntity>)

    @Query("UPDATE paired_devices SET pairState = :pairState, lastSeenEpochMs = :timestamp WHERE deviceId = :deviceId")
    suspend fun updatePairState(deviceId: String, pairState: PairState, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE paired_devices SET isReachable = :reachable, lastSeenEpochMs = :timestamp WHERE deviceId = :deviceId")
    suspend fun updateReachability(deviceId: String, reachable: Boolean, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE paired_devices SET batteryLevel = :level, isCharging = :charging WHERE deviceId = :deviceId")
    suspend fun updateBattery(deviceId: String, level: Int, charging: Boolean)

    @Query("UPDATE paired_devices SET isMediaPlaying = :playing, mediaVolume = :volume, mediaTitle = :title, mediaArtist = :artist WHERE deviceId = :deviceId")
    suspend fun updateMediaState(deviceId: String, playing: Boolean, volume: Int, title: String, artist: String)

    @Query("UPDATE paired_devices SET lastClipboardSnippet = :snippet WHERE deviceId = :deviceId")
    suspend fun updateClipboard(deviceId: String, snippet: String)

    @Query("DELETE FROM paired_devices WHERE deviceId = :deviceId")
    suspend fun deleteDevice(deviceId: String)
}

@Dao
interface SpaceDao {
    @Query("SELECT * FROM device_messages WHERE deviceId = :deviceId ORDER BY createdAtEpochMs ASC")
    fun observeMessagesForDevice(deviceId: String): Flow<List<DeviceMessageEntity>>

    @Query("SELECT * FROM device_messages WHERE messageUuid = :uuid LIMIT 1")
    suspend fun getMessageByUuid(uuid: String): DeviceMessageEntity?

    @Query("SELECT * FROM device_messages WHERE deviceId = :deviceId AND syncStatus IN ('QUEUED_OFFLINE', 'FAILED') ORDER BY createdAtEpochMs ASC")
    suspend fun getPendingMessagesForDevice(deviceId: String): List<DeviceMessageEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMessageIgnoreDuplicate(message: DeviceMessageEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMessage(message: DeviceMessageEntity)

    @Query("UPDATE device_messages SET syncStatus = :status, errorReason = :error, updatedAtEpochMs = :now WHERE messageUuid = :uuid")
    suspend fun updateMessageStatus(
        uuid: String,
        status: SyncStatus,
        error: String? = null,
        now: Long = System.currentTimeMillis()
    )

    @Query("DELETE FROM device_messages WHERE messageUuid = :uuid")
    suspend fun deleteMessageByUuid(uuid: String)

    @Query("DELETE FROM device_messages WHERE deviceId = :deviceId")
    suspend fun clearConversationForDevice(deviceId: String)

    @Query("SELECT * FROM device_notes WHERE deviceId = :deviceId AND isDeletedTombstone = 0 ORDER BY isPinned DESC, updatedAtEpochMs DESC")
    fun observeNotesForDevice(deviceId: String): Flow<List<DeviceNoteEntity>>

    @Query("SELECT * FROM device_notes WHERE noteUuid = :uuid LIMIT 1")
    suspend fun getNoteByUuid(uuid: String): DeviceNoteEntity?

    @Query("SELECT * FROM device_notes WHERE deviceId = :deviceId AND syncStatus IN ('QUEUED_OFFLINE', 'FAILED') ORDER BY updatedAtEpochMs ASC")
    suspend fun getPendingNotesForDevice(deviceId: String): List<DeviceNoteEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertNote(note: DeviceNoteEntity)

    @Update
    suspend fun updateNote(note: DeviceNoteEntity)

    @Query("UPDATE device_notes SET syncStatus = :status WHERE noteUuid = :uuid")
    suspend fun updateNoteSyncStatus(uuid: String, status: SyncStatus)

    @Query("DELETE FROM device_notes WHERE deviceId = :deviceId")
    suspend fun clearNotesForDevice(deviceId: String)
}

@Dao
interface TransferDao {
    @Query("SELECT * FROM transfer_records WHERE deviceId = :deviceId ORDER BY startedAtEpochMs DESC LIMIT 25")
    fun observeTransfersForDevice(deviceId: String): Flow<List<TransferRecordEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTransfer(record: TransferRecordEntity)

    @Query("UPDATE transfer_records SET transferredBytes = :bytes, status = :status, errorMessage = :error WHERE transferId = :id")
    suspend fun updateProgress(id: String, bytes: Long, status: String, error: String? = null)
}
