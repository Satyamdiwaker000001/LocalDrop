// M04 test support: loopback-only helpers. No Internet, no hardware.
using System.Collections.Concurrent;
using System.Net.Sockets;
using System.Text;
using LocalDrop.Discovery;
using LocalDrop.Network;
using LocalDrop.Protocol;

namespace LocalDrop.Protocol.Tests;

internal static class ControlTestSupport
{
    internal static readonly DeviceInfo DeviceA =
        new("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "PhoneA", "android");
    internal static readonly DeviceInfo DeviceB =
        new("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", "PcB", "windows");
    internal const string Tid = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";

    internal static LocalDropMessage PauseMessage(string transferId) => new(
        MessageType.PAUSE,
        ProtocolCodec.NewMessageId(),
        null, null, transferId,
        new TypedPayload.Pause(new TransferIdOnlyPayload(transferId)));

    internal static LocalDropMessage CancelMessage(string transferId) => new(
        MessageType.CANCEL,
        ProtocolCodec.NewMessageId(),
        null, null, transferId,
        new TypedPayload.Cancel(new CancelPayload(transferId, null)));

    internal static LocalDropMessage ErrorReply(LocalDropMessage to, ErrorCode code, string text) => new(
        MessageType.ERROR,
        ProtocolCodec.NewMessageId(),
        to.MessageId, null, null,
        new TypedPayload.Error(new ErrorPayload(new ProtocolError(code, text), to.MessageId)));

    internal sealed record CloseEvent(CloseReason Reason, string Detail);

    internal sealed class Capture : IControlEvents
    {
        public ConcurrentQueue<LocalDropMessage> Messages { get; } = new();
        public ConcurrentQueue<CloseEvent> Closes { get; } = new();
        public void OnMessage(ControlConnection connection, LocalDropMessage message) =>
            Messages.Enqueue(message);
        public void OnClosed(ControlConnection connection, CloseReason reason, string detail) =>
            Closes.Enqueue(new CloseEvent(reason, detail));

        public async Task<LocalDropMessage> AwaitMessageAsync(int timeoutMs = 5000)
        {
            var deadline = DateTimeOffset.UtcNow.AddMilliseconds(timeoutMs);
            while (DateTimeOffset.UtcNow < deadline)
            {
                if (Messages.TryDequeue(out var m)) return m;
                await Task.Delay(25);
            }
            throw new Xunit.Sdk.XunitException("timed out waiting for message");
        }

        public async Task<CloseEvent> AwaitCloseAsync(int timeoutMs = 5000)
        {
            var deadline = DateTimeOffset.UtcNow.AddMilliseconds(timeoutMs);
            while (DateTimeOffset.UtcNow < deadline)
            {
                if (Closes.TryDequeue(out var c)) return c;
                await Task.Delay(25);
            }
            throw new Xunit.Sdk.XunitException("timed out waiting for close");
        }
    }

    internal static async Task WaitForAsync(string description, Func<bool> condition, int timeoutMs = 8000)
    {
        var deadline = DateTimeOffset.UtcNow.AddMilliseconds(timeoutMs);
        while (DateTimeOffset.UtcNow < deadline)
        {
            if (condition()) return;
            await Task.Delay(50);
        }
        throw new Xunit.Sdk.XunitException($"timed out waiting for: {description}");
    }

    // Reads one M02 frame (prefix + body) from a raw stream. Null on timeout.
    internal static async Task<byte[]?> ReadRawFrameAsync(NetworkStream input, int timeoutMs = 5000)
    {
        input.ReadTimeout = timeoutMs;
        var prefix = new byte[4];
        int got = 0;
        try
        {
            while (got < 4)
            {
                int n = await input.ReadAsync(prefix.AsMemory(got, 4 - got));
                if (n == 0) return null;
                got += n;
            }
            int len = (prefix[0] << 24) | (prefix[1] << 16) | (prefix[2] << 8) | prefix[3];
            if (len <= 0 || len > 2 * 1024 * 1024) return null;
            var body = new byte[len];
            got = 0;
            while (got < len)
            {
                int n = await input.ReadAsync(body.AsMemory(got, len - got));
                if (n == 0) return null;
                got += n;
            }
            return body;
        }
        catch
        {
            return null;
        }
    }

    // Server side of the M04 handshake over a raw socket: reads HELLO,
    // replies HELLO + correlated HELLO_ACK. Returns the client HELLO or null.
    internal static async Task<LocalDropMessage?> RawHandshakeAsync(TcpClient socket, DeviceInfo local)
    {
        var stream = socket.GetStream();
        var helloBytes = await ReadRawFrameAsync(stream);
        if (helloBytes is null) return null;
        if (ProtocolCodec.DecodeUtf8(helloBytes) is not DecodeOutcome.Success ok) return null;
        if (ok.Message.Type != MessageType.HELLO) return null;
        var myHello = new LocalDropMessage(
            MessageType.HELLO,
            ProtocolCodec.NewMessageId(), null, null, null,
            new TypedPayload.Hello(new HelloPayload(
                local.DeviceId, local.DeviceName, local.DeviceType,
                Core.AppConfig.ControlTcpPort, [])));
        await stream.WriteAsync(FrameCodec.EncodeMessage(myHello));
        var ack = new LocalDropMessage(
            MessageType.HELLO_ACK,
            ProtocolCodec.NewMessageId(), ok.Message.MessageId, null, null,
            new TypedPayload.HelloAck(new HelloAckPayload(
                local.DeviceId, local.DeviceName, local.DeviceType, true)));
        await stream.WriteAsync(FrameCodec.EncodeMessage(ack));
        await stream.FlushAsync();
        return ok.Message;
    }
}
