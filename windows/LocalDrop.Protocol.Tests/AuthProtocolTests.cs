// M05: token structure + transcript contract (shared-protocol/authentication.md).
using LocalDrop.Security;

namespace LocalDrop.Protocol.Tests;

public sealed class AuthProtocolTests
{
    [Fact]
    public void HelloToken_RoundTrips()
    {
        using var store = new SoftwareDeviceIdentityStore();
        var id = store.Identity();
        byte[] nonce = AuthProtocol.NewNonce();
        Assert.Equal(32, nonce.Length);
        string token = AuthProtocol.HelloToken(id.DeviceId, id.PublicKeySpki, nonce);
        var parsed = Assert.IsType<PayloadResult<AuthProtocol.ParsedHello>.Ok>(
            AuthProtocol.ParseHello(token));
        Assert.Equal(id.DeviceId, parsed.Value.DeviceId);
        Assert.True(parsed.Value.PublicKeySpki.AsSpan().SequenceEqual(id.PublicKeySpki));
        Assert.True(parsed.Value.Nonce.AsSpan().SequenceEqual(nonce));
    }

    [Fact]
    public void ProofToken_RoundTrips()
    {
        using var store = new SoftwareDeviceIdentityStore();
        byte[] sig = store.Sign(System.Text.Encoding.UTF8.GetBytes("data"));
        var parsed = Assert.IsType<PayloadResult<AuthProtocol.ParsedProof>.Ok>(
            AuthProtocol.ParseProof(AuthProtocol.ProofToken(sig)));
        Assert.True(parsed.Value.RawSignature.AsSpan().SequenceEqual(sig));
    }

    [Fact]
    public void MalformedTokens_AreRejected()
    {
        using var store = new SoftwareDeviceIdentityStore();
        var id = store.Identity();
        Assert.IsType<PayloadResult<AuthProtocol.ParsedHello>.Fail>(AuthProtocol.ParseHello("{not json"));
        Assert.IsType<PayloadResult<AuthProtocol.ParsedHello>.Fail>(AuthProtocol.ParseHello("{}"));
        Assert.IsType<PayloadResult<AuthProtocol.ParsedProof>.Fail>(AuthProtocol.ParseProof("{}"));
        // Wrong step discriminator.
        string swapped = AuthProtocol.ProofToken(new byte[64]);
        Assert.IsType<PayloadResult<AuthProtocol.ParsedHello>.Fail>(AuthProtocol.ParseHello(swapped));
        // Bad base64.
        string badB64 = "{\"step\":\"auth-hello\",\"deviceId\":\"" + id.DeviceId + "\"," +
            "\"publicKey\":\"!!!\",\"nonce\":\"!!!\"}";
        Assert.IsType<PayloadResult<AuthProtocol.ParsedHello>.Fail>(AuthProtocol.ParseHello(badB64));
        // Short nonce / signature / oversized token.
        string shortNonce = AuthProtocol.HelloToken(id.DeviceId, id.PublicKeySpki, new byte[4]);
        Assert.IsType<PayloadResult<AuthProtocol.ParsedHello>.Fail>(AuthProtocol.ParseHello(shortNonce));
        string shortSig = AuthProtocol.ProofToken(new byte[10]);
        Assert.IsType<PayloadResult<AuthProtocol.ParsedProof>.Fail>(AuthProtocol.ParseProof(shortSig));
        Assert.IsType<PayloadResult<AuthProtocol.ParsedHello>.Fail>(
            AuthProtocol.ParseHello(new string('x', 5000)));
    }

    [Fact]
    public void Transcript_IsDeterministicAndOrderIndependent()
    {
        using var a = new SoftwareDeviceIdentityStore();
        using var b = new SoftwareDeviceIdentityStore();
        string idA = a.Identity().DeviceId;
        string idB = b.Identity().DeviceId;
        byte[] nA = Enumerable.Repeat((byte)0x11, 32).ToArray();
        byte[] nB = Enumerable.Repeat((byte)0x22, 32).ToArray();
        byte[] t1 = AuthProtocol.BuildTranscript("m", idA, nA, idB, nB);
        byte[] t2 = AuthProtocol.BuildTranscript("m", idA, nA, idB, nB);
        byte[] t3 = AuthProtocol.BuildTranscript("m", idB, nB, idA, nA);
        Assert.True(t1.AsSpan().SequenceEqual(t2));
        Assert.True(t1.AsSpan().SequenceEqual(t3));
        Assert.False(t1.AsSpan().SequenceEqual(AuthProtocol.BuildTranscript("other", idA, nA, idB, nB)));
        Assert.False(t1.AsSpan().SequenceEqual(AuthProtocol.BuildTranscript("m", idA, nB, idB, nA)));
    }
}
