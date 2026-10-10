// M06 §24A/B + ADRs 12/15/17: 64-bit progress math, state wires, error
// mapping, transferId routing. Pure logic, no I/O.
using LocalDrop.Transfer;

namespace LocalDrop.Protocol.Tests;

public sealed class TransferModelsTests
{
    [Fact]
    public void StateWires_ReuseFrozenM01Vocabulary()
    {
        Assert.Equal("connecting", TransferEngineStates.ToWire(TransferEngineState.CONNECTING));
        Assert.Equal("waiting acceptance", TransferEngineStates.ToWire(TransferEngineState.WAITING_ACCEPTANCE));
        Assert.Equal("transferring", TransferEngineStates.ToWire(TransferEngineState.TRANSFERRING));
        Assert.Equal("completed", TransferEngineStates.ToWire(TransferEngineState.TRANSPORT_COMPLETE));
        Assert.Equal("failed", TransferEngineStates.ToWire(TransferEngineState.FAILED));
        Assert.Equal("cancelled", TransferEngineStates.ToWire(TransferEngineState.CANCELLED));
        var frozen = new[]
        {
            "available", "connecting", "waiting acceptance", "transferring",
            "paused", "reconnecting", "verifying", "completed", "failed", "cancelled",
        };
        foreach (TransferEngineState state in Enum.GetValues<TransferEngineState>())
            Assert.Contains(TransferEngineStates.ToWire(state), frozen);
    }

    [Fact]
    public void ProgressMath_Is64BitAndSafe()
    {
        long tenGib = 10L * 1024 * 1024 * 1024;
        var p = TransferProgress.Compute("t", tenGib, tenGib / 4, 2000L, 1_000_000_000.0,
            TransferEngineState.TRANSFERRING);
        Assert.Equal(tenGib, p.TotalBytes);
        Assert.Equal(tenGib / 4, p.TransferredBytes);
        Assert.Equal(25.0, p.Percent, 9);
        Assert.Equal(tenGib / 4 * 1000.0 / 2000.0, p.AverageBytesPerSec!.Value, 0);
        Assert.Equal((long)((tenGib - tenGib / 4) * 1000.0 / 1_000_000_000.0), p.EtaMs);
        // Near long.MaxValue: no overflow, percent clamps.
        var huge = TransferProgress.Compute("t", long.MaxValue, long.MaxValue, 1000L, 1e12,
            TransferEngineState.TRANSFERRING);
        Assert.Equal(100.0, huge.Percent, 9);
        // Zero-byte file: defined as complete, no division by zero.
        var empty = TransferProgress.Compute("t", 0L, 0L, 0L, null, TransferEngineState.TRANSFERRING);
        Assert.Equal(100.0, empty.Percent, 9);
        Assert.Null(empty.AverageBytesPerSec);
        Assert.Null(empty.EtaMs);
        // No recent rate: no ETA claim.
        var blind = TransferProgress.Compute("t", 100L, 10L, 100L, null, TransferEngineState.TRANSFERRING);
        Assert.Null(blind.EtaMs);
        // Over-count clamps to total.
        var over = TransferProgress.Compute("t", 100L, 150L, 100L, 10.0, TransferEngineState.TRANSFERRING);
        Assert.Equal(100L, over.TransferredBytes);
        Assert.Equal(100.0, over.Percent, 9);
    }

    [Fact]
    public void ErrorMapping_UsesOnlyFrozenCodes()
    {
        Assert.Equal(ErrorCode.FILE_NOT_FOUND, TransferErrors.SourceUnavailable("x").Code);
        Assert.Equal(ErrorCode.INTERNAL_ERROR, TransferErrors.SourceReadFailed("x").Code);
        Assert.Equal(ErrorCode.NETWORK_LOST, TransferErrors.ConnectionFailed("x").Code);
        Assert.Equal(ErrorCode.NETWORK_LOST, TransferErrors.ConnectionLost("x").Code);
        Assert.Equal(ErrorCode.CANCELLED, TransferErrors.Cancelled().Code);
        Assert.Equal(ErrorCode.TIMEOUT, TransferErrors.TimedOut("x").Code);
        Assert.Equal(ErrorCode.TRANSFER_REJECTED, TransferErrors.Rejected("x").Code);
        Assert.Equal(ErrorCode.INVALID_PROTOCOL, TransferErrors.ProtocolViolation("x").Code);
        Assert.Equal(ErrorCode.NOT_ENOUGH_STORAGE,
            TransferErrors.ClassifyWriteFailure(new IOException("No space left on device")).Code);
        Assert.Equal(ErrorCode.INTERNAL_ERROR,
            TransferErrors.ClassifyWriteFailure(new IOException("weird")).Code);
    }

    [Fact]
    public void TransferIdRouting_CoversTransferTypesOnly()
    {
        const string tid = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";
        LocalDropMessage Pause() => new(
            MessageType.PAUSE,
            "11111111-1111-4111-8111-111111111111", null, null, "tid",
            new TypedPayload.Pause(new TransferIdOnlyPayload(tid)));
        Assert.Equal(tid, TransferIds.Of(Pause()));
        LocalDropMessage Start() => new(
            MessageType.TRANSFER_START,
            "11111111-1111-4111-8111-111111111111", null, null, "tid",
            new TypedPayload.TransferStart(new TransferStartPayload(tid, 0L)));
        Assert.Equal(tid, TransferIds.Of(Start()));
    }
}
