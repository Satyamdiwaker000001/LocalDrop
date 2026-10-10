using Microsoft.Data.Sqlite;

namespace LocalDrop.Storage;

// M01 Foundation: SQLite storage boundary over Microsoft.Data.Sqlite.
// Creates the six required tables. No transfer/database business logic,
// no repositories, no queries beyond schema creation in M01.
public sealed class AppDbContext
{
    private readonly string _connectionString;

    public AppDbContext(string databasePath)
    {
        _connectionString = new SqliteConnectionStringBuilder
        {
            DataSource = databasePath,
        }.ToString();
    }

    public void EnsureSchema()
    {
        using var connection = new SqliteConnection(_connectionString);
        connection.Open();
        using var tx = connection.BeginTransaction();
        Execute(connection, tx, """
            CREATE TABLE IF NOT EXISTS device (
              deviceId TEXT PRIMARY KEY,
              deviceName TEXT NOT NULL,
              deviceType TEXT NOT NULL,
              lastSeenEpochMs INTEGER NOT NULL DEFAULT 0
            );
            """);
        Execute(connection, tx, """
            CREATE TABLE IF NOT EXISTS pairing (
              peerDeviceId TEXT PRIMARY KEY,
              pairingCreatedEpochMs INTEGER NOT NULL DEFAULT 0,
              revoked INTEGER NOT NULL DEFAULT 0
            );
            """);
        Execute(connection, tx, """
            CREATE TABLE IF NOT EXISTS transfer (
              transferId TEXT PRIMARY KEY,
              fileId TEXT NOT NULL,
              sourceDeviceId TEXT NOT NULL,
              destinationDeviceId TEXT NOT NULL,
              fileName TEXT NOT NULL,
              fileSizeBytes INTEGER NOT NULL,
              checkpointBytes INTEGER NOT NULL DEFAULT 8388608,
              fileSha256Hex TEXT NOT NULL DEFAULT '',
              state TEXT NOT NULL DEFAULT 'available',
              createdEpochMs INTEGER NOT NULL DEFAULT 0,
              updatedEpochMs INTEGER NOT NULL DEFAULT 0
            );
            """);
        Execute(connection, tx, """
            CREATE TABLE IF NOT EXISTS checkpoint (
              transferId TEXT PRIMARY KEY,
              confirmedOffsetBytes INTEGER NOT NULL DEFAULT 0,
              updatedEpochMs INTEGER NOT NULL DEFAULT 0
            );
            """);
        Execute(connection, tx, """
            CREATE TABLE IF NOT EXISTS settings (
              key TEXT PRIMARY KEY,
              value TEXT NOT NULL DEFAULT ''
            );
            """);
        Execute(connection, tx, """
            CREATE TABLE IF NOT EXISTS history (
              transferId TEXT PRIMARY KEY,
              fileName TEXT NOT NULL DEFAULT '',
              direction TEXT NOT NULL DEFAULT '',
              finalState TEXT NOT NULL DEFAULT '',
              completedEpochMs INTEGER NOT NULL DEFAULT 0
            );
            """);
        tx.Commit();
    }

    private static void Execute(SqliteConnection connection, SqliteTransaction tx, string sql)
    {
        using var cmd = connection.CreateCommand();
        cmd.Transaction = tx;
        cmd.CommandText = sql;
        cmd.ExecuteNonQuery();
    }
}
