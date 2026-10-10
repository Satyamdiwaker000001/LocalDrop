// Mailbox ordering + per-transfer bound (audit hardening).
using LocalDrop.Discovery;
using LocalDrop.Network;
using LocalDrop.Transfer;

namespace LocalDrop.Protocol.Tests;

public sealed class TransferMailboxTests
{
    private static readonly DeviceInfo ProbeDevice =
        new("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "T", "android");

    private static LocalDropMessage Progress(string tid, long n) => new(
        MessageType.TRANSFER_PROGRESS,
        ProtocolCodec.NewMessageId(),
        null, null, tid,
        new TypedPayload.TransferProgress(new TransferProgressPayload(tid, n, 100L)));

    // Listener-only use: the connection reference is unused by the box.
    private static ControlConnection MockConnection() =>
        new(ProbeDevice, null);

    [Fact]
    public void Await_SkipsNonMatchingTypesPreservingOrder()
    {
        var mailbox = new TransferMailbox();
        const string tid = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";
        var conn = MockConnection();
        mailbox.OnMessage(conn, Progress(tid, 1L));
        mailbox.OnMessage(conn, Progress(tid, 2L));
        // No START ever arrives: timeout, queue intact.
        Assert.Null(mailbox.AwaitResponse(tid,
            new HashSet<MessageType> { MessageType.TRANSFER_START }, 200));
        Assert.Equal(2, mailbox.PendingCount(tid));
        var first = mailbox.AwaitResponse(tid,
            new HashSet<MessageType> { MessageType.TRANSFER_PROGRESS }, 200);
        Assert.NotNull(first);
        Assert.Equal(1L, Assert.IsType<TypedPayload.TransferProgress>(first!.Payload).V.TransferredBytes);
        Assert.Equal(1, mailbox.PendingCount(tid));
    }

    [Fact]
    public void PerTransferQueue_IsBoundedKeepingNewest()
    {
        var mailbox = new TransferMailbox();
        const string tid = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";
        var conn = MockConnection();
        for (int i = 0; i < 200; i++) mailbox.OnMessage(conn, Progress(tid, i));
        Assert.Equal(TransferMailbox.MaxQueuedPerTransfer, mailbox.PendingCount(tid));
        var all = mailbox.Drain(tid);
        Assert.Equal(TransferMailbox.MaxQueuedPerTransfer, all.Count);
        Assert.Equal(199L,
            Assert.IsType<TypedPayload.TransferProgress>(all[^1].Payload).V.TransferredBytes);
        var values = all
            .Select(m => Assert.IsType<TypedPayload.TransferProgress>(m.Payload).V.TransferredBytes)
            .ToList();
        Assert.True(values.Zip(values.Skip(1), (a, b) => a < b).All(x => x));
    }
}
