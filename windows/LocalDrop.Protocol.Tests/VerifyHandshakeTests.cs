// Audit verify-gating (Windows mirror of the Android VerifyHandshakeTest):
// the sender reports TransportComplete ONLY after the receiver confirms
// hash verification + finalization (VERIFY_RESULT match=true). Scripted
// fake receiver for sender verdicts; real receiver engine for receiver
// verdicts. No Internet.
using System.Collections.Concurrent;
using System.Net;
using System.Net.Sockets;
using System.Text;
using LocalDrop.Discovery;
using LocalDrop.Network;
using LocalDrop.Security;
using LocalDrop.Transfer;

namespace LocalDrop.Protocol.Tests;

public sealed class VerifyHandshakeTests
{
    private const string SenderId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
    private const string ReceiverId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";

    private static byte[] PatternBytes(long size, long seed = 0x5A17L)
    {
        var buffer = new byte[size];
        for (long i = 0; i < size; i++) buffer[i] = (byte)((i * 31 + seed) & 0xFF);
        return buffer;
    }

    private static string Sha256Hex(byte[] content)
    {
        using var hasher = System.Security.Cryptography.SHA256.Create();
        return Convert.ToHexString(hasher.ComputeHash(content)).ToLowerInvariant();
    }

    // SenderEngineTests.FakeReceiver is private; this file drives sends
    // through its own scripted fake below instead.
    private sealed class ScriptedReceiver : IDisposable
    {
        public enum Verdict { Match, Mismatch, Silent }
        private readonly Verdict _verdict;
        public readonly ConcurrentQueue<LocalDropMessage> ReceivedControl = new();
        public readonly TcpListener DataListener;
        public int ActualDataPort => ((IPEndPoint)DataListener.LocalEndpoint).Port;
        private readonly Task _dataTask;

        public ScriptedReceiver(Verdict verdict)
        {
            _verdict = verdict;
            DataListener = new TcpListener(IPAddress.Loopback, 0);
            DataListener.Start();
            _dataTask = Task.Run(DataLoopAsync);
        }

        private async Task DataLoopAsync()
        {
            try
            {
                using var socket = await DataListener.AcceptTcpClientAsync();
                using var stream = socket.GetStream();
                var line = new List<byte>();
                while (true)
                {
                    int b = stream.ReadByte();
                    if (b < 0 || b == '\n') break;
                    line.Add((byte)b);
                }
                byte[] buf = new byte[65536];
                while (await stream.ReadAsync(buf) > 0) { }
            }
            catch { }
        }

        public IControlEvents Listener => new ScriptedListener(this);

        private sealed class ScriptedListener(ScriptedReceiver parent) : IControlEvents
        {
            public void OnMessage(ControlConnection connection, LocalDropMessage message)
            {
                parent.ReceivedControl.Enqueue(message);
                if (message.Type == MessageType.TRANSFER_REQUEST &&
                    message.Payload is TypedPayload.TransferRequest req)
                {
                    _ = connection.SendAsync(new LocalDropMessage(
                        MessageType.TRANSFER_ACCEPT, ProtocolCodec.NewMessageId(),
                        null, null, req.V.Metadata.TransferId,
                        new TypedPayload.TransferAccept(
                            new TransferAcceptPayload(req.V.Metadata.TransferId, 0L))));
                }
                if (message.Type == MessageType.VERIFY_REQUEST &&
                    message.Payload is TypedPayload.VerifyRequest verify)
                {
                    if (parent._verdict != Verdict.Silent)
                    {
                        _ = connection.SendAsync(new LocalDropMessage(
                            MessageType.VERIFY_RESULT, ProtocolCodec.NewMessageId(),
                            null, null, verify.V.TransferId,
                            new TypedPayload.VerifyResult(new VerifyResultPayload(
                                verify.V.TransferId,
                                parent._verdict == Verdict.Match,
                                verify.V.Sha256Hex))));
                    }
                }
            }

            public void OnClosed(ControlConnection connection, CloseReason reason, string detail) { }
        }

