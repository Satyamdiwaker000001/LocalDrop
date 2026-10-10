// M07 UI closure: SendController integration tests. The controller is
// framework-free, so these run as plain xUnit tests against a stub
// ITransferSender plus REAL TransferProgress/TransferOutcome values through
// the production callback path (no simulated success: outcomes only arrive
// via the same OnProgress/outcome channel the engine uses).
using LocalDrop.Discovery;
using LocalDrop.Protocol;
using LocalDrop.Transfer;

namespace LocalDrop.Protocol.Tests;

public sealed class SendControllerTests
{
    private const string PeerId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";

    private sealed class StubSender : ITransferSender
    {
        public Func<List<DiscoveredPeer>> PeerSource { get; set; } = () => [];
        public Func<string, ITransferSource, string, CancellationToken, Action<TransferProgress>?, Task<TransferOutcome>>? OnSend;
        public int Calls;
        public List<DiscoveredPeer> NearbyPeers() => PeerSource();
        public Task<TransferOutcome> SendFileAsync(
            string peerDeviceId, ITransferSource source, string code,
            CancellationToken ct = default, Action<TransferProgress>? onProgress = null)
        {
            Calls++;
            return OnSend!(peerDeviceId, source, code, ct, onProgress);
        }
    }

    private static DiscoveredPeer MakePeer(
        string id = PeerId, string name = "Phone", long controlPort = 48424, string? host = "127.0.0.1") =>
        new(new DiscoveryRecord(id, name, "android", controlPort, ["transfer"]), 0L, 0L, host);

    private static ByteArrayTransferSource MakeFile(string name = "a.bin", int size = 100) =>
        new(name, new byte[size]);

    private static SendController MakeController(
        StubSender stub, ITransferSource? picked = null, TimeSpan? poll = null) =>
        new(stub, () => stub.PeerSource(), () => picked, poll ?? TimeSpan.FromHours(1));

    private static void WaitFor(SendController c, SendPhase phase, int timeoutMs = 15000)
    {
        var deadline = DateTimeOffset.UtcNow.AddMilliseconds(timeoutMs);
        while (c.Phase != phase && DateTimeOffset.UtcNow < deadline)
            Thread.Sleep(25);
        Assert.True(c.Phase == phase,
            $"phase={c.Phase} status='{c.StatusText}' result='{c.ResultText}' progress='{c.ProgressText}'");
    }

    [Fact]
    public void PeerList_Updates_Empty_And_DialableFlags()
    {
        var stub = new StubSender();
        using var c = MakeController(stub);
        c.RefreshPeersNow();
        Assert.Empty(c.Peers);
        stub.PeerSource = () => [MakePeer(), MakePeer("cccccccc-cccc-4ccc-8ccc-cccccccccccc", "Tablet", 0, null)];
        c.RefreshPeersNow();
        Assert.Equal(2, c.Peers.Count);
        Assert.True(c.Peers[0].Dialable);
        Assert.Equal("Online", c.Peers[0].Status);
        Assert.False(c.Peers[1].Dialable);
    }

    [Fact]
    public void PeerDisappears_BeforeSend_BlocksWithActionableMessage()
    {
        var stub = new StubSender { PeerSource = () => [MakePeer()] };
        using var c = MakeController(stub, MakeFile());
        c.RefreshPeersNow();
        c.SelectPeer(PeerId);
        c.PickFile();
        c.Code = "123456";
        Assert.True(c.CanSend);
        stub.PeerSource = () => [];
        c.RefreshPeersNow();
        Assert.Empty(c.Peers);
        Assert.False(c.CanSend);
        Assert.Contains("no longer nearby", c.StatusText);
        Assert.False(c.StartSend());
        Assert.Equal(0, stub.Calls);
    }

