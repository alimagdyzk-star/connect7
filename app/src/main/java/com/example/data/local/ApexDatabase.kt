package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

class ApexTypeConverters {
    @TypeConverter
    fun fromPairState(value: PairState): String = value.name

    @TypeConverter
    fun toPairState(value: String): PairState = runCatching { PairState.valueOf(value) }.getOrDefault(PairState.UNPAIRED)

    @TypeConverter
    fun fromSyncStatus(value: SyncStatus): String = value.name

    @TypeConverter
    fun toSyncStatus(value: String): SyncStatus = runCatching { SyncStatus.valueOf(value) }.getOrDefault(SyncStatus.QUEUED_OFFLINE)

    @TypeConverter
    fun fromMessageContentType(value: MessageContentType): String = value.name

    @TypeConverter
    fun toMessageContentType(value: String): MessageContentType =
        runCatching { MessageContentType.valueOf(value) }.getOrDefault(MessageContentType.TEXT)

    @TypeConverter
    fun fromAttachmentAvailability(value: AttachmentAvailability): String = value.name

    @TypeConverter
    fun toAttachmentAvailability(value: String): AttachmentAvailability =
        runCatching { AttachmentAvailability.valueOf(value) }.getOrDefault(AttachmentAvailability.NONE)
}

@Database(
    entities = [
        PairedDeviceEntity::class,
        DeviceMessageEntity::class,
        DeviceNoteEntity::class,
        TransferRecordEntity::class
    ],
    version = 1,
    exportSchema = false
)
@TypeConverters(ApexTypeConverters::class)
abstract class ApexDatabase : RoomDatabase() {
    abstract fun deviceDao(): DeviceDao
    abstract fun spaceDao(): SpaceDao
    abstract fun transferDao(): TransferDao

    companion object {
        @Volatile
        private var INSTANCE: ApexDatabase? = null

        fun getInstance(context: Context): ApexDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    ApexDatabase::class.java,
                    "apex_connect_vault.db"
                )
                    .fallbackToDestructiveMigration(true)
                    .build()
                    .also { INSTANCE = it }
            }
        }

        fun createInMemory(context: Context): ApexDatabase {
            return Room.inMemoryDatabaseBuilder(
                context.applicationContext,
                ApexDatabase::class.java
            ).allowMainThreadQueries().build()
        }
    }
}