        public async Task<LocalDropMessage> AwaitTypeAsync(MessageType type, int timeoutMs = 15000)
        {
            var deadline = DateTimeOffset.UtcNow.AddMilliseconds(timeoutMs);
            while (DateTimeOffset.UtcNow < deadline)
            {
                foreach (var m in ReceivedControl)
                    if (m.Type == type) return m;
                await Task.Delay(50);
            }
            throw new Xunit.Sdk.XunitException($"timed out waiting for {type}");
        }

        public void Dispose()
        {
            try { DataListener.Stop(); } catch { }
        }
    }

    private sealed class SenderHarness : IDisposable
    {
        public required ControlServer Server { get; init; }
        public required ControlConnection Control { get; init; }
        public required ScriptedReceiver Fake { get; init; }
        public required SenderEngine Engine { get; init; }
        public void Dispose()
        {
            try { Control.DisconnectAsync().GetAwaiter().GetResult(); } catch { }
            try { Server.StopAsync().GetAwaiter().GetResult(); } catch { }
            try { Server.Dispose(); } catch { }
            Fake.Dispose();
        }
    }

    private static SenderHarness CreateSender(ScriptedReceiver.Verdict verdict, int verifyTimeoutMs = 8000)
    {
        var senderDevice = new DeviceInfo(SenderId, "PcA", "windows");
        var receiverDevice = new DeviceInfo(ReceiverId, "PhoneB", "android");
        var fake = new ScriptedReceiver(verdict);
        var server = new ControlServer(receiverDevice, port: 0, listenerFactory: () => fake.Listener);
        server.Start();
        var mailbox = new TransferMailbox();
        var control = new ControlConnection(senderDevice, mailbox);
        if (!control.ConnectAsync("127.0.0.1", server.LocalPort).GetAwaiter().GetResult())
            throw new Xunit.Sdk.XunitException("control connect failed");
        var engine = new SenderEngine(
            SenderId, control, mailbox,
            dataPort: fake.ActualDataPort, verifyTimeoutMs: verifyTimeoutMs);
        return new SenderHarness { Server = server, Control = control, Fake = fake, Engine = engine };
    }

    private static ByteArrayTransferSource PatternSource(string name, byte[] bytes) =>
        new(name, bytes);

    [Fact]
    public async Task VerifiedSuccess_ReturnsTransportCompleteAfterVerifyResultMatch()
    {
        using var h = CreateSender(ScriptedReceiver.Verdict.Match);
        var source = PatternSource("v.bin", PatternBytes(200_000));
        var outcome = await h.Engine.SendAsync(new SenderEngine.SendRequest(
            source, ReceiverId, DataHost: "127.0.0.1"));
        var done = Assert.IsType<TransferOutcome.TransportComplete>(outcome);
        var verify = await h.Fake.AwaitTypeAsync(MessageType.VERIFY_REQUEST);
        Assert.Equal(done.TransferId, verify.TransferId);
    }

    [Fact]
    public async Task DigestMismatch_ReturnsFailedChecksumMismatch()
    {
        using var h = CreateSender(ScriptedReceiver.Verdict.Mismatch);
        var outcome = await h.Engine.SendAsync(new SenderEngine.SendRequest(
            PatternSource("v.bin", PatternBytes(100_000)), ReceiverId, DataHost: "127.0.0.1"));
        var failed = Assert.IsType<TransferOutcome.Failed>(outcome);
        Assert.Equal(ErrorCode.CHECKSUM_MISMATCH, failed.Code);
    }

    [Fact]
    public async Task SilentVerifier_TimesOutAsFailedTimeout()
    {
        using var h = CreateSender(ScriptedReceiver.Verdict.Silent, verifyTimeoutMs: 3000);
        var outcome = await h.Engine.SendAsync(new SenderEngine.SendRequest(
            PatternSource("v.bin", PatternBytes(50_000)), ReceiverId, DataHost: "127.0.0.1"));
        var failed = Assert.IsType<TransferOutcome.Failed>(outcome);
        // Control is still up: unconfirmed-verification timeout, not loss.
        Assert.Equal(ErrorCode.TIMEOUT, failed.Code);
        Assert.Contains("did not confirm verification", failed.Detail);
    }

