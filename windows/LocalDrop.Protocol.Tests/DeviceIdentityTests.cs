// M05 tests 1-2: device identity creation + persistence/reload, on both the
// software store and the real CNG persisted store (temp key names).
using LocalDrop.Security;

namespace LocalDrop.Protocol.Tests;

public sealed class DeviceIdentityTests
{
    private static readonly System.Text.RegularExpressions.Regex UuidV4 =
        new("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");

    [Fact]
    public void IdentityCreation_YieldsBoundUuidAndSpki()
    {
        using var store = new SoftwareDeviceIdentityStore();
        var id = store.Identity();
        Assert.Matches(UuidV4, id.DeviceId);
        Assert.NotEmpty(id.PublicKeySpki);
        Assert.True(Guid.TryParse(id.DeviceId, out _));
    }

    [Fact]
    public void Identity_IsStableAcrossReloadsFromSameStore()
    {
        using var store = new SoftwareDeviceIdentityStore();
        Assert.Equal(store.Identity(), store.Identity());
    }

    [Fact]
    public void DistinctInstallations_GetDistinctIdentities()
    {
        using var a = new SoftwareDeviceIdentityStore();
        using var b = new SoftwareDeviceIdentityStore();
        Assert.NotEqual(a.Identity().DeviceId, b.Identity().DeviceId);
        Assert.False(a.Identity().PublicKeySpki.AsSpan().SequenceEqual(b.Identity().PublicKeySpki));
    }

    [Fact]
    public void DeviceIdDerivation_IsDeterministicPerKey()
    {
        using var store = new SoftwareDeviceIdentityStore();
        byte[] spki = store.Identity().PublicKeySpki;
        Assert.Equal(
            DeviceIdentityCrypto.DeriveDeviceId(spki),
            DeviceIdentityCrypto.DeriveDeviceId(spki));
        Assert.Equal(store.Identity().DeviceId, DeviceIdentityCrypto.DeriveDeviceId(spki));
    }

    [Fact]
    public void SignVerify_RoundTripSucceeds_TamperedDataFails()
    {
        using var store = new SoftwareDeviceIdentityStore();
        byte[] data = System.Text.Encoding.UTF8.GetBytes("transcript-bytes");
        byte[] sig = store.Sign(data);
        Assert.Equal(64, sig.Length);
        Assert.True(store.Verify(data, sig));
        byte[] tampered = (byte[])data.Clone();
        tampered[0] ^= 0xFF;
        Assert.False(store.Verify(tampered, sig));
    }

    [Fact]
    public void WrongKey_CannotVerify_FlippedSignatureFails()
    {
        using var a = new SoftwareDeviceIdentityStore();
        using var b = new SoftwareDeviceIdentityStore();
        byte[] data = System.Text.Encoding.UTF8.GetBytes("other");
        byte[] sigFromA = a.Sign(data);
        // A's signature does NOT verify under B's key.
        Assert.False(b.Verify(data, sigFromA, b.Identity().PublicKeySpki));
        byte[] flipped = (byte[])sigFromA.Clone();
        flipped[63] ^= 0x01;
        Assert.False(a.Verify(data, flipped));
    }

    [Fact]
    public void CngStore_PersistsIdentityAcrossReopen_ThenDeletes()
    {
        string alias = "LocalDropTest-" + Guid.NewGuid().ToString("D");
        try
        {
            string first, second;
            byte[] spki;
            using (var store = new CngDeviceIdentityStore(alias))
            {
                first = store.Identity().DeviceId;
                spki = store.Identity().PublicKeySpki;
                Assert.Matches(UuidV4, first);
            }
            // Reopen the same persisted key: same identity, still bound.
            using (var reopened = new CngDeviceIdentityStore(alias))
            {
                second = reopened.Identity().DeviceId;
                Assert.Equal(first, second);
                Assert.Equal(first, DeviceIdentityCrypto.DeriveDeviceId(reopened.Identity().PublicKeySpki));
                Assert.True(reopened.Identity().PublicKeySpki.AsSpan().SequenceEqual(spki));
            }
        }
        finally
        {
            CngDeviceIdentityStore.Delete(alias);
        }
    }

    [Fact]
    public void CngStore_SignsVerifiably()
    {
        string alias = "LocalDropTest-" + Guid.NewGuid().ToString("D");
        try
        {
            using var store = new CngDeviceIdentityStore(alias);
            byte[] data = System.Text.Encoding.UTF8.GetBytes("cng-transcript");
            byte[] sig = store.Sign(data);
            Assert.Equal(64, sig.Length);
            Assert.True(DeviceIdentityCrypto.VerifySignature(data, sig, store.Identity().PublicKeySpki));
        }
        finally
        {
            CngDeviceIdentityStore.Delete(alias);
        }
    }
}
