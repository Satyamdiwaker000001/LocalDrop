// M07 Windows sender engine (mirrors Android SenderEngine, M06 ADRs 1-2,
// 9-11, 13-18, 20-23, reused verbatim for the reverse direction per the M07
// reuse decision A/C). Streams one file over a dedicated TCP data connection
// to an M05-authorized Android receiver. Requires: established M04 control
// channel + completed M05 authorization (the receiver enforces both; this
// engine only uses the channel). Fully async; never blocks the WPF UI thread.
// Single transfer per call (M06 scope; M12 owns orchestration).
using System.Net.Sockets;
using System.IO;
using System.Text;
using LocalDrop.Core;
using LocalDrop.Network;
using LocalDrop.Protocol;
using System.Security.Cryptography;

namespace LocalDrop.Transfer;

public sealed class SenderEngine
{
    private readonly string _localDeviceId;
    private readonly ControlConnection _control;
    private readonly TransferMailbox _mailbox;
    private readonly int _dataPort;
    private readonly int _requestTimeoutMs;
    private readonly int _dataConnectTimeoutMs;
    private readonly long _progressIntervalMs;

    public sealed record SendRequest(
        ITransferSource Source,
        string ReceiverDeviceId,
        string? TransferId = null,
        string? FileId = null,
        // Null = derive from the control connection's remote address (the
        // receiver we are already talking to; M06 ADR: control-derived endpoint).
        string? DataHost = null,
        CancellationToken CancellationToken = default,
        Action<TransferProgress>? OnProgress = null);