    [Fact]
    public async Task ControlLossDuringVerification_ReturnsNetworkLost()
    {
        using var h = CreateSender(ScriptedReceiver.Verdict.Silent, verifyTimeoutMs: 15000);
        var sendTask = h.Engine.SendAsync(new SenderEngine.SendRequest(
            PatternSource("v.bin", PatternBytes(50_000)), ReceiverId, DataHost: "127.0.0.1"));
        // Parked in the verify wait: kill the channel underneath it.
        await h.Fake.AwaitTypeAsync(MessageType.VERIFY_REQUEST);
        await Task.Delay(300);
        await h.Control.DisconnectAsync();
        var outcome = await sendTask.WaitAsync(TimeSpan.FromSeconds(25));
        var failed = Assert.IsType<TransferOutcome.Failed>(outcome);
        Assert.Equal(ErrorCode.NETWORK_LOST, failed.Code);
    }

    [Fact]
    public async Task CancelDuringVerification_ReturnsCancelled()
    {
        using var h = CreateSender(ScriptedReceiver.Verdict.Silent, verifyTimeoutMs: 20000);
        using var cts = new CancellationTokenSource();
        var sendTask = h.Engine.SendAsync(new SenderEngine.SendRequest(
            PatternSource("v.bin", PatternBytes(50_000)), ReceiverId, DataHost: "127.0.0.1",
            CancellationToken: cts.Token));
        await h.Fake.AwaitTypeAsync(MessageType.VERIFY_REQUEST);
        cts.Cancel();
        var outcome = await sendTask.WaitAsync(TimeSpan.FromSeconds(25));
        Assert.IsType<TransferOutcome.Cancelled>(outcome);
        await h.Fake.AwaitTypeAsync(MessageType.CANCEL);
    }

    // ---- real receiver engine (receiver-side verdicts) ----

    private sealed class RecvHarness : IDisposable
    {
        public required DeviceInfo ClientDevice { get; init; }
        public required DeviceInfo ServerDevice { get; init; }
        public required ReceiverEngine Engine { get; init; }
        public required ControlServer Server { get; init; }
        public required ControlConnection Client { get; init; }
        public required TransferMailbox Mailbox { get; init; }
        public required string DestDir { get; init; }
        public void Dispose()
        {
            try { Client.DisconnectAsync().GetAwaiter().GetResult(); } catch { }
            try { Engine.StopAsync().GetAwaiter().GetResult(); } catch { }
            try { Server.StopAsync().GetAwaiter().GetResult(); } catch { }
            try { Server.Dispose(); } catch { }
            try { Directory.Delete(DestDir, recursive: true); } catch { }
        }
    }

    private static RecvHarness CreateReceiver()
    {
        var serverStore = new SoftwareDeviceIdentityStore();
        var clientStore = new SoftwareDeviceIdentityStore();
        var serverDevice = AuthTestSupport.DeviceFor(serverStore, "PcB");
        var clientDevice = AuthTestSupport.DeviceFor(clientStore, "PhoneA");
        var log = new RecordingSecurityLogger();
        var serverAuth = new AuthManager(serverDevice, serverStore, log);
        var clientAuth = new AuthManager(clientDevice, clientStore, log);
        var txns = new TransactionManager(null, Core.AppConfig.TransactionCodeLifetimeMs, log);
        var sessions = new AuthorizedSessionStore();
        string destDir = Path.Combine(Path.GetTempPath(), "localdrop-verify-" + Guid.NewGuid().ToString("D"));
        var engine = new ReceiverEngine(serverDevice, serverAuth, sessions, destDir, log, dataPort: 0);
        var handler = engine.CreateHandler();
        serverAuth.Inner = new TransactionAuthorizer(
            serverAuth, txns, sessions, log, serverDevice.DeviceId, handler);
        var server = new ControlServer(serverDevice, port: 0, listenerFactory: () => serverAuth);
        server.Start();
        engine.Start();
        var mailbox = new TransferMailbox();
        clientAuth.Inner = mailbox;
        var client = new ControlConnection(clientDevice, clientAuth);
        if (!client.ConnectAsync("127.0.0.1", server.LocalPort).GetAwaiter().GetResult())
            throw new Xunit.Sdk.XunitException("connect failed");
        var authOutcome = AuthTestSupport.AwaitOutcomeAsync(clientAuth.BeginAuthAsync(client), 15000)
            .GetAwaiter().GetResult();
        if (authOutcome is not AuthOutcome.Authenticated)
            throw new Xunit.Sdk.XunitException($"auth failed: {authOutcome}");
        sessions.Authorize(clientDevice.DeviceId, Guid.NewGuid().ToString("D"));
        return new RecvHarness
        {
            ClientDevice = clientDevice, ServerDevice = serverDevice,
            Engine = engine, Server = server, Client = client,
            Mailbox = mailbox, DestDir = destDir,
        };
    }

