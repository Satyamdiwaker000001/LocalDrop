// M04 §23: cross-platform interop tests, gated on LOCALDROP_INTEROP=1.
// Without the variable (normal `dotnet test`) they pass vacuously; the
// interop script sets it and pairs each test with the Java driver running
// the PRODUCTION Android classes (same M02 bytes both directions).
using System.Net;
using LocalDrop.Discovery;
using LocalDrop.Network;

namespace LocalDrop.Protocol.Tests;

public sealed class InteropTests
{
    private const string JavaDriverId = "dddddddd-dddd-4ddd-8ddd-dddddddddddd";
    private const string CSharpServerId = "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee";
    private const string CSharpClientId = "ffffffff-ffff-4fff-8fff-ffffffffffff";
    private const int InteropPort = 48501;
    private const string Tid = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";

    private static bool InteropEnabled() =>
        Environment.GetEnvironmentVariable("LOCALDROP_INTEROP") == "1";

    // Java driver (production Android ControlConnection) dials this listener.
    [Fact]
    public async Task CSharpListener_JavaDialer()
    {
        if (!InteropEnabled()) return;
        var serverDevice = new DeviceInfo(CSharpServerId, "CSharpServer", "windows");
        var capture = new ControlTestSupport.Capture();
        var reply = new ReplyEvents(capture);
        var server = new ControlServer(serverDevice, port: InteropPort, listenerFactory: () => reply);
        server.Start();
        try
        {
            // The Java driver connects, handshakes, sends PAUSE, awaits the
            // correlated ERROR, then disconnects. The session can be fast,
            // so assert on monotonic event flags (never on a transient
            // snapshot: a sub-poll-interval session must still count).
            var deadline = DateTimeOffset.UtcNow.AddSeconds(60);
            while (DateTimeOffset.UtcNow < deadline)
            {
                if (reply.SawPauseFromJavaDriver && reply.ReplySent && reply.CloseSeen) break;
                await Task.Delay(100);
            }
            Assert.True(reply.SawPauseFromJavaDriver, "never received PAUSE from the Java driver");
            Assert.Equal(JavaDriverId, reply.PausePeerId);
            Assert.True(reply.ReplySent, "correlated ERROR reply was never sent");
            Assert.True(reply.CloseSeen, "driver departure was never observed");
        }
        finally
        {
            await server.StopAsync();
        }
    }

    // This client dials the Java driver (production Android ControlServer).
    [Fact]
    public async Task JavaListener_CSharpDialer()
    {
        if (!InteropEnabled()) return;
        var clientDevice = new DeviceInfo(CSharpClientId, "CSharpClient", "windows");
        var events = new ControlTestSupport.Capture();
        var client = new ControlConnection(clientDevice, events);
        // The Java server may still be binding; retry the dial briefly.
        bool ready = false;
        var deadline = DateTimeOffset.UtcNow.AddSeconds(25);
        while (DateTimeOffset.UtcNow < deadline && !ready)
        {
            ready = await client.ConnectAsync("127.0.0.1", InteropPort);
            if (!ready) await Task.Delay(500);
        }
        Assert.True(ready);
        try
        {
            Assert.Equal("dddddddd-dddd-4ddd-8ddd-dddddddddddd", client.PeerDeviceId);
            var request = ControlTestSupport.PauseMessage(Tid);
            var response = await client.SendAndReceiveAsync(request, 15000);
            Assert.NotNull(response);
            Assert.Equal(MessageType.ERROR, response!.Type);
            Assert.Equal(request.MessageId, response.CorrelationId);
            Assert.Equal(ErrorCode.TRANSFER_REJECTED,
                Assert.IsType<TypedPayload.Error>(response.Payload).V.Error.Code);
        }
        finally
        {
            await client.DisconnectAsync();
        }
    }

    // Replies to the first PAUSE with a correlated ERROR, then lets the
    // capture record everything for assertions. Monotonic flags make the
    // test immune to session lifetimes shorter than the poll interval.
    private sealed class ReplyEvents(ControlTestSupport.Capture capture) : IControlEvents
    {
        public volatile bool SawPauseFromJavaDriver;
        public volatile string? PausePeerId;
        public volatile bool ReplySent;
        public volatile bool CloseSeen;

        public void OnMessage(ControlConnection connection, LocalDropMessage message)
        {
            capture.OnMessage(connection, message);
            if (message.Type == MessageType.PAUSE)
            {
                PausePeerId = connection.PeerDeviceId;
                SawPauseFromJavaDriver = true;
                var reply = ControlTestSupport.ErrorReply(message, ErrorCode.TRANSFER_REJECTED, "nope");
                try
                {
                    if (connection.SendAsync(reply).GetAwaiter().GetResult()) ReplySent = true;
                }
                catch
                {
                    // Best effort in test harness.
                }
            }
        }

        public void OnClosed(ControlConnection connection, CloseReason reason, string detail)
        {
            capture.OnClosed(connection, reason, detail);
            CloseSeen = true;
        }
    }
}