    [Fact]
    public void SuccessfulTransfer_ReportsActualTerminalResult()
    {
        var stub = new StubSender { PeerSource = () => [MakePeer()] };
        stub.OnSend = (peer, source, code, ct, progress) =>
        {
            Assert.Equal(PeerId, peer);
            Assert.Equal("a.bin", source.DisplayName);
            // Controller passes the raw input through; M05 normalization
            // (spaces/dashes stripped) happens in the engine, not the UI.
            Assert.Equal("123 456", code);
            progress?.Invoke(TransferProgress.Compute("t", 100, 50, 1000, 50000.0, TransferEngineState.TRANSFERRING));
            return Task.FromResult<TransferOutcome>(
                new TransferOutcome.TransportComplete("t", 100, 2000, 50000.0,
                    new TransferMetrics(2000, 100, 50000.0, 0, 0, 0, [], 65536)));
        };
        using var c = MakeController(stub, MakeFile());
        c.RefreshPeersNow();
        c.SelectPeer(PeerId);
        c.PickFile();
        c.Code = "123 456";
        Assert.True(c.StartSend());
        WaitFor(c, SendPhase.Succeeded);
        Assert.Equal(1, stub.Calls);
        Assert.Equal(100.0, c.ProgressPercent);
        Assert.Contains("50 B of 100 B", c.ProgressText);
        Assert.Contains("Completed", c.ResultText);
        Assert.Contains("Phone", c.ResultText);
    }

    [Fact]
    public void RejectedAuthorization_ShowsActionableError()
    {
        var stub = new StubSender { PeerSource = () => [MakePeer()] };
        stub.OnSend = (_, _, _, _, _) => Task.FromResult<TransferOutcome>(
            new TransferOutcome.Failed("t", ErrorCode.TRANSFER_REJECTED, "Invalid transfer code."));
        using var c = MakeController(stub, MakeFile());
        c.RefreshPeersNow();
        c.SelectPeer(PeerId);
        c.PickFile();
        c.Code = "000000";
        Assert.True(c.StartSend());
        WaitFor(c, SendPhase.Failed);
        Assert.Contains("rejected", c.ResultText, StringComparison.OrdinalIgnoreCase);
        Assert.Contains("try again", c.ResultText, StringComparison.OrdinalIgnoreCase);
    }

    [Fact]
    public void InvalidCode_BlockedBeforeNetwork()
    {
        var stub = new StubSender { PeerSource = () => [MakePeer()] };
        using var c = MakeController(stub, MakeFile());
        c.RefreshPeersNow();
        c.SelectPeer(PeerId);
        c.PickFile();
        c.Code = "12";
        Assert.False(c.CanSend);
        Assert.False(c.StartSend());
        Assert.Equal(0, stub.Calls);
    }

    [Fact]
    public void DuplicateSubmission_Prevented()
    {
        var stub = new StubSender { PeerSource = () => [MakePeer()] };
        var gate = new TaskCompletionSource<TransferOutcome>(TaskCreationOptions.RunContinuationsAsynchronously);
        stub.OnSend = (_, _, _, _, _) => gate.Task;
        using var c = MakeController(stub, MakeFile());
        c.RefreshPeersNow();
        c.SelectPeer(PeerId);
        c.PickFile();
        c.Code = "123456";
        Assert.True(c.StartSend());
        Assert.False(c.StartSend());
        Assert.False(c.StartSend());
        gate.SetResult(new TransferOutcome.TransportComplete("t", 100, 10, null,
            new TransferMetrics(10, 100, null, 0, 0, 0, [], 65536)));
        WaitFor(c, SendPhase.Succeeded);
        Assert.Equal(1, stub.Calls);
    }

    [Fact]
    public void PickerCancellation_KeepsPreviousState()
    {
        var stub = new StubSender { PeerSource = () => [MakePeer()] };
        using var c = MakeController(stub, picked: null);
        c.PickFile();
        Assert.False(c.HasFile);
        Assert.Equal("", c.FileName);
    }

    [Fact]
    public void Progress_ReflectsRealEngineEvents()
    {
        var stub = new StubSender { PeerSource = () => [MakePeer()] };
        TransferProgress? seen = null;
        stub.OnSend = (_, _, _, ct, progress) =>
        {
            var snaps = new[]
            {
                TransferProgress.Compute("t", 2048, 1024, 500, 102400.0, TransferEngineState.TRANSFERRING),
                TransferProgress.Compute("t", 2048, 2048, 1000, 102400.0, TransferEngineState.TRANSFERRING),
            };
            foreach (var s in snaps) { progress?.Invoke(s); seen = s; }
            return Task.FromResult<TransferOutcome>(
                new TransferOutcome.TransportComplete("t", 2048, 1000, 102400.0,
                    new TransferMetrics(1000, 2048, 102400.0, 0, 0, 0, [], 65536)));
        };
        using var c = MakeController(stub, MakeFile("b.bin", 2048));
        c.RefreshPeersNow();
        c.SelectPeer(PeerId);
        c.PickFile();
        c.Code = "123456";
        Assert.True(c.StartSend());
        WaitFor(c, SendPhase.Succeeded);
        Assert.NotNull(seen);
        Assert.Equal(seen!.Percent, c.ProgressPercent);
        Assert.Contains("2.0 KB of 2.0 KB", c.ProgressText);
        Assert.Contains("100.0 KB/s", c.SpeedText);
    }