    private static async Task DriveBytesAsync(
        RecvHarness h, string transferId, byte[] content, string? completeSha = null)
    {
        var metadata = new TransferMetadata(
            transferId, h.ClientDevice.DeviceId, h.ServerDevice.DeviceId,
            Guid.NewGuid().ToString("D"), "verify.bin", content.LongLength,
            Core.AppConfig.DefaultCheckpointBytes, null, 0L);
        Assert.True(await h.Client.SendAsync(new LocalDropMessage(
            MessageType.TRANSFER_REQUEST, ProtocolCodec.NewMessageId(),
            null, null, transferId,
            new TypedPayload.TransferRequest(new TransferRequestPayload(metadata)))));
        var accept = h.Mailbox.AwaitResponse(transferId,
            new HashSet<MessageType> { MessageType.TRANSFER_ACCEPT }, 10000);
        Assert.NotNull(accept);
        using var data = new TcpClient();
        await data.ConnectAsync(IPAddress.Loopback, h.Engine.LocalPort);
        var stream = data.GetStream();
        await stream.WriteAsync(Encoding.ASCII.GetBytes(transferId + "\n"));
        Assert.True(await h.Client.SendAsync(new LocalDropMessage(
            MessageType.TRANSFER_START, ProtocolCodec.NewMessageId(),
            null, null, transferId,
            new TypedPayload.TransferStart(new TransferStartPayload(transferId, 0L)))));
        await stream.WriteAsync(content);
        await stream.FlushAsync();
        await h.Client.SendAsync(new LocalDropMessage(
            MessageType.TRANSFER_COMPLETE, ProtocolCodec.NewMessageId(),
            null, null, transferId,
            new TypedPayload.TransferComplete(new TransferCompletePayload(
                transferId, content.LongLength, completeSha ?? Sha256Hex(content)))));
        await Task.Delay(500); // let the receiver drain before socket close
    }

    private static async Task<LocalDropMessage> AwaitVerifyAsync(RecvHarness h, string transferId)
    {
        var deadline = DateTimeOffset.UtcNow.AddSeconds(15);
        while (DateTimeOffset.UtcNow < deadline)
        {
            var m = h.Mailbox.AwaitResponse(transferId,
                new HashSet<MessageType> { MessageType.VERIFY_RESULT, MessageType.ERROR }, 2000);
            if (m is not null) return m;
        }
        throw new Xunit.Sdk.XunitException("timed out waiting for VERIFY_RESULT/ERROR");
    }

    private static async Task RequestVerifyAsync(RecvHarness h, string transferId, string sha)
    {
        Assert.True(await h.Client.SendAsync(new LocalDropMessage(
            MessageType.VERIFY_REQUEST, ProtocolCodec.NewMessageId(),
            null, null, transferId,
            new TypedPayload.VerifyRequest(new VerifyRequestPayload(transferId, sha)))));
    }

