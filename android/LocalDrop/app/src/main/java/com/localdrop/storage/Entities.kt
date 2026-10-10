package com.localdrop.storage

import androidx.room.Entity
import androidx.room.PrimaryKey

// M01 Foundation: persistence entity boundaries (SQLite/Room preferred).
// Table definitions only. No DAOs, repositories, or business logic in M01.

// Known peer device.
@Entity(tableName = "device")
data class DeviceEntity(
    @PrimaryKey val deviceId: String,
    val deviceName: String,
    val deviceType: String, // "android" | "windows"
    val lastSeenEpochMs: Long = 0L
)

// Explicit pairing record. Same-LAN presence never equals trust.
@Entity(tableName = "pairing")
data class PairingEntity(
    @PrimaryKey val peerDeviceId: String,
    val pairingCreatedEpochMs: Long = 0L,
    val revoked: Boolean = false
)

// Transfer metadata (PROTOCOL v1.0 §7 vocabulary).
@Entity(tableName = "transfer")
data class TransferEntity(
    @PrimaryKey val transferId: String,
    val fileId: String,
    val sourceDeviceId: String,
    val destinationDeviceId: String,
    val fileName: String,
    val fileSizeBytes: Long,
    val checkpointBytes: Long = 8L * 1024L * 1024L,
    val fileSha256Hex: String = "",
    val state: String = "available",
    val createdEpochMs: Long = 0L,
    val updatedEpochMs: Long = 0L
)

// Durable receiver checkpoint (M10 implements behavior).
@Entity(tableName = "checkpoint")
data class CheckpointEntity(
    @PrimaryKey val transferId: String,
    val confirmedOffsetBytes: Long = 0L,
    val updatedEpochMs: Long = 0L
)

// Local settings key-value.
@Entity(tableName = "settings")
data class SettingsEntity(
    @PrimaryKey val key: String,
    val value: String = ""
)

// Completed/failed transfer history view (backed by transfer table in M10+).
@Entity(tableName = "history")
data class HistoryEntity(
    @PrimaryKey val transferId: String,
    val fileName: String = "",
    val direction: String = "",
    val finalState: String = "",
    val completedEpochMs: Long = 0L
)
