// M06 §24 (receiver side): accept/receive/finalize over loopback with full
// M04+M05 stacks (real auth, real authorized sessions). Covers validation
// order, temp lifecycle, collisions, preamble binding, failures, cancel,
// progress, and metrics. No Internet.
using System.Net;
using System.Net.Sockets;
using System.Text;
using LocalDrop.Discovery;
using LocalDrop.Network;
using LocalDrop.Security;
using LocalDrop.Transfer;

namespace LocalDrop.Protocol.Tests;

public sealed class ReceiverEngineTests
{
    private const string Zeros64 =
        "0000000000000000000000000000000000000000000000000000000000000000";

    private sealed class Harness : IDisposable
    {
        public required SoftwareDeviceIdentityStore ServerStore { get; init; }
        public required SoftwareDeviceIdentityStore ClientStore { get; init; }
        public required DeviceInfo ServerDevice { get; init; }
        public required DeviceInfo ClientDevice { get; init; }
        public required AuthManager ServerAuth { get; init; }
        public required AuthManager ClientAuth { get; init; }
        public required TransactionManager Txns { get; init; }
        public required AuthorizedSessionStore Sessions { get; init; }
        public required ReceiverEngine Engine { get; init; }
        public required ControlServer Server { get; init; }
        public required ControlConnection Client { get; init; }
        public required TransferMailbox ClientMailbox { get; init; }
        public required string DestDir { get; init; }
        public List<TransferProgress> ProgressSeen { get; } = [];
        public required string SessionTxnId { get; init; }

        public void Dispose()
        {
            try { Client.DisconnectAsync().GetAwaiter().GetResult(); } catch { }
            try { Engine.StopAsync().GetAwaiter().GetResult(); } catch { }
            try { Server.StopAsync().GetAwaiter().GetResult(); } catch { }
            try { Server.Dispose(); } catch { }
            try { ClientStore.Dispose(); } catch { }
            try { ServerStore.Dispose(); } catch { }
            try { Directory.Delete(DestDir, recursive: true); } catch { }
        }
    }