    [Fact]
    public async Task VerifyResultMatch_AfterRealFinalize()
    {
        using var h = CreateReceiver();
        string tid = Guid.NewGuid().ToString("D");
        byte[] content = PatternBytes(300_000);
        await DriveBytesAsync(h, tid, content);
        var engineOutcome = await h.Engine.WaitForOutcomeAsync(tid, 15000);
        Assert.IsType<TransferOutcome.TransportComplete>(engineOutcome);
        string sha = Sha256Hex(content);
        await RequestVerifyAsync(h, tid, sha);
        var verdict = await AwaitVerifyAsync(h, tid);
        Assert.Equal(MessageType.VERIFY_RESULT, verdict.Type);
        var v = Assert.IsType<TypedPayload.VerifyResult>(verdict.Payload).V;
        Assert.True(v.Match);
        Assert.Equal(sha, v.Sha256Hex);
        Assert.Equal(content, await File.ReadAllBytesAsync(Path.Combine(h.DestDir, "verify.bin")));
    }

    [Fact]
    public async Task WrongSha_AnswersMismatch()
    {
        using var h = CreateReceiver();
        string tid = Guid.NewGuid().ToString("D");
        await DriveBytesAsync(h, tid, PatternBytes(100_000));
        Assert.IsType<TransferOutcome.TransportComplete>(
            await h.Engine.WaitForOutcomeAsync(tid, 15000));
        await RequestVerifyAsync(h, tid, new string('f', 64));
        var verdict = await AwaitVerifyAsync(h, tid);
        Assert.Equal(MessageType.VERIFY_RESULT, verdict.Type);
        Assert.False(Assert.IsType<TypedPayload.VerifyResult>(verdict.Payload).V.Match);
    }

    [Fact]
    public async Task UnknownTransfer_AnswersError()
    {
        using var h = CreateReceiver();
        string tid = Guid.NewGuid().ToString("D");
        await RequestVerifyAsync(h, tid, new string('e', 64));
        var verdict = await AwaitVerifyAsync(h, tid);
        Assert.Equal(MessageType.ERROR, verdict.Type);
    }

    [Fact]
    public async Task DelayedVerify_WaitsForFinalizeThenMatches()
    {
        using var h = CreateReceiver();
        string tid = Guid.NewGuid().ToString("D");
        byte[] content = PatternBytes(200_000, seed: 7L);
        string sha = Sha256Hex(content);
        var metadata = new TransferMetadata(
            tid, h.ClientDevice.DeviceId, h.ServerDevice.DeviceId,
            Guid.NewGuid().ToString("D"), "verify.bin", content.LongLength,
            Core.AppConfig.DefaultCheckpointBytes, null, 0L);
        Assert.True(await h.Client.SendAsync(new LocalDropMessage(
            MessageType.TRANSFER_REQUEST, ProtocolCodec.NewMessageId(),
            null, null, tid,
            new TypedPayload.TransferRequest(new TransferRequestPayload(metadata)))));
        Assert.NotNull(h.Mailbox.AwaitResponse(tid,
            new HashSet<MessageType> { MessageType.TRANSFER_ACCEPT }, 10000));
        using var data = new TcpClient();
        await data.ConnectAsync(IPAddress.Loopback, h.Engine.LocalPort);
        var stream = data.GetStream();
        await stream.WriteAsync(Encoding.ASCII.GetBytes(tid + "\n"));
        Assert.True(await h.Client.SendAsync(new LocalDropMessage(
            MessageType.TRANSFER_START, ProtocolCodec.NewMessageId(),
            null, null, tid,
            new TypedPayload.TransferStart(new TransferStartPayload(tid, 0L)))));
        await stream.WriteAsync(content);
        await stream.FlushAsync();
        // Ask BEFORE the receiver can finish: it must wait, then answer
        // match=true (never a premature verdict).
        await RequestVerifyAsync(h, tid, sha);
        await Task.Delay(300);
        await h.Client.SendAsync(new LocalDropMessage(
            MessageType.TRANSFER_COMPLETE, ProtocolCodec.NewMessageId(),
            null, null, tid,
            new TypedPayload.TransferComplete(new TransferCompletePayload(
                tid, content.LongLength, sha))));
        await Task.Delay(500);
        var verdict = await AwaitVerifyAsync(h, tid);
        Assert.Equal(MessageType.VERIFY_RESULT, verdict.Type);
        var v = Assert.IsType<TypedPayload.VerifyResult>(verdict.Payload).V;
        Assert.True(v.Match);
        Assert.Equal(sha, v.Sha256Hex);
    }

