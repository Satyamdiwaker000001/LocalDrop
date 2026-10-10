namespace LocalDrop.Storage;

// M01 Foundation: persistence entity boundaries (SQLite preferred).
// Table definitions only. No repositories or business logic in M01.
public sealed record DeviceEntity(
    string DeviceId,
    string DeviceName,
    string DeviceType, // "android" | "windows"
    long LastSeenEpochMs = 0L);

public sealed record PairingEntity(
    string PeerDeviceId,
    long PairingCreatedEpochMs = 0L,
    bool Revoked = false);

public sealed record TransferEntity(
    string TransferId,
    string FileId,
    string SourceDeviceId,
    string DestinationDeviceId,
    string FileName,
    long FileSizeBytes,
    long CheckpointBytes = 8L * 1024L * 1024L,
    string FileSha256Hex = "",
    string State = "available",
    long CreatedEpochMs = 0L,
    long UpdatedEpochMs = 0L);

public sealed record CheckpointEntity(
    string TransferId,
    long ConfirmedOffsetBytes = 0L,
    long UpdatedEpochMs = 0L);

public sealed record SettingsEntity(
    string Key,
    string Value = "");

public sealed record HistoryEntity(
    string TransferId,
    string FileName = "",
    string Direction = "",
    string FinalState = "",
    long CompletedEpochMs = 0L);