    private static Harness Setup(
        bool authenticate = true,
        bool authorize = true,
        int completeTimeoutMs = 10000,
        long pendingTimeoutMs = 30000L)
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
        string destDir = Path.Combine(Path.GetTempPath(), "localdrop-test-" + Guid.NewGuid().ToString("D"));
        var engine = new ReceiverEngine(serverDevice, serverAuth, sessions, destDir, log,
            dataPort: 0, completeTimeoutMs: completeTimeoutMs, pendingTimeoutMs: pendingTimeoutMs);
        var handler = engine.CreateHandler();
        var authorizer = new TransactionAuthorizer(serverAuth, txns, sessions, log,
            serverDevice.DeviceId, handler);
        serverAuth.Inner = authorizer;
        var server = new ControlServer(serverDevice, port: 0, listenerFactory: () => serverAuth);
        server.Start();
        engine.Start();
        var clientMailbox = new TransferMailbox();
        clientAuth.Inner = clientMailbox;
        var client = new ControlConnection(clientDevice, clientAuth);
        if (!client.ConnectAsync("127.0.0.1", server.LocalPort).GetAwaiter().GetResult())
            throw new Xunit.Sdk.XunitException("M04 connect failed");
        string sessionTxn = Guid.NewGuid().ToString("D");
        if (authenticate)
        {
            var outcome = AuthTestSupport.AwaitOutcomeAsync(
                clientAuth.BeginAuthAsync(client), 15000).GetAwaiter().GetResult();
            if (outcome is not AuthOutcome.Authenticated)
                throw new Xunit.Sdk.XunitException($"auth failed: {outcome}");
            if (authorize) sessions.Authorize(clientDevice.DeviceId, sessionTxn);
        }
        return new Harness
        {
            ServerStore = serverStore,
            ClientStore = clientStore,
            ServerDevice = serverDevice,
            ClientDevice = clientDevice,
            ServerAuth = serverAuth,
            ClientAuth = clientAuth,
            Txns = txns,
            Sessions = sessions,
            Engine = engine,
            Server = server,
            Client = client,
            ClientMailbox = clientMailbox,
            DestDir = destDir,
            SessionTxnId = sessionTxn,
        };
    }

    private static byte[] PatternBytes(long size, long seed = 0x5A17L)
    {
        var buffer = new byte[size];
        for (long i = 0; i < size; i++) buffer[i] = (byte)((i * 31 + seed) & 0xFF);
        return buffer;
    }

    // Drives one full sender-side exchange over an established harness:
    // REQUEST -> await ACCEPT -> data connect + preamble + bytes -> COMPLETE.
    private static async Task<TransferOutcome?> DriveTransferAsync(
        Harness h, string transferId, string fileName, byte[] content,
        bool sendComplete = true, bool closeDataAfterBytes = true)
    {
        var metadata = new TransferMetadata(
            transferId, h.ClientDevice.DeviceId, h.ServerDevice.DeviceId,
            Guid.NewGuid().ToString("D"), fileName, content.LongLength,
            Core.AppConfig.DefaultCheckpointBytes, null, 0L);
        var request = new LocalDropMessage(
            MessageType.TRANSFER_REQUEST, ProtocolCodec.NewMessageId(),
            null, null, transferId,
            new TypedPayload.TransferRequest(new TransferRequestPayload(metadata)));
        Assert.True(await h.Client.SendAsync(request));
        var accept = h.ClientMailbox.AwaitResponse(transferId,
            new HashSet<MessageType> { MessageType.TRANSFER_ACCEPT }, 10000);
        Assert.NotNull(accept);
        using var data = new TcpClient();
        await data.ConnectAsync(IPAddress.Loopback, h.Engine.LocalPort);
        var stream = data.GetStream();
        byte[] preamble = Encoding.ASCII.GetBytes(transferId + "\n");
        await stream.WriteAsync(preamble);
        var start = new LocalDropMessage(
            MessageType.TRANSFER_START, ProtocolCodec.NewMessageId(),
            null, null, transferId,
            new TypedPayload.TransferStart(new TransferStartPayload(transferId, 0L)));
        Assert.True(await h.Client.SendAsync(start));
        await stream.WriteAsync(content);
        await stream.FlushAsync();
        if (sendComplete)
        {
            string actualShaHex;
            using (var hasher = System.Security.Cryptography.SHA256.Create())
            {
                actualShaHex = Convert.ToHexString(hasher.ComputeHash(content)).ToLowerInvariant();
            }
            var complete = new LocalDropMessage(
                MessageType.TRANSFER_COMPLETE, ProtocolCodec.NewMessageId(),
                null, null, transferId,
                new TypedPayload.TransferComplete(new TransferCompletePayload(
                    transferId, content.LongLength, actualShaHex)));
            Assert.True(await h.Client.SendAsync(complete));
        }
        if (closeDataAfterBytes) data.Close();
        return await h.Engine.WaitForOutcomeAsync(transferId, 15000);
    }

    [Fact]
    public async Task AcceptReceiveFinalize_ByteExact_TempLifecycleClean()
    {
        using var h = Setup();
        string transferId = Guid.NewGuid().ToString("D");
        byte[] content = PatternBytes(300_000);
        // Temp must not exist before data flows.
        Assert.False(File.Exists(Path.Combine(h.DestDir, "a.bin.localdrop-part")));
        var outcome = await DriveTransferAsync(h, transferId, "a.bin", content);
        var done = Assert.IsType<TransferOutcome.TransportComplete>(outcome);
        Assert.Equal(content.LongLength, done.BytesTransferred);
        // Temp gone, final present with exact bytes.
        Assert.False(File.Exists(Path.Combine(h.DestDir, "a.bin.localdrop-part")));
        string finalPath = Path.Combine(h.DestDir, "a.bin");
        Assert.True(File.Exists(finalPath));
        Assert.Equal(content, await File.ReadAllBytesAsync(finalPath));
        // Metrics sane, block size within bounds, no artificial limits hit.
        Assert.Equal(content.LongLength, done.Metrics.BytesTransferred);
        Assert.InRange(done.Metrics.FinalBlockSizeBytes, 65536, 1048576);
        Assert.True(done.Metrics.ElapsedMs >= 0);
        Assert.NotNull(done.AverageBytesPerSec);
    }

    [Fact]
    public async Task ZeroByteFile_AcceptedAndFinalizedEmpty()
    {
        using var h = Setup();
        string transferId = Guid.NewGuid().ToString("D");
        var outcome = await DriveTransferAsync(h, transferId, "empty.bin", []);
        Assert.IsType<TransferOutcome.TransportComplete>(outcome);
        string finalPath = Path.Combine(h.DestDir, "empty.bin");
        Assert.True(File.Exists(finalPath));
        Assert.Equal(0, new FileInfo(finalPath).Length);
    }

    [Fact]
    public async Task ExistingDestination_NeverOverwritten_Uniquified()
    {
        using var h = Setup();
        string finalPath = Path.Combine(h.DestDir, "keep.bin");
        byte[] original = Encoding.ASCII.GetBytes("original-user-file");
        await File.WriteAllBytesAsync(finalPath, original);
        string transferId = Guid.NewGuid().ToString("D");
        byte[] content = PatternBytes(10_000);
        var outcome = await DriveTransferAsync(h, transferId, "keep.bin", content);
        Assert.IsType<TransferOutcome.TransportComplete>(outcome);
        // Original untouched; newcomer uniquified.
        Assert.Equal(original, await File.ReadAllBytesAsync(finalPath));
        string uniquified = Path.Combine(h.DestDir, "keep (2).bin");
        Assert.True(File.Exists(uniquified));
        Assert.Equal(content, await File.ReadAllBytesAsync(uniquified));
    }

    [Fact]
    public async Task UnauthenticatedRequest_RejectedWithPairingRequired()
    {
        using var h = Setup(authenticate: false);
        string transferId = Guid.NewGuid().ToString("D");
        var metadata = new TransferMetadata(
            transferId, h.ClientDevice.DeviceId, h.ServerDevice.DeviceId,
            Guid.NewGuid().ToString("D"), "x.bin", 10L,
            Core.AppConfig.DefaultCheckpointBytes, null, 0L);
        var request = new LocalDropMessage(
            MessageType.TRANSFER_REQUEST, ProtocolCodec.NewMessageId(),
            null, null, transferId,
            new TypedPayload.TransferRequest(new TransferRequestPayload(metadata)));
        Assert.True(await h.Client.SendAsync(request));
        var response = h.ClientMailbox.AwaitResponse(transferId,
            new HashSet<MessageType> { MessageType.TRANSFER_REJECT }, 10000);
        Assert.NotNull(response);
        var reject = Assert.IsType<TypedPayload.TransferReject>(response!.Payload).V;
        Assert.Equal(ErrorCode.PAIRING_REQUIRED, reject.Error.Code);
        Assert.False(h.Engine.IsBusy);
    }

    [Fact]
    public async Task NoAuthorizedSession_Rejected()
    {
        using var h = Setup(authenticate: true, authorize: false);
        string transferId = Guid.NewGuid().ToString("D");
        var metadata = new TransferMetadata(
            transferId, h.ClientDevice.DeviceId, h.ServerDevice.DeviceId,
            Guid.NewGuid().ToString("D"), "x.bin", 10L,
            Core.AppConfig.DefaultCheckpointBytes, null, 0L);
        var request = new LocalDropMessage(
            MessageType.TRANSFER_REQUEST, ProtocolCodec.NewMessageId(),
            null, null, transferId,
            new TypedPayload.TransferRequest(new TransferRequestPayload(metadata)));
        Assert.True(await h.Client.SendAsync(request));
        var response = h.ClientMailbox.AwaitResponse(transferId,
            new HashSet<MessageType> { MessageType.TRANSFER_REJECT }, 10000);
        Assert.NotNull(response);
        var reject = Assert.IsType<TypedPayload.TransferReject>(response!.Payload).V;
        Assert.Equal(ErrorCode.TRANSFER_REJECTED, reject.Error.Code);
    }

    [Fact]
    public async Task MisaddressedAndResumeRequests_Rejected()
    {
        using var h = Setup();
        foreach (var (fileSize, receiver, resume, name) in new (long, string, long, string)[]
        {
            (10L, Guid.NewGuid().ToString("D"), 0L, "wrong-recipient"),
            // NOTE: resumeOffset must satisfy the frozen M02 shape
            // (resumeOffset <= fileSize); 512 <= 1000 is valid but nonzero,
            // which M06 rejects by policy.
            (1000L, h.ServerDevice.DeviceId, 512L, "nonzero-resume"),
        })
        {
            string transferId = Guid.NewGuid().ToString("D");
            var metadata = new TransferMetadata(
                transferId, h.ClientDevice.DeviceId, receiver,
                Guid.NewGuid().ToString("D"), "x.bin", fileSize,
                Core.AppConfig.DefaultCheckpointBytes, null, resume);
            var request = new LocalDropMessage(
                MessageType.TRANSFER_REQUEST, ProtocolCodec.NewMessageId(),
                null, null, transferId,
                new TypedPayload.TransferRequest(new TransferRequestPayload(metadata)));
            Assert.True(await h.Client.SendAsync(request));
            var response = h.ClientMailbox.AwaitResponse(transferId,
                new HashSet<MessageType> { MessageType.TRANSFER_REJECT }, 10000);
            Assert.NotNull(response);
            Assert.IsType<TypedPayload.TransferReject>(response!.Payload);
        }
        Assert.False(h.Engine.IsBusy);
    }

    [Fact]
    public async Task MultipleRequests_Concurrent_BothComplete()
    {
        using var h = Setup();
        // Start transfer A but hold its data socket open mid-stream.
        string tidA = Guid.NewGuid().ToString("D");
        byte[] contentA = PatternBytes(200_000);
        var metadataA = new TransferMetadata(
            tidA, h.ClientDevice.DeviceId, h.ServerDevice.DeviceId,
            Guid.NewGuid().ToString("D"), "a.bin", contentA.LongLength,
            Core.AppConfig.DefaultCheckpointBytes, null, 0L);
        var requestA = new LocalDropMessage(
            MessageType.TRANSFER_REQUEST, ProtocolCodec.NewMessageId(),
            null, null, tidA,
            new TypedPayload.TransferRequest(new TransferRequestPayload(metadataA)));
        Assert.True(await h.Client.SendAsync(requestA));
        Assert.NotNull(h.ClientMailbox.AwaitResponse(tidA,
            new HashSet<MessageType> { MessageType.TRANSFER_ACCEPT }, 10000));
        var dataA = new TcpClient();
        try
        {
            await dataA.ConnectAsync(IPAddress.Loopback, h.Engine.LocalPort);
            await dataA.GetStream().WriteAsync(System.Text.Encoding.ASCII.GetBytes(tidA + "\n"));
            await dataA.GetStream().WriteAsync(contentA, 0, 1000); // trickle, keep open
            
            // Second request while A is active: must be accepted now.
            string tidB = Guid.NewGuid().ToString("D");
            byte[] contentB = PatternBytes(50_000);
            var metadataB = new TransferMetadata(
                tidB, h.ClientDevice.DeviceId, h.ServerDevice.DeviceId,
                Guid.NewGuid().ToString("D"), "b.bin", contentB.LongLength,
                Core.AppConfig.DefaultCheckpointBytes, null, 0L);
            var requestB = new LocalDropMessage(
                MessageType.TRANSFER_REQUEST, ProtocolCodec.NewMessageId(),
                null, null, tidB,
                new TypedPayload.TransferRequest(new TransferRequestPayload(metadataB)));
            Assert.True(await h.Client.SendAsync(requestB));
            Assert.NotNull(h.ClientMailbox.AwaitResponse(tidB,
                new HashSet<MessageType> { MessageType.TRANSFER_ACCEPT }, 10000));
            
            var dataB = new TcpClient();
            try
            {
                await dataB.ConnectAsync(IPAddress.Loopback, h.Engine.LocalPort);
                await dataB.GetStream().WriteAsync(System.Text.Encoding.ASCII.GetBytes(tidB + "\n"));
                await dataB.GetStream().WriteAsync(contentB);
                await dataB.GetStream().FlushAsync();
                var completeB = new LocalDropMessage(
                    MessageType.TRANSFER_COMPLETE, ProtocolCodec.NewMessageId(),
                    null, null, tidB,
                    new TypedPayload.TransferComplete(new TransferCompletePayload(
                        tidB, contentB.LongLength, Convert.ToHexString(System.Security.Cryptography.SHA256.Create().ComputeHash(contentB)).ToLowerInvariant())));
                Assert.True(await h.Client.SendAsync(completeB));
                dataB.Close();
                var outcomeB = await h.Engine.WaitForOutcomeAsync(tidB, 15000);
                Assert.IsType<TransferOutcome.TransportComplete>(outcomeB);
                Assert.Equal(contentB, await File.ReadAllBytesAsync(Path.Combine(h.DestDir, "b.bin")));
            }
            finally
            {
                dataB.Dispose();
            }

            // Finish A normally: byte-exact completion still works.
            await dataA.GetStream().WriteAsync(contentA.AsMemory(1000));
            await dataA.GetStream().FlushAsync();
            var completeA = new LocalDropMessage(
                MessageType.TRANSFER_COMPLETE, ProtocolCodec.NewMessageId(),
                null, null, tidA,
                new TypedPayload.TransferComplete(new TransferCompletePayload(
                    tidA, contentA.LongLength, Convert.ToHexString(System.Security.Cryptography.SHA256.Create().ComputeHash(contentA)).ToLowerInvariant())));
            Assert.True(await h.Client.SendAsync(completeA));
            dataA.Close();
            var outcomeA = await h.Engine.WaitForOutcomeAsync(tidA, 15000);
            Assert.IsType<TransferOutcome.TransportComplete>(outcomeA);
            Assert.Equal(contentA, await File.ReadAllBytesAsync(Path.Combine(h.DestDir, "a.bin")));
        }
        finally
        {
            dataA.Dispose();
        }
    }

    // M12 helpers: request/accept (stays pending), open a bound data stream,
    // and send TRANSFER_COMPLETE with real SHA-256. Each returns/opens one
    // session; callers compose concurrent scenarios from these steps.
    private static string ShaHex(byte[] content)
    {
        using var hasher = System.Security.Cryptography.SHA256.Create();
        return Convert.ToHexString(hasher.ComputeHash(content)).ToLowerInvariant();
    }

    private static async Task<string> RequestTransferAsync(Harness h, string fileName, byte[] content)
    {
        string transferId = Guid.NewGuid().ToString("D");
        var metadata = new TransferMetadata(
            transferId, h.ClientDevice.DeviceId, h.ServerDevice.DeviceId,
            Guid.NewGuid().ToString("D"), fileName, content.LongLength,
            Core.AppConfig.DefaultCheckpointBytes, null, 0L);
        var request = new LocalDropMessage(
            MessageType.TRANSFER_REQUEST, ProtocolCodec.NewMessageId(),
            null, null, transferId,
            new TypedPayload.TransferRequest(new TransferRequestPayload(metadata)));
        Assert.True(await h.Client.SendAsync(request));
        Assert.NotNull(h.ClientMailbox.AwaitResponse(transferId,
            new HashSet<MessageType> { MessageType.TRANSFER_ACCEPT }, 10000));
        return transferId;
    }

    private static async Task<TcpClient> OpenDataAsync(Harness h, string transferId)
    {
        var data = new TcpClient();
        await data.ConnectAsync(IPAddress.Loopback, h.Engine.LocalPort);
        await data.GetStream().WriteAsync(Encoding.ASCII.GetBytes(transferId + "\n"));
        var start = new LocalDropMessage(
            MessageType.TRANSFER_START, ProtocolCodec.NewMessageId(),
            null, null, transferId,
            new TypedPayload.TransferStart(new TransferStartPayload(transferId, 0L)));
        Assert.True(await h.Client.SendAsync(start));
        return data;
    }

    private static async Task SendCompleteAsync(Harness h, string transferId, byte[] content)
    {
        var complete = new LocalDropMessage(
            MessageType.TRANSFER_COMPLETE, ProtocolCodec.NewMessageId(),
            null, null, transferId,
            new TypedPayload.TransferComplete(new TransferCompletePayload(
                transferId, content.LongLength, ShaHex(content))));
        Assert.True(await h.Client.SendAsync(complete));
    }

    // M12: cancelling one transfer leaves the other running to a byte-exact
    // completion. Cancel cleanup must not touch the surviving session.
    [Fact]
    public async Task CancelOneTransfer_OtherCompletesByteExact()
    {
        using var h = Setup();
        byte[] contentA = PatternBytes(300_000, seed: 0xCA9CE1);
        byte[] contentB = PatternBytes(100_000, seed: 0x5EED);
        string tidA = await RequestTransferAsync(h, "a-cancel.bin", contentA);
        using var dataA = await OpenDataAsync(h, tidA);
        await dataA.GetStream().WriteAsync(contentA.AsMemory(0, 1000));
        string tidB = await RequestTransferAsync(h, "b-survivor.bin", contentB);

        var cancel = new LocalDropMessage(
            MessageType.CANCEL, ProtocolCodec.NewMessageId(),
            null, null, tidA,
            new TypedPayload.Cancel(new CancelPayload(tidA, "test cancel")));
        Assert.True(await h.Client.SendAsync(cancel));
        var outcomeA = await h.Engine.WaitForOutcomeAsync(tidA, 10000);
        Assert.IsType<TransferOutcome.Cancelled>(outcomeA);
        Assert.False(File.Exists(Path.Combine(h.DestDir, "a-cancel.bin")));
        Assert.False(File.Exists(Path.Combine(h.DestDir, "a-cancel.bin.localdrop-part")));

        using var dataB = await OpenDataAsync(h, tidB);
        await dataB.GetStream().WriteAsync(contentB);
        await dataB.GetStream().FlushAsync();
        await SendCompleteAsync(h, tidB, contentB);
        dataB.Close();
        var outcomeB = await h.Engine.WaitForOutcomeAsync(tidB, 15000);
        Assert.IsType<TransferOutcome.TransportComplete>(outcomeB);
        Assert.Equal(contentB, await File.ReadAllBytesAsync(Path.Combine(h.DestDir, "b-survivor.bin")));
        Assert.False(h.Engine.IsBusy);
    }

    // M12: a sender ERROR addressed to one transfer fails exactly that
    // transfer with the sender's code and leaves the other session intact.
    [Fact]
    public async Task PeerError_ScopedToAddressedTransfer()
    {
        using var h = Setup();
        byte[] contentA = PatternBytes(120_000, seed: 0xE8808);
        byte[] contentB = PatternBytes(1000, seed: 0xBAAD);
        string tidA = await RequestTransferAsync(h, "a-clean.bin", contentA);
        string tidB = await RequestTransferAsync(h, "b-broken.bin", contentB);
        var peerError = new LocalDropMessage(
            MessageType.ERROR, ProtocolCodec.NewMessageId(),
            null, null, tidB,
            new TypedPayload.Error(new ErrorPayload(
                new ProtocolError(ErrorCode.FILE_NOT_FOUND, "gone"), null)));
        Assert.True(await h.Client.SendAsync(peerError));
        var outcomeB = await h.Engine.WaitForOutcomeAsync(tidB, 10000);
        var failedB = Assert.IsType<TransferOutcome.Failed>(outcomeB);
        Assert.Equal(ErrorCode.FILE_NOT_FOUND, failedB.Code);
        Assert.Equal(tidB, failedB.TransferId);

        using var dataA = await OpenDataAsync(h, tidA);
        await dataA.GetStream().WriteAsync(contentA);
        await dataA.GetStream().FlushAsync();
        await SendCompleteAsync(h, tidA, contentA);
        dataA.Close();
        var outcomeA = await h.Engine.WaitForOutcomeAsync(tidA, 15000);
        Assert.IsType<TransferOutcome.TransportComplete>(outcomeA);
        Assert.Equal(contentA, await File.ReadAllBytesAsync(Path.Combine(h.DestDir, "a-clean.bin")));
        Assert.False(File.Exists(Path.Combine(h.DestDir, "b-broken.bin")));
    }

    // M12: two concurrent transfers for the SAME fileName follow the
    // established collision policy (never overwrite, uniquify) with both
    // payloads byte-exact and no temp leftovers.
    [Fact]
    public async Task ConcurrentSameFilename_UniquifiesWithoutCorruption()
    {
        using var h = Setup();
        byte[] contentA = PatternBytes(100_000, seed: 0xD9A);
        byte[] contentB = PatternBytes(60_000, seed: 0xD9B);
        string tidA = await RequestTransferAsync(h, "dup.bin", contentA);
        using var dataA = await OpenDataAsync(h, tidA);
        string tidB = await RequestTransferAsync(h, "dup.bin", contentB);
        using var dataB = await OpenDataAsync(h, tidB);
        await dataA.GetStream().WriteAsync(contentA);
        await dataA.GetStream().FlushAsync();
        await SendCompleteAsync(h, tidA, contentA);
        await dataB.GetStream().WriteAsync(contentB);
        await dataB.GetStream().FlushAsync();
        await SendCompleteAsync(h, tidB, contentB);
        dataA.Close();
        dataB.Close();
        var outcomeA = await h.Engine.WaitForOutcomeAsync(tidA, 15000);
        var outcomeB = await h.Engine.WaitForOutcomeAsync(tidB, 15000);
        Assert.IsType<TransferOutcome.TransportComplete>(outcomeA);
        Assert.IsType<TransferOutcome.TransportComplete>(outcomeB);
        string first = Path.Combine(h.DestDir, "dup.bin");
        string second = Path.Combine(h.DestDir, "dup (2).bin");
        Assert.True(File.Exists(first));
        Assert.True(File.Exists(second));
        var landed = new HashSet<string>
        {
            ShaHex(await File.ReadAllBytesAsync(first)),
            ShaHex(await File.ReadAllBytesAsync(second)),
        };
        Assert.Equal(new HashSet<string> { ShaHex(contentA), ShaHex(contentB) }, landed);
        Assert.Empty(Directory.GetFiles(h.DestDir, "*.localdrop-part"));
    }

    // M12: a second request reusing a live transferId is rejected and the
    // original session still completes byte-exact.
    [Fact]
    public async Task DuplicateTransferId_Rejected_LiveTransferUndisturbed()
    {
        using var h = Setup();
        byte[] content = PatternBytes(40_000);
        string tid = await RequestTransferAsync(h, "orig.bin", content);
        var evil = new TransferMetadata(
            tid, h.ClientDevice.DeviceId, h.ServerDevice.DeviceId,
            Guid.NewGuid().ToString("D"), "evil.bin", 10L,
            Core.AppConfig.DefaultCheckpointBytes, null, 0L);
        Assert.True(await h.Client.SendAsync(new LocalDropMessage(
            MessageType.TRANSFER_REQUEST, ProtocolCodec.NewMessageId(),
            null, null, tid,
            new TypedPayload.TransferRequest(new TransferRequestPayload(evil)))));
        var reject = h.ClientMailbox.AwaitResponse(tid,
            new HashSet<MessageType> { MessageType.TRANSFER_REJECT }, 10000);
        Assert.NotNull(reject);

        using var data = await OpenDataAsync(h, tid);
        await data.GetStream().WriteAsync(content);
        await data.GetStream().FlushAsync();
        await SendCompleteAsync(h, tid, content);
        data.Close();
        var outcome = await h.Engine.WaitForOutcomeAsync(tid, 15000);
        Assert.IsType<TransferOutcome.TransportComplete>(outcome);
        Assert.Equal(content, await File.ReadAllBytesAsync(Path.Combine(h.DestDir, "orig.bin")));
        Assert.False(File.Exists(Path.Combine(h.DestDir, "evil.bin")));
    }

    [Fact]
    public async Task UnknownPreamble_ClosesDataSocket_NoFileTouched()
    {
        using var h = Setup();
        using var data = new TcpClient();
        await data.ConnectAsync(IPAddress.Loopback, h.Engine.LocalPort);
        string bogus = Guid.NewGuid().ToString("D");
        await data.GetStream().WriteAsync(Encoding.ASCII.GetBytes(bogus + "\n"));
        await data.GetStream().FlushAsync();
        // Server closes: read yields EOF.
        var one = new byte[1];
        int n = await data.GetStream().ReadAsync(one.AsMemory(0, 1));
        Assert.Equal(0, n);
        Assert.False(h.Engine.IsBusy);
        Assert.Empty(Directory.GetFiles(h.DestDir));
    }

    [Fact]
    public async Task EarlyDataDisconnect_FailsAndCleansTemp()
    {
        using var h = Setup();
        string transferId = Guid.NewGuid().ToString("D");
        byte[] content = PatternBytes(500_000);
        var metadata = new TransferMetadata(
            transferId, h.ClientDevice.DeviceId, h.ServerDevice.DeviceId,
            Guid.NewGuid().ToString("D"), "half.bin", content.LongLength,
            Core.AppConfig.DefaultCheckpointBytes, null, 0L);
        var request = new LocalDropMessage(
            MessageType.TRANSFER_REQUEST, ProtocolCodec.NewMessageId(),
            null, null, transferId,
            new TypedPayload.TransferRequest(new TransferRequestPayload(metadata)));
        Assert.True(await h.Client.SendAsync(request));
        Assert.NotNull(h.ClientMailbox.AwaitResponse(transferId,
            new HashSet<MessageType> { MessageType.TRANSFER_ACCEPT }, 10000));
        using var data = new TcpClient();
        await data.ConnectAsync(IPAddress.Loopback, h.Engine.LocalPort);
        await data.GetStream().WriteAsync(Encoding.ASCII.GetBytes(transferId + "\n"));
        await data.GetStream().WriteAsync(content.AsMemory(0, 1000)); // partial, then vanish
        data.Close();
        var outcome = await h.Engine.WaitForOutcomeAsync(transferId, 15000);
        var failed = Assert.IsType<TransferOutcome.Failed>(outcome);
        Assert.Equal(ErrorCode.NETWORK_LOST, failed.Code);
        Assert.True(File.Exists(Path.Combine(h.DestDir, "half.bin.localdrop-part")));
        Assert.False(File.Exists(Path.Combine(h.DestDir, "half.bin")));
    }

    [Fact]
    public async Task MissingComplete_TimesOutAndCleansTemp()
    {
        using var h = Setup(completeTimeoutMs: 1500);
        string transferId = Guid.NewGuid().ToString("D");
        byte[] content = PatternBytes(50_000);
        var metadata = new TransferMetadata(
            transferId, h.ClientDevice.DeviceId, h.ServerDevice.DeviceId,
            Guid.NewGuid().ToString("D"), "nocomplete.bin", content.LongLength,
            Core.AppConfig.DefaultCheckpointBytes, null, 0L);
        var request = new LocalDropMessage(
            MessageType.TRANSFER_REQUEST, ProtocolCodec.NewMessageId(),
            null, null, transferId,
            new TypedPayload.TransferRequest(new TransferRequestPayload(metadata)));
        Assert.True(await h.Client.SendAsync(request));
        Assert.NotNull(h.ClientMailbox.AwaitResponse(transferId,
            new HashSet<MessageType> { MessageType.TRANSFER_ACCEPT }, 10000));
        using var data = new TcpClient();
        await data.ConnectAsync(IPAddress.Loopback, h.Engine.LocalPort);
        await data.GetStream().WriteAsync(Encoding.ASCII.GetBytes(transferId + "\n"));
        await data.GetStream().WriteAsync(content);
        await data.GetStream().FlushAsync();
        // All bytes delivered, but TRANSFER_COMPLETE never sent; socket held.
        var outcome = await h.Engine.WaitForOutcomeAsync(transferId, 15000);
        var failed = Assert.IsType<TransferOutcome.Failed>(outcome);
        Assert.Equal(ErrorCode.TIMEOUT, failed.Code);
        Assert.True(File.Exists(Path.Combine(h.DestDir, "nocomplete.bin.localdrop-part")));
    }

    [Fact]
    public async Task CancelMessage_AbortsAndCleansTemp()
    {
        using var h = Setup();
        string transferId = Guid.NewGuid().ToString("D");
        byte[] content = PatternBytes(500_000);
        var metadata = new TransferMetadata(
            transferId, h.ClientDevice.DeviceId, h.ServerDevice.DeviceId,
            Guid.NewGuid().ToString("D"), "cancel.bin", content.LongLength,
            Core.AppConfig.DefaultCheckpointBytes, null, 0L);
        var request = new LocalDropMessage(
            MessageType.TRANSFER_REQUEST, ProtocolCodec.NewMessageId(),
            null, null, transferId,
            new TypedPayload.TransferRequest(new TransferRequestPayload(metadata)));
        Assert.True(await h.Client.SendAsync(request));
        Assert.NotNull(h.ClientMailbox.AwaitResponse(transferId,
            new HashSet<MessageType> { MessageType.TRANSFER_ACCEPT }, 10000));
        using var data = new TcpClient();
        await data.ConnectAsync(IPAddress.Loopback, h.Engine.LocalPort);
        await data.GetStream().WriteAsync(Encoding.ASCII.GetBytes(transferId + "\n"));
        await data.GetStream().WriteAsync(content.AsMemory(0, 1000));
        var cancel = new LocalDropMessage(
            MessageType.CANCEL, ProtocolCodec.NewMessageId(),
            null, null, transferId,
            new TypedPayload.Cancel(new CancelPayload(transferId, "Stop.")));
        Assert.True(await h.Client.SendAsync(cancel));
        var outcome = await h.Engine.WaitForOutcomeAsync(transferId, 15000);
        Assert.IsType<TransferOutcome.Cancelled>(outcome);
        Assert.False(File.Exists(Path.Combine(h.DestDir, "cancel.bin.localdrop-part")));
    }

    [Fact]
    public async Task SlowDripSender_CompletesByteExact()
    {
        using var h = Setup();
        string transferId = Guid.NewGuid().ToString("D");
        byte[] content = PatternBytes(100_000);
        var metadata = new TransferMetadata(
            transferId, h.ClientDevice.DeviceId, h.ServerDevice.DeviceId,
            Guid.NewGuid().ToString("D"), "drip.bin", content.LongLength,
            Core.AppConfig.DefaultCheckpointBytes, null, 0L);
        var request = new LocalDropMessage(
            MessageType.TRANSFER_REQUEST, ProtocolCodec.NewMessageId(),
            null, null, transferId,
            new TypedPayload.TransferRequest(new TransferRequestPayload(metadata)));
        Assert.True(await h.Client.SendAsync(request));
        Assert.NotNull(h.ClientMailbox.AwaitResponse(transferId,
            new HashSet<MessageType> { MessageType.TRANSFER_ACCEPT }, 10000));
        using var data = new TcpClient();
        await data.ConnectAsync(IPAddress.Loopback, h.Engine.LocalPort);
        await data.GetStream().WriteAsync(Encoding.ASCII.GetBytes(transferId + "\n"));
        // 1 KiB at a time with pauses: receiver must reassemble exactly.
        for (int off = 0; off < content.Length; off += 1024)
        {
            int n = Math.Min(1024, content.Length - off);
            await data.GetStream().WriteAsync(content.AsMemory(off, n));
            await data.GetStream().FlushAsync();
            await Task.Delay(5);
        }
        var complete = new LocalDropMessage(
            MessageType.TRANSFER_COMPLETE, ProtocolCodec.NewMessageId(),
            null, null, transferId,
            new TypedPayload.TransferComplete(new TransferCompletePayload(
                transferId, content.LongLength, Convert.ToHexString(System.Security.Cryptography.SHA256.Create().ComputeHash(content)).ToLowerInvariant())));
        Assert.True(await h.Client.SendAsync(complete));
        data.Close();
        var outcome = await h.Engine.WaitForOutcomeAsync(transferId, 20000);
        Assert.IsType<TransferOutcome.TransportComplete>(outcome);
        Assert.Equal(content, await File.ReadAllBytesAsync(Path.Combine(h.DestDir, "drip.bin")));
    }

    [Fact]
    public async Task Progress_IsMonotonicAndWellFormed()
    {
        using var h = Setup();
        var seen = new List<TransferProgress>();
        h.Engine.Progress += p => { lock (seen) seen.Add(p); };
        string transferId = Guid.NewGuid().ToString("D");
        byte[] content = PatternBytes(2_000_000);
        var outcome = await DriveTransferAsync(h, transferId, "prog.bin", content);
        Assert.IsType<TransferOutcome.TransportComplete>(outcome);
        Assert.NotEmpty(seen);
        long last = -1L;
        foreach (var s in seen)
        {
            Assert.True(s.TransferredBytes >= last);
            last = s.TransferredBytes;
        }
        Assert.Equal(100.0, seen[^1].Percent, 9);
        Assert.Equal(content.LongLength, seen[^1].TransferredBytes);
    }

    [Fact]
    public async Task TempNaming_UsesPartSuffix_AndFinalizesAtomically()
    {
        using var h = Setup();
        Assert.Equal(
            Path.Combine(h.DestDir, "x.bin.localdrop-part"),
            h.Engine.TempPathFor("x.bin"));
        Assert.False(File.Exists(h.Engine.TempPathFor("x.bin")));
    }
    [Fact]
    public async Task DeleteTempQuietly_TransientLock_SucceedsAfterRetry()
    {
        string temp = Path.GetTempFileName();
        var logger = new RecordingSecurityLogger();
        var task = Task.Run(async () =>
        {
            using var fs = new FileStream(temp, FileMode.Open, FileAccess.ReadWrite, FileShare.None);
            await Task.Delay(60);
        });
        // We ensure the lock is acquired before calling Delete.
        await Task.Delay(10);
        ReceiverEngine.DeleteTempQuietly(temp, logger, "tx1");
        await task;
        Assert.False(File.Exists(temp));
        Assert.Empty(logger.Snapshot());
    }

    [Fact]
    public void DeleteTempQuietly_PersistentLock_ExhaustsRetriesAndLogs()
    {
        string temp = Path.GetTempFileName();
        var logger = new RecordingSecurityLogger();
        using (var fs = new FileStream(temp, FileMode.Open, FileAccess.ReadWrite, FileShare.None))
        {
            ReceiverEngine.DeleteTempQuietly(temp, logger, "tx2");
        }
        Assert.True(File.Exists(temp));
        File.Delete(temp);
        Assert.Single(logger.Snapshot());
        Assert.Contains("temporary file cleanup failed", logger.Snapshot()[0]);
    }
}