    [Fact]
    public async Task CorruptBytes_MismatchRecorded_NoFinalFile()
    {
        using var h = CreateReceiver();
        string tid = Guid.NewGuid().ToString("D");
        byte[] content = PatternBytes(100_000);
        string wrongSha = new string('f', 64);
        await DriveBytesAsync(h, tid, content, completeSha: wrongSha);
        var engineOutcome = await h.Engine.WaitForOutcomeAsync(tid, 15000);
        Assert.IsType<TransferOutcome.Failed>(engineOutcome);
        h.Mailbox.Forget(tid);
        await RequestVerifyAsync(h, tid, wrongSha);
        var verdict = await AwaitVerifyAsync(h, tid);
        Assert.Equal(MessageType.VERIFY_RESULT, verdict.Type);
        Assert.False(Assert.IsType<TypedPayload.VerifyResult>(verdict.Payload).V.Match);
        Assert.False(File.Exists(Path.Combine(h.DestDir, "verify.bin")));
    }

    [Fact]
    public async Task FinalizeFailure_ReportsMismatchNotSuccess()
    {
        using var h = CreateReceiver();
        string tid = Guid.NewGuid().ToString("D");
        byte[] content = PatternBytes(100_000, seed: 13L);
        string sha = Sha256Hex(content);
        var metadata = new TransferMetadata(
            tid, h.ClientDevice.DeviceId, h.ServerDevice.DeviceId,
            Guid.NewGuid().ToString("D"), "verify.bin", content.LongLength,
            Core.AppConfig.DefaultCheckpointBytes, null, 0L);
        Assert.True(await h.Client.SendAsync(new LocalDropMessage(
            MessageType.TRANSFER_REQUEST, ProtocolCodec.NewMessageId(),
            null, null, tid,
            new TypedPayload.TransferRequest(new TransferRequestPayload(metadata)))));
        Assert.NotNull(h.Mailbox.AwaitResponse(tid,
            new HashSet<MessageType> { MessageType.TRANSFER_ACCEPT }, 10000));
        using var data = new TcpClient();
        await data.ConnectAsync(IPAddress.Loopback, h.Engine.LocalPort);
        var stream = data.GetStream();
        await stream.WriteAsync(Encoding.ASCII.GetBytes(tid + "\n"));
        // Wait for the receiver to adopt the transfer (temp appears, final
        // resolved to verify.bin), THEN block the commit path with a
        // directory at the final name. The commit-time re-resolve sees no
        // FILE there and the atomic move onto a directory throws.
        string tempPath = Path.Combine(h.DestDir, "verify.bin.localdrop-part");
        var adoptDeadline = DateTimeOffset.UtcNow.AddSeconds(10);
        while (!File.Exists(tempPath) && DateTimeOffset.UtcNow < adoptDeadline)
            await Task.Delay(50);
        Assert.True(File.Exists(tempPath));
        Directory.CreateDirectory(Path.Combine(h.DestDir, "verify.bin"));
        Assert.True(await h.Client.SendAsync(new LocalDropMessage(
            MessageType.TRANSFER_START, ProtocolCodec.NewMessageId(),
            null, null, tid,
            new TypedPayload.TransferStart(new TransferStartPayload(tid, 0L)))));
        await stream.WriteAsync(content);
        await stream.FlushAsync();
        await h.Client.SendAsync(new LocalDropMessage(
            MessageType.TRANSFER_COMPLETE, ProtocolCodec.NewMessageId(),
            null, null, tid,
            new TypedPayload.TransferComplete(new TransferCompletePayload(
                tid, content.LongLength, sha))));
        await Task.Delay(500);
        var engineOutcome = await h.Engine.WaitForOutcomeAsync(tid, 15000);
        Assert.IsType<TransferOutcome.Failed>(engineOutcome);
        await RequestVerifyAsync(h, tid, sha);
        var verdict = await AwaitVerifyAsync(h, tid);
        Assert.Equal(MessageType.VERIFY_RESULT, verdict.Type);
        // Hashes matched but nothing finalized: must NOT be success.
        Assert.False(Assert.IsType<TypedPayload.VerifyResult>(verdict.Payload).V.Match);
    }
}
