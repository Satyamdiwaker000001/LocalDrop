// M05 tests 5-7, 9-14, 19 (+UI model checks): transaction lifecycle with a
// fake clock. No sockets here; channel binding is covered in flow tests.
using LocalDrop.Security;

namespace LocalDrop.Protocol.Tests;

public sealed class TransactionManagerTests
{
    private static TransactionManager ManagerAt(FakeDiscoveryClock clock, long lifetimeMs = 300_000L) =>
        new(clock, lifetimeMs, new RecordingSecurityLogger());

    [Fact]
    public void CreatedCode_IsSixDigits()
    {
        var txns = ManagerAt(new FakeDiscoveryClock());
        for (int i = 0; i < 20; i++)
        {
            var txn = txns.CreateTransaction();
            Assert.Matches(new System.Text.RegularExpressions.Regex(@"^\d{6}$"), txn.Code);
            Assert.Equal(CodeState.ACTIVE, txns.Get(txn.Id)?.State);
        }
    }

    [Fact]
    public void Codes_AreUniqueAcrossSamples()
    {
        var txns = ManagerAt(new FakeDiscoveryClock());
        var codes = new HashSet<string>();
        for (int i = 0; i < 200; i++) codes.Add(txns.CreateTransaction().Code);
        Assert.Equal(200, codes.Count);
    }

    [Fact]
    public void CorrectCode_AuthorizesAndConsumes()
    {
        var txns = ManagerAt(new FakeDiscoveryClock());
        var txn = txns.CreateTransaction();
        var outcome = Assert.IsType<VerifyOutcome.Authorized>(txns.VerifyByCode(txn.Code, "peer-1"));
        Assert.Equal(txn.Id, outcome.TransactionId);
        Assert.Equal(CodeState.CONSUMED, txns.Get(txn.Id)?.State);
        Assert.Equal("peer-1", txns.Get(txn.Id)?.AuthorizedPeerDeviceId);
    }

    [Fact]
    public void WrongCode_RejectedWithoutConsumingCorrectCode()
    {
        var txns = ManagerAt(new FakeDiscoveryClock());
        var txn = txns.CreateTransaction();
        string wrong = txn.Code != "000000" ? "000000" : "111111";
        var rejected = Assert.IsType<VerifyOutcome.Rejected>(txns.VerifyByCode(wrong, "peer-1"));
        Assert.Equal(CodeReject.UNKNOWN_CODE, rejected.Reason);
        // Correct code still works afterwards.
        Assert.IsType<VerifyOutcome.Authorized>(txns.VerifyByCode(txn.Code, "peer-1"));
    }

    [Fact]
    public void ExpiredCode_RejectedAndNeverRefreshed()
    {
        var clock = new FakeDiscoveryClock();
        var txns = ManagerAt(clock);
        var txn = txns.CreateTransaction();
        clock.Advance(300_001L); // past the 5-minute lifetime
        var outcome = Assert.IsType<VerifyOutcome.Rejected>(txns.VerifyByCode(txn.Code, "peer-1"));
        Assert.Equal(CodeReject.EXPIRED, outcome.Reason);
        Assert.Equal(CodeState.EXPIRED, txns.Get(txn.Id)?.State);
        clock.Advance(60_000L);
        Assert.IsType<VerifyOutcome.Rejected>(txns.VerifyByCode(txn.Code, "peer-1"));
    }

    [Fact]
    public void ConsumedCode_RejectsReplays()
    {
        var txns = ManagerAt(new FakeDiscoveryClock());
        var txn = txns.CreateTransaction();
        Assert.IsType<VerifyOutcome.Authorized>(txns.VerifyByCode(txn.Code, "peer-1"));
        var replay = Assert.IsType<VerifyOutcome.Rejected>(txns.VerifyByCode(txn.Code, "peer-1"));
        Assert.Equal(CodeReject.CONSUMED, replay.Reason);
    }

    [Fact]
    public void CancelledTransaction_Rejects()
    {
        var txns = ManagerAt(new FakeDiscoveryClock());
        var txn = txns.CreateTransaction();
        Assert.True(txns.Cancel(txn.Id));
        Assert.Equal(CodeState.CANCELLED, txns.Get(txn.Id)?.State);
        var outcome = Assert.IsType<VerifyOutcome.Rejected>(txns.VerifyByCode(txn.Code, "peer-1"));
        Assert.Equal(CodeReject.CANCELLED, outcome.Reason);
        Assert.False(txns.Cancel("no-such-id"));
    }

    [Fact]
    public void SimultaneousTransactions_StayIsolated()
    {
        var txns = ManagerAt(new FakeDiscoveryClock());
        var a = txns.CreateTransaction();
        var b = txns.CreateTransaction();
        // Code A authorizes only transaction A.
        var outcomeA = Assert.IsType<VerifyOutcome.Authorized>(txns.VerifyByCode(a.Code, "peer-1"));
        Assert.Equal(a.Id, outcomeA.TransactionId);
        // Code B still authorizes transaction B afterwards.
        var outcomeB = Assert.IsType<VerifyOutcome.Authorized>(txns.VerifyByCode(b.Code, "peer-1"));
        Assert.Equal(b.Id, outcomeB.TransactionId);
    }

    [Fact]
    public void SpacedInput_NormalizesToDigits()
    {
        var txns = ManagerAt(new FakeDiscoveryClock());
        var txn = txns.CreateTransaction();
        string spaced = txn.Code[..3] + " " + txn.Code[3..];
        Assert.IsType<VerifyOutcome.Authorized>(txns.VerifyByCode(spaced, "peer-1"));
    }

    [Fact]
    public void ReceiverUiSnapshots_FollowLifecycle()
    {
        var txns = ManagerAt(new FakeDiscoveryClock());
        var seen = new List<ReceiverTransactionUiState>();
        txns.ReceiverObserver = seen.Add;
        var txn = txns.CreateTransaction();
        string lastFlipped = txn.Code[..^1] + (txn.Code[^1] == '0' ? '1' : '0');
        txns.VerifyByCode(lastFlipped, "peer-1");
        txns.VerifyByCode(txn.Code, "peer-1");
        var phases = seen.Select(s => s.Phase).ToList();
        Assert.Contains(ReceiverPhase.CODE_ACTIVE, phases);
        Assert.Contains(ReceiverPhase.INVALID, phases);
        Assert.Contains(ReceiverPhase.AUTHORIZED, phases);
        var authorized = seen.Last(s => s.Phase == ReceiverPhase.AUTHORIZED);
        Assert.Equal("peer-1", authorized.AuthorizedPeerDeviceId);
        Assert.Equal(AuthUiState.FormatCode(txn.Code), authorized.CodeDisplay);
        Assert.Equal("583 921", AuthUiState.FormatCode("583921"));
        Assert.Equal("583921", AuthUiState.NormalizeInput("583-921"));
    }
}
