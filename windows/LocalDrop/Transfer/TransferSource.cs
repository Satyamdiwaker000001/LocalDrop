// M07 Windows source abstraction (mirrors Android TransferSource, M06 ADR 1).
// The sender engine streams from a Stream and needs only a base file name +
// a 64-bit size up front, so future sources (pickers, virtual files) can plug
// in without changing the engine. Approach C (platform-specific counterpart):
// .NET uses FileStream/FileInfo idioms; the interface shape is deliberately
// identical to Android's so the engines stay behavior-compatible.
using System.IO;

namespace LocalDrop.Transfer;

public interface ITransferSource
{
    // Base file name only (no directories); validated against M02 rules.
    string DisplayName { get; }
    long SizeBytes { get; }
    long LastModifiedMs { get; }
    Stream OpenStream();
}

public sealed class FileTransferSource : ITransferSource
{
    private readonly string _path;

    public FileTransferSource(string path)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(path);
        _path = path;
    }

    public string DisplayName => Path.GetFileName(_path);
    public long SizeBytes => new FileInfo(_path).Length;
    public long LastModifiedMs => new DateTimeOffset(new FileInfo(_path).LastWriteTimeUtc).ToUnixTimeMilliseconds();
    public Stream OpenStream() =>
        new FileStream(_path, FileMode.Open, FileAccess.Read, FileShare.Read,
            65536, FileOptions.SequentialScan | FileOptions.Asynchronous);
}

// Test/sample source: deterministic in-memory bytes (also useful for
// generated fixtures without touching disk).
public sealed class ByteArrayTransferSource : ITransferSource
{
    private readonly byte[] _bytes;

    public ByteArrayTransferSource(string displayName, byte[] bytes)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(displayName);
        ArgumentNullException.ThrowIfNull(bytes);
        DisplayName = displayName;
        _bytes = bytes;
    }

    public string DisplayName { get; }
    public long SizeBytes => _bytes.LongLength;
    public long LastModifiedMs => 0L;
    public Stream OpenStream() => new MemoryStream(_bytes, writable: false);
}
