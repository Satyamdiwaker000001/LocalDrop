using System;
using Microsoft.Data.Sqlite;
using System.IO;

namespace LocalDrop.Storage;

public interface ICheckpointStore
{
    long GetCheckpoint(string senderDeviceId, string fileId, long fileSize, string newTransferId);
    void SaveCheckpoint(string transferId, string senderDeviceId, string fileId, string fileName, long fileSizeBytes, long confirmedOffsetBytes);
    void ClearCheckpoint(string transferId);
}

public sealed class SqliteCheckpointStore : ICheckpointStore
{
    private readonly string _connString;

    public SqliteCheckpointStore(string dbPath)
    {
        _connString = new SqliteConnectionStringBuilder { DataSource = dbPath }.ToString();
        new AppDbContext(dbPath).EnsureSchema();
    }

    public long GetCheckpoint(string senderDeviceId, string fileId, long fileSize, string newTransferId)
    {
        using var conn = new SqliteConnection(_connString);
        conn.Open();
        
        using var cmd = conn.CreateCommand();
        cmd.CommandText = @"
            SELECT c.confirmedOffsetBytes, t.transferId
            FROM transfer t
            JOIN checkpoint c ON t.transferId = c.transferId
            WHERE t.sourceDeviceId = @sourceDeviceId 
              AND t.fileId = @fileId 
              AND t.fileSizeBytes = @fileSize";
        cmd.Parameters.AddWithValue("@sourceDeviceId", senderDeviceId);
        cmd.Parameters.AddWithValue("@fileId", fileId);
        cmd.Parameters.AddWithValue("@fileSize", fileSize);
        
        using var reader = cmd.ExecuteReader();
        if (reader.Read())
        {
            long offset = reader.GetInt64(0);
            string oldTransferId = reader.GetString(1);
            
            if (oldTransferId != newTransferId)
            {
                using var tx = conn.BeginTransaction();
                
                using var updateTransfer = conn.CreateCommand();
                updateTransfer.Transaction = tx;
                updateTransfer.CommandText = "UPDATE transfer SET transferId = @newId WHERE transferId = @oldId";
                updateTransfer.Parameters.AddWithValue("@newId", newTransferId);
                updateTransfer.Parameters.AddWithValue("@oldId", oldTransferId);
                updateTransfer.ExecuteNonQuery();
                
                using var updateCheckpoint = conn.CreateCommand();
                updateCheckpoint.Transaction = tx;
                updateCheckpoint.CommandText = "UPDATE checkpoint SET transferId = @newId WHERE transferId = @oldId";
                updateCheckpoint.Parameters.AddWithValue("@newId", newTransferId);
                updateCheckpoint.Parameters.AddWithValue("@oldId", oldTransferId);
                updateCheckpoint.ExecuteNonQuery();
                
                tx.Commit();
            }
            
            return offset;
        }
        return 0L;
    }

    public void SaveCheckpoint(string transferId, string senderDeviceId, string fileId, string fileName, long fileSizeBytes, long confirmedOffsetBytes)
    {
        using var conn = new SqliteConnection(_connString);
        conn.Open();
        using var tx = conn.BeginTransaction();
        
        long now = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        
        using var cmd1 = conn.CreateCommand();
        cmd1.Transaction = tx;
        cmd1.CommandText = @"
            INSERT INTO transfer (transferId, fileId, sourceDeviceId, destinationDeviceId, fileName, fileSizeBytes, state, createdEpochMs, updatedEpochMs)
            VALUES (@transferId, @fileId, @sourceDeviceId, '', @fileName, @fileSizeBytes, 'transferring', @now, @now)
            ON CONFLICT(transferId) DO UPDATE SET updatedEpochMs = @now";
        cmd1.Parameters.AddWithValue("@transferId", transferId);
        cmd1.Parameters.AddWithValue("@fileId", fileId);
        cmd1.Parameters.AddWithValue("@sourceDeviceId", senderDeviceId);
        cmd1.Parameters.AddWithValue("@fileName", fileName);
        cmd1.Parameters.AddWithValue("@fileSizeBytes", fileSizeBytes);
        cmd1.Parameters.AddWithValue("@now", now);
        cmd1.ExecuteNonQuery();
        
        using var cmd2 = conn.CreateCommand();
        cmd2.Transaction = tx;
        cmd2.CommandText = @"
            INSERT INTO checkpoint (transferId, confirmedOffsetBytes, updatedEpochMs)
            VALUES (@transferId, @offset, @now)
            ON CONFLICT(transferId) DO UPDATE SET confirmedOffsetBytes = @offset, updatedEpochMs = @now";
        cmd2.Parameters.AddWithValue("@transferId", transferId);
        cmd2.Parameters.AddWithValue("@offset", confirmedOffsetBytes);
        cmd2.Parameters.AddWithValue("@now", now);
        cmd2.ExecuteNonQuery();
        
        tx.Commit();
    }

    public void ClearCheckpoint(string transferId)
    {
        using var conn = new SqliteConnection(_connString);
        conn.Open();
        using var tx = conn.BeginTransaction();
        
        using var cmd1 = conn.CreateCommand();
        cmd1.Transaction = tx;
        cmd1.CommandText = "DELETE FROM checkpoint WHERE transferId = @transferId";
        cmd1.Parameters.AddWithValue("@transferId", transferId);
        cmd1.ExecuteNonQuery();
        
        using var cmd2 = conn.CreateCommand();
        cmd2.Transaction = tx;
        cmd2.CommandText = "DELETE FROM transfer WHERE transferId = @transferId";
        cmd2.Parameters.AddWithValue("@transferId", transferId);
        cmd2.ExecuteNonQuery();
        
        tx.Commit();
    }
}
