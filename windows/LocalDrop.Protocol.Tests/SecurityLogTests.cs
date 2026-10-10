// M05 tests 11 (wrong-code path logging), 21: no secret material in logs.
// Runs a full success flow plus a wrong-code attempt, then scans every
// recorded line for the known secrets (code, display form, both SPKI blobs)
// and for any long base64 blob (nonce/signature shape).
using System.Text.RegularExpressions;
using LocalDrop.Network;
using LocalDrop.Security;

namespace LocalDrop.Protocol.Tests;

public sealed class SecurityLogTests
{
    [Fact]
    public async Task NoSecretMaterial_AppearsInSecurityLogs()
    {
        using var receiverStore = new SoftwareDeviceIdentityStore();
        using var senderStore = new SoftwareDeviceIdentityStore();
        var receiverDevice = AuthTestSupport.DeviceFor(receiverStore, "PcB");
        var senderDevice = AuthTestSupport.DeviceFor(senderStore, "PhoneA");
        var receiverLog = new RecordingSecurityLogger();
        var senderLog = new RecordingSecurityLogger();
        var receiverAuth = new AuthManager(receiverDevice, receiverStore, receiverLog);
        var senderAuth = new AuthManager(senderDevice, senderStore, senderLog);
        var txns = new TransactionManager(new FakeDiscoveryClock(), 300_000L, receiverLog);
        var sessions = new AuthorizedSessionStore();
        receiverAuth.Inner = new TransactionAuthorizer(
            receiverAuth, txns, sessions, receiverLog, receiverDevice.DeviceId);
        var server = new ControlServer(receiverDevice, port: 0, listenerFactory: () => receiverAuth);
        server.Start();
        var secrets = new List<string>();
        try
        {
            var client = new ControlConnection(senderDevice, senderAuth);
            Assert.True(await client.ConnectAsync("127.0.0.1", server.LocalPort));
            try
            {
                var outcome = await AuthTestSupport.AwaitOutcomeAsync(senderAuth.BeginAuthAsync(client));
                Assert.IsType<AuthOutcome.Authenticated>(outcome);
                var txn = txns.CreateTransaction();
                secrets.Add(txn.Code);
                secrets.Add(AuthUiState.FormatCode(txn.Code));
                secrets.Add(AuthProtocol.B64Encode(receiverStore.Identity().PublicKeySpki));
                secrets.Add(AuthProtocol.B64Encode(senderStore.Identity().PublicKeySpki));
                // A wrong attempt first (exercises the reject log path).
                string wrong = txn.Code[..^1] + (txn.Code[^1] == '0' ? '1' : '0');
                Assert.IsType<PairSubmitOutcome.Rejected>(
                    await senderAuth.SenderSubmitCodeAsync(client, wrong));
                Assert.IsType<PairSubmitOutcome.Accepted>(
                    await senderAuth.SenderSubmitCodeAsync(client, txn.Code));
            }
            finally
            {
                await client.DisconnectAsync();
            }
        }
        finally
        {
            await server.StopAsync();
        }
        var lines = receiverLog.Snapshot().Concat(senderLog.Snapshot()).ToList();
        Assert.NotEmpty(lines);
        var blob = new Regex("[A-Za-z0-9+/]{40,}={0,2}");
        foreach (string line in lines)
        {
            foreach (string secret in secrets)
                Assert.DoesNotContain(secret, line);
            Assert.DoesNotMatch(blob, line);
        }
        // Positive control: logs DO carry ids + event words.
        string joined = string.Join("\n", lines);
        Assert.Contains(receiverDevice.DeviceId, joined);
        Assert.Contains(senderDevice.DeviceId, joined);
        Assert.True(joined.Contains("rejected") || joined.Contains("authorized"));
    }

    [Fact]
    public void PeerFacingRejectReason_IsGeneric()
    {
        Assert.Equal("Invalid transfer code.", TransactionAuthorizer.GenericRejectReason);
        Assert.DoesNotContain("583921", TransactionAuthorizer.GenericRejectReason);
    }
}