    [Fact]
    public void Cancel_ReportsCancelledState()
    {
        var stub = new StubSender { PeerSource = () => [MakePeer()] };
        stub.OnSend = async (_, _, _, ct, _) =>
        {
            // Faithful to the production contract: cooperative cancellation
            // surfaces as a Cancelled outcome, never as an escaped exception.
            try
            {
                await Task.Delay(Timeout.Infinite, ct);
            }
            catch (OperationCanceledException)
            {
                return new TransferOutcome.Cancelled("t", 10);
            }
            return new TransferOutcome.Cancelled("t", 10);
        };
        using var c = MakeController(stub, MakeFile());
        c.RefreshPeersNow();
        c.SelectPeer(PeerId);
        c.PickFile();
        c.Code = "123456";
        Assert.True(c.StartSend());
        Thread.Sleep(200);
        c.CancelSend();
        WaitFor(c, SendPhase.Cancelled);
        Assert.Contains("Cancelled", c.ResultText);
    }

    [Fact]
    public async Task Dispose_CancelsActiveTransfer_AndStopsPolling()
    {
        var stub = new StubSender { PeerSource = () => [MakePeer()] };
        var started = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var observedCancel = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        stub.OnSend = async (_, _, _, ct, _) =>
        {
            started.SetResult();
            using var reg = ct.Register(() => observedCancel.TrySetResult());
            await Task.Delay(Timeout.Infinite, ct);
            return new TransferOutcome.Cancelled("t", 0);
        };
        var c = MakeController(stub, MakeFile(), TimeSpan.FromMilliseconds(50));
        c.RefreshPeersNow();
        c.SelectPeer(PeerId);
        c.PickFile();
        c.Code = "123456";
        Assert.True(c.StartSend());
        await started.Task.WaitAsync(TimeSpan.FromSeconds(5));
        c.Dispose();
        await observedCancel.Task.WaitAsync(TimeSpan.FromSeconds(5));
    }

    [Fact]
    public void PickerFailure_ShowsActionableError()
    {
        var stub = new StubSender();
        using var c = new SendController(stub, () => stub.PeerSource(),
            () => throw new IOException("denied"), TimeSpan.FromHours(1));
        c.PickFile();
        Assert.Equal(SendPhase.Failed, c.Phase);
        Assert.Contains("Could not open", c.ResultText);
    }

    [Theory]
    [InlineData(ErrorCode.AUTH_FAILED)]
    [InlineData(ErrorCode.PAIRING_REQUIRED)]
    [InlineData(ErrorCode.TRANSFER_REJECTED)]
    [InlineData(ErrorCode.NOT_ENOUGH_STORAGE)]
    [InlineData(ErrorCode.FILE_NOT_FOUND)]
    [InlineData(ErrorCode.NETWORK_LOST)]
    [InlineData(ErrorCode.RESUME_UNAVAILABLE)]
    [InlineData(ErrorCode.CHECKSUM_MISMATCH)]
    [InlineData(ErrorCode.CANCELLED)]
    [InlineData(ErrorCode.TIMEOUT)]
    [InlineData(ErrorCode.INVALID_PROTOCOL)]
    [InlineData(ErrorCode.INTERNAL_ERROR)]
    public void FriendlyError_CoversEveryProtocolCode(ErrorCode code)
    {
        string text = SendController.FriendlyError(code, "detail");
        Assert.False(string.IsNullOrWhiteSpace(text));
    }

    [Fact]
    public void PresentationHelpers_FormatSanely()
    {
        Assert.Equal("0 B", SendController.FormatBytes(0));
        Assert.Equal("1.0 KB", SendController.FormatBytes(1024));
        Assert.Equal("10.0 MB", SendController.FormatBytes(10L * 1024 * 1024));
        Assert.Equal("—", SendController.FormatSpeed(0));
        Assert.Equal("—", SendController.FormatDuration(-1));
        Assert.Equal("00:05", SendController.FormatDuration(5000));
        foreach (TransferEngineState state in Enum.GetValues<TransferEngineState>())
            Assert.False(string.IsNullOrWhiteSpace(SendController.HumanState(state)));
    }
}