    public SenderEngine(
        string localDeviceId,
        ControlConnection control,
        TransferMailbox mailbox,
        int dataPort = AppConfig.DataTcpPort,
        int requestTimeoutMs = AppConfig.TransferRequestTimeoutMs,
        int dataConnectTimeoutMs = AppConfig.DataConnectTimeoutMs,
        long progressIntervalMs = AppConfig.TransferProgressIntervalMs)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(localDeviceId);
        ArgumentNullException.ThrowIfNull(control);
        ArgumentNullException.ThrowIfNull(mailbox);
        _localDeviceId = localDeviceId;
        _control = control;
        _mailbox = mailbox;
        _dataPort = dataPort;
        _requestTimeoutMs = requestTimeoutMs;
        _dataConnectTimeoutMs = dataConnectTimeoutMs;
        _progressIntervalMs = progressIntervalMs;
    }

    public async Task<TransferOutcome> SendAsync(SendRequest req, CancellationToken ct = default)
    {
        using var linkedCts = CancellationTokenSource.CreateLinkedTokenSource(ct, req.CancellationToken);
        var token = linkedCts.Token;
        string transferId = req.TransferId ?? Uuids.Random();
        string fileId = req.FileId ?? Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes($"{req.Source.DisplayName}|{req.Source.SizeBytes}|{req.Source.LastModifiedMs}"))).ToLowerInvariant();
        Emit(req, transferId, TransferEngineState.CONNECTING, 0L, req.Source.SizeBytes, 0L, null);
        // 1. Local source validation (fail before touching the network).
        string? nameError = ValidateFileName(req.Source.DisplayName);
        if (nameError is not null)
            return Fail(transferId, ErrorCode.INTERNAL_ERROR, nameError);
        long declaredSize;
        try
        {
            declaredSize = req.Source.SizeBytes;
        }
        catch (Exception ex)
        {
            var (code, detail) = TransferErrors.SourceUnavailable(ex.Message);
            await SendErrorBestEffortAsync(transferId, code, detail);
            return Fail(transferId, code, detail);
        }
        if (declaredSize < 0L)
            return Fail(transferId, ErrorCode.INTERNAL_ERROR, "Negative source size.");
        // 2. TRANSFER_REQUEST over control; await ACCEPT/REJECT/ERROR.
        Emit(req, transferId, TransferEngineState.WAITING_ACCEPTANCE, 0L, declaredSize, 0L, null);
        var metadata = new TransferMetadata(
            transferId, _localDeviceId, req.ReceiverDeviceId,
            fileId, req.Source.DisplayName, declaredSize,
            AppConfig.DefaultCheckpointBytes, null, 0L); // M11 owns hashing; M10 owns resume.
        var request = new LocalDropMessage(
            MessageType.TRANSFER_REQUEST, ProtocolCodec.NewMessageId(),
            null, null, transferId,
            new TypedPayload.TransferRequest(new TransferRequestPayload(metadata)));
        bool sent;
        try
        {
            sent = await _control.SendAsync(request, token);
        }
        catch (OperationCanceledException)
        {
            return new TransferOutcome.Cancelled(transferId, 0L);
        }
        if (!sent)
            return Fail(transferId, ErrorCode.NETWORK_LOST, "Control send failed.");
        LocalDropMessage? response;
        try
        {
            // The token only prevents a queued start; a pre-cancelled token
            // must map to Cancelled (not escape as TaskCanceledException),
            // matching the cooperative-cancel contract on every other path.
            response = await Task.Run(() => _mailbox.AwaitResponse(
                transferId,
                new HashSet<MessageType> { MessageType.TRANSFER_ACCEPT, MessageType.TRANSFER_REJECT, MessageType.ERROR },
                _requestTimeoutMs), token);
        }
        catch (OperationCanceledException)
        {
            await SendCancelBestEffortAsync(transferId);
            return new TransferOutcome.Cancelled(transferId, 0L);
        }
        if (token.IsCancellationRequested)
        {
            await SendCancelBestEffortAsync(transferId);
            return new TransferOutcome.Cancelled(transferId, 0L);
        }
        if (response is null)
            return Fail(transferId, ErrorCode.TIMEOUT, "No transfer response.");
        long resumeOffset = 0L;
        switch (response.Payload)
        {
            case TypedPayload.TransferReject rej:
                _mailbox.Forget(transferId);
                return new TransferOutcome.Failed(
                    transferId, rej.V.Error.Code, rej.V.Error.Message);
            case TypedPayload.Error err:
                _mailbox.Forget(transferId);
                return new TransferOutcome.Failed(
                    transferId, err.V.Error.Code, err.V.Error.Message);
            case TypedPayload.TransferAccept acc:
                if (acc.V.TransferId != transferId)
                {
                    _mailbox.Forget(transferId);
                    return Fail(transferId, ErrorCode.INVALID_PROTOCOL, "Mismatched TRANSFER_ACCEPT.");
                }
                resumeOffset = acc.V.ResumeOffset;
                break;
            default:
                _mailbox.Forget(transferId);
                return Fail(transferId, ErrorCode.INVALID_PROTOCOL, "Unexpected transfer response.");
        }
        // 3. Dedicated data connection (never bulk bytes on control, ADR 9).
        Emit(req, transferId, TransferEngineState.TRANSFERRING, 0L, declaredSize, 0L, null);
        string? host = req.DataHost ?? DeriveControlPeerHost(_control);
        if (host is null)
            return Fail(transferId, ErrorCode.NETWORK_LOST, "Cannot resolve receiver host.");
        var dataClient = new TcpClient();
        try
        {
            using var connectCts = CancellationTokenSource.CreateLinkedTokenSource(token);
            connectCts.CancelAfter(_dataConnectTimeoutMs);
            try
            {
                await dataClient.ConnectAsync(host, _dataPort, connectCts.Token);
            }
            catch (OperationCanceledException) when (!token.IsCancellationRequested)
            {
                dataClient.Dispose();
                return Fail(transferId, ErrorCode.TIMEOUT, "Data connect timed out.");
            }
            catch (OperationCanceledException)
            {
                dataClient.Dispose();
                await SendCancelBestEffortAsync(transferId);
                return new TransferOutcome.Cancelled(transferId, 0L);
            }
            catch (Exception ex)
            {
                dataClient.Dispose();
                return Fail(transferId, ErrorCode.NETWORK_LOST, $"Data connect failed: {ex.Message}");
            }
            try
            {
                // 4. Data-plane preamble binds this socket to the authorized
                // transfer (37 bytes: transferId + '\n'; receiver rejects unknown).
                byte[] preamble = Encoding.ASCII.GetBytes(transferId + "\n");
                var dataStream = dataClient.GetStream();
                await dataStream.WriteAsync(preamble, token);
                await dataStream.FlushAsync(token);
                // 5. TRANSFER_START marker, then the byte stream.
                var start = new LocalDropMessage(
                    MessageType.TRANSFER_START, ProtocolCodec.NewMessageId(),
                    null, null, transferId,
                    new TypedPayload.TransferStart(new TransferStartPayload(transferId, resumeOffset)));
                if (!await _control.SendAsync(start, token))
                    return Fail(transferId, ErrorCode.NETWORK_LOST, "Control lost before streaming.");
                return await StreamBytesAsync(req, transferId, declaredSize, resumeOffset, dataClient, token);
            }
            finally
            {
                _mailbox.Forget(transferId);
                try { dataClient.Dispose(); } catch { }
            }
        }
        catch (OperationCanceledException)
        {
            _mailbox.Forget(transferId);
            try { dataClient.Dispose(); } catch { }
            await SendCancelBestEffortAsync(transferId);
            return new TransferOutcome.Cancelled(transferId, 0L);
        }
    }

    private async Task<TransferOutcome> StreamBytesAsync(
        SendRequest req, string transferId, long total, long resumeOffset, TcpClient dataClient, CancellationToken token)
    {
        using var hasher = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
        var sizer = new AdaptiveBlockSizer(total);
        byte[] buffer = new byte[sizer.CurrentBlockSize];
        long transferred = resumeOffset;
        long startNanos = NanoNow();
        long readNanosTotal = 0L;
        long writeNanosTotal = 0L;
        long stalls = 0L;
        long lastProgressEmitMs = 0L;
        long windowBytes = 0L;
        long windowStartNanos = startNanos;
        double? recentBps = null;
        Stream input;
        try
        {
            input = req.Source.OpenStream();
            if (resumeOffset > 0)
            {
                if (!input.CanSeek)
                {
                    input.Dispose();
                    var (code, detail) = TransferErrors.SourceUnavailable("Source stream does not support seeking for resume.");
                    await SendErrorBestEffortAsync(transferId, code, detail);
                    return Fail(transferId, code, detail);
                }
                long remainingPrefix = resumeOffset;
                while (remainingPrefix > 0)
                {
                    token.ThrowIfCancellationRequested();
                    int toRead = (int)Math.Min(remainingPrefix, buffer.Length);
                    int n = await input.ReadAsync(buffer.AsMemory(0, toRead), token);
                    if (n == 0)
                    {
                        input.Dispose();
                        var (code, detail) = TransferErrors.Internal($"Source shrunk during prefix hash (expected {resumeOffset} prefix bytes).");
                        await SendErrorBestEffortAsync(transferId, code, detail);
                        return Fail(transferId, code, detail);
                    }
                    hasher.AppendData(buffer, 0, n);
                    remainingPrefix -= n;
                }
            }
        }
        catch (FileNotFoundException ex)
        {
            var (code, detail) = TransferErrors.SourceUnavailable(ex.Message);
            await SendErrorBestEffortAsync(transferId, code, detail);
            return Fail(transferId, code, detail);
        }
        catch (IOException ex)
        {
            var (code, detail) = TransferErrors.SourceUnavailable(ex.Message);
            await SendErrorBestEffortAsync(transferId, code, detail);
            return Fail(transferId, code, detail);
        }
        catch (UnauthorizedAccessException ex)
        {
            var (code, detail) = TransferErrors.SourceUnavailable(ex.Message);
            await SendErrorBestEffortAsync(transferId, code, detail);
            return Fail(transferId, code, detail);
        }
        using (input)
        {
            NetworkStream? netStream;
            try
            {
                netStream = dataClient.GetStream();
            }
            catch (Exception ex)
            {
                var (code, detail) = TransferErrors.ConnectionLost(ex.Message);
                await SendErrorBestEffortAsync(transferId, code, detail);
                return Fail(transferId, code, detail);
            }
            while (true)
            {
                if (token.IsCancellationRequested)
                {
                    await SendCancelBestEffortAsync(transferId);
                    return new TransferOutcome.Cancelled(transferId, transferred);
                }
                if (buffer.Length != sizer.CurrentBlockSize)
                    buffer = new byte[sizer.CurrentBlockSize];
                // Bounded single-block read (never the whole file, ADR 1).
                long readStart = NanoNow();
                int n;
                try
                {
                    n = await input.ReadAsync(buffer.AsMemory(0, buffer.Length), token);
                }
                catch (OperationCanceledException)
                {
                    await SendCancelBestEffortAsync(transferId);
                    return new TransferOutcome.Cancelled(transferId, transferred);
                }
                catch (Exception ex)
                {
                    var (code, detail) = TransferErrors.SourceReadFailed(ex.Message);
                    await SendErrorBestEffortAsync(transferId, code, detail);
                    return Fail(transferId, code, detail);
                }
                long readNanos = NanoNow() - readStart;
                readNanosTotal += readNanos;
                if (n == 0) break; // source EOF
                hasher.AppendData(buffer, 0, n);
                if (transferred + n > total)
                {
                    var (code, detail) = TransferErrors.Internal(
                        $"Source grew during transfer (declared {total}).");
                    await SendErrorBestEffortAsync(transferId, code, detail);
                    return Fail(transferId, code, detail);
                }
                // Bounded single-block write; the blocking write IS the
                // backpressure mechanism (ADR 11) — no queues anywhere.
                long writeStart = NanoNow();
                try
                {
                    await netStream.WriteAsync(buffer.AsMemory(0, n), token);
                }
                catch (OperationCanceledException)
                {
                    await SendCancelBestEffortAsync(transferId);
                    return new TransferOutcome.Cancelled(transferId, transferred);
                }
                catch (Exception ex)
                {
                    var (code, detail) = TransferErrors.ConnectionLost(ex.Message);
                    await SendErrorBestEffortAsync(transferId, code, detail);
                    return Fail(transferId, code, detail);
                }
                long writeNanos = NanoNow() - writeStart;
                writeNanosTotal += writeNanos;
                transferred += n;
                long blockNanos = readNanos + writeNanos;
                bool stalled = blockNanos / 1_000_000L > AppConfig.AdaptStallThresholdMs;
                if (stalled) stalls++;
                sizer.Observe(n, blockNanos, stalled);
                windowBytes += n;
                // Throttled progress: local snapshot + peer TRANSFER_PROGRESS.
                long nowMs = Environment.TickCount64;
                if (nowMs - lastProgressEmitMs >= _progressIntervalMs)
                {
                    lastProgressEmitMs = nowMs;
                    long windowNanos = NanoNow() - windowStartNanos;
                    recentBps = windowNanos > 0 ? windowBytes * 1e9 / windowNanos : null;
                    windowBytes = 0L;
                    windowStartNanos = NanoNow();
                    Emit(req, transferId, TransferEngineState.TRANSFERRING, transferred, total,
                        (NanoNow() - startNanos) / 1_000_000L, recentBps);
                    var progress = new LocalDropMessage(
                        MessageType.TRANSFER_PROGRESS, ProtocolCodec.NewMessageId(),
                        null, null, transferId,
                        new TypedPayload.TransferProgress(
                            new TransferProgressPayload(transferId, transferred, total)));
                    bool progressSent;
                    try
                    {
                        progressSent = await _control.SendAsync(progress, token);
                    }
                    catch (OperationCanceledException)
                    {
                        await SendCancelBestEffortAsync(transferId);
                        return new TransferOutcome.Cancelled(transferId, transferred);
                    }
                    if (!progressSent)
                        return Fail(transferId, ErrorCode.NETWORK_LOST, "Control lost mid-transfer.");
                }
            }
        }
        if (transferred != total)
        {
            var (code, detail) = TransferErrors.Internal(
                $"Source truncated (declared {total}, read {transferred}).");
            await SendErrorBestEffortAsync(transferId, code, detail);
            return Fail(transferId, code, detail);
        }
        // 6. Transport completion: final progress + TRANSFER_COMPLETE with the
        // M06 zeros sentinel (M02 shape satisfied; meaning documented — M11
        // owns verification; explicitly NOT verification).
        long elapsedMs = (NanoNow() - startNanos) / 1_000_000L;
        double? average = elapsedMs > 0 ? transferred * 1000.0 / elapsedMs : null;
        Emit(req, transferId, TransferEngineState.TRANSPORT_COMPLETE, transferred, total, elapsedMs, recentBps);
        string sha256Hex = Convert.ToHexString(hasher.GetHashAndReset()).ToLowerInvariant();
        var complete = new LocalDropMessage(
            MessageType.TRANSFER_COMPLETE, ProtocolCodec.NewMessageId(),
            null, null, transferId,
            new TypedPayload.TransferComplete(
                new TransferCompletePayload(transferId, transferred, sha256Hex)));
        bool completedSent;
        try
        {
            completedSent = await _control.SendAsync(complete, token);
        }
        catch (OperationCanceledException)
        {
            await SendCancelBestEffortAsync(transferId);
            return new TransferOutcome.Cancelled(transferId, transferred);
        }
        if (!completedSent)
            return Fail(transferId, ErrorCode.NETWORK_LOST, "Completion signal lost.");
        return new TransferOutcome.TransportComplete(
            transferId, transferred, elapsedMs, average,
            new TransferMetrics(elapsedMs, transferred, average, readNanosTotal, writeNanosTotal,
                stalls, [.. sizer.History], sizer.CurrentBlockSize));
    }

    private async Task SendCancelBestEffortAsync(string transferId)
    {
        try
        {
            await _control.SendAsync(new LocalDropMessage(
                MessageType.CANCEL, ProtocolCodec.NewMessageId(),
                null, null, transferId,
                new TypedPayload.Cancel(new CancelPayload(transferId, "Sender cancelled."))));
        }
        catch { }
    }

    private async Task SendErrorBestEffortAsync(string transferId, ErrorCode code, string detail)
    {
        try
        {
            await _control.SendAsync(new LocalDropMessage(
                MessageType.ERROR, ProtocolCodec.NewMessageId(),
                null, null, transferId,
                new TypedPayload.Error(new ErrorPayload(new ProtocolError(code, detail), null))));
        }
        catch { }
    }

    private static void Emit(
        SendRequest req, string transferId, TransferEngineState state, long transferred, long total,
        long elapsedMs, double? recentBps)
    {
        try
        {
            req.OnProgress?.Invoke(TransferProgress.Compute(
                transferId, total, transferred, elapsedMs, recentBps, state));
        }
        catch
        {
            // Listener bugs must not break the transfer.
        }
    }

    private static TransferOutcome.Failed Fail(string? transferId, ErrorCode code, string detail) =>
        new(transferId, code, detail);

    // 64 zeros = "hash not computed (M11 pending)". Satisfies the frozen M02
    // 64-hex shape; documented meaning, never verification (M06 ADR 20,
    // reused unchanged for M07).
    public const string M07HashNotComputed =
        "0000000000000000000000000000000000000000000000000000000000000000";

    public static string? ValidateFileName(string name)
    {
        if (name.Length == 0 || name.Length > 255) return "Invalid file name length.";
        if (name.Contains('/') || name.Contains('\\') || name is "." or "..")
            return "File name must be a base name.";
        return null;
    }

    // Derives the data-connection host from the control connection we already
    // hold to the receiver (M06 ADR: control-derived endpoint — no discovery
    // changes, no manual IP entry). Handles "host:port" and "[v6]:port"
    // forms; null when unparseable (caller fails cleanly).
    public static string? DeriveControlPeerHost(ControlConnection control)
    {
        string raw = control.RemoteAddress.Trim();
        if (raw.Length == 0 || raw == "(none)") return null;
        string hostPort = raw.Contains('/') ? raw[(raw.LastIndexOf('/') + 1)..] : raw;
        string host = hostPort.Contains(':') ? hostPort[..hostPort.LastIndexOf(':')] : hostPort;
        if (host.Length == 0) return null;
        host = host.TrimStart('[').TrimEnd(']');
        // Dual-stack listeners report IPv4 peers as "::ffff:127.0.0.1"; the
        // data dial accepts that form, but normalize to the dotted quad for
        // stable logs and for stacks that dislike the mapped form.
        const string mappedPrefix = "::ffff:";
        if (host.StartsWith(mappedPrefix, StringComparison.Ordinal) &&
            System.Net.IPAddress.TryParse(host[mappedPrefix.Length..], out var v4) &&
            v4.AddressFamily == System.Net.Sockets.AddressFamily.InterNetwork)
            return v4.ToString();
        return host;
    }

    private static long NanoNow()
    {
        long freq = System.Diagnostics.Stopwatch.Frequency;
        return System.Diagnostics.Stopwatch.GetTimestamp() * 1_000_000_000L / freq;
    }
}
