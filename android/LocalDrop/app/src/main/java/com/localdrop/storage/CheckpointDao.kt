package com.localdrop.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface CheckpointDao {
    @Query("""
        SELECT c.confirmedOffsetBytes 
        FROM transfer t 
        JOIN checkpoint c ON t.transferId = c.transferId 
        WHERE t.sourceDeviceId = :sourceDeviceId AND t.fileId = :fileId AND t.fileSizeBytes = :fileSize
    """)
    fun getCheckpointOffset(sourceDeviceId: String, fileId: String, fileSize: Long): Long?

    @Query("""
        SELECT t.transferId 
        FROM transfer t 
        JOIN checkpoint c ON t.transferId = c.transferId 
        WHERE t.sourceDeviceId = :sourceDeviceId AND t.fileId = :fileId AND t.fileSizeBytes = :fileSize
    """)
    fun getCheckpointTransferId(sourceDeviceId: String, fileId: String, fileSize: Long): String?

    @Query("UPDATE transfer SET transferId = :newId WHERE transferId = :oldId")
    fun updateTransferId(oldId: String, newId: String)

    @Query("UPDATE checkpoint SET transferId = :newId WHERE transferId = :oldId")
    fun updateCheckpointTransferId(oldId: String, newId: String)

    @Transaction
    fun updateCheckpointIdentity(oldId: String, newId: String) {
        updateTransferId(oldId, newId)
        updateCheckpointTransferId(oldId, newId)
    }

    @Transaction
    fun getCheckpointAndMigrate(sourceDeviceId: String, fileId: String, fileSize: Long, newId: String): Long {
        val offset = getCheckpointOffset(sourceDeviceId, fileId, fileSize) ?: return 0L
        val oldId = getCheckpointTransferId(sourceDeviceId, fileId, fileSize)
        if (oldId != null && oldId != newId) {
            updateCheckpointIdentity(oldId, newId)
        }
        return offset
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertTransfer(transfer: TransferEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertCheckpoint(checkpoint: CheckpointEntity)

    @Transaction
    fun saveCheckpoint(transfer: TransferEntity, checkpoint: CheckpointEntity) {
        insertTransfer(transfer)
        insertCheckpoint(checkpoint)
    }

    @Query("DELETE FROM checkpoint WHERE transferId = :transferId")
    fun deleteCheckpoint(transferId: String)

    @Query("DELETE FROM transfer WHERE transferId = :transferId")
    fun deleteTransfer(transferId: String)

    @Transaction
    fun clearCheckpoint(transferId: String) {
        deleteCheckpoint(transferId)
        deleteTransfer(transferId)
    }
}
