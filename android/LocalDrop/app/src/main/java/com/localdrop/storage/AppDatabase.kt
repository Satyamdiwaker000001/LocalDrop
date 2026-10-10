package com.localdrop.storage

import androidx.room.Database
import androidx.room.RoomDatabase

// M01 Foundation: database boundary only.
// Entities are registered so the schema is frozen early.
// No DAO methods in M01 (repositories/queries arrive with M02+ features).
@Database(
    entities = [
        DeviceEntity::class,
        PairingEntity::class,
        TransferEntity::class,
        CheckpointEntity::class,
        SettingsEntity::class,
        HistoryEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun checkpointDao(): CheckpointDao
}
