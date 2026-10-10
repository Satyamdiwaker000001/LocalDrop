// M05: cryptographic device identity (shared-protocol/authentication.md §1).
// One P-256 keypair per installation; the M02 deviceId UUID is derived from
// SHA-256(SPKI) with version/variant bits set, so the id is cryptographically
// bound to the key. Standard platform primitives only (CNG ECDSA, SHA-256):
// NO custom cryptography. Wire forms: SPKI DER (public key) and 64-byte raw
// R||S (signatures).
using System.Security.Cryptography;
using System.Text;

namespace LocalDrop.Security;

// Bound identity handle. The private key itself is never exposed here;
// signing happens inside the store that owns the key material.
public sealed record DeviceIdentity(string DeviceId, byte[] PublicKeySpki)
{
    public bool Equals(DeviceIdentity? other) =>
        other is not null && DeviceId == other.DeviceId &&
        PublicKeySpki.AsSpan().SequenceEqual(other.PublicKeySpki);

    public override int GetHashCode() => HashCode.Combine(DeviceId, PublicKeySpki.Length);
}

public interface IDeviceIdentityStore
{
    // Load-or-create the installation identity. The same identity (same
    // deviceId + key) must be returned across restarts (test 2).
    DeviceIdentity Identity();

    // ECDSA/SHA-256 sign with the installation private key.
    // Returns the 64-byte raw R||S signature.
    byte[] Sign(byte[] data);
}

public static class DeviceIdentityCrypto
{
    // deviceId = UUID(SHA-256(SPKI)[0..16], version=4, variant=10xx),
    // formatted big-endian to match the Android derivation exactly.
    public static string DeriveDeviceId(byte[] publicKeySpki)
    {
        byte[] hash = SHA256.HashData(publicKeySpki);
        byte[] id = hash[..16];
        id[6] = (byte)((id[6] & 0x0F) | 0x40);
        id[8] = (byte)((id[8] & 0x3F) | 0x80);
        var sb = new StringBuilder(36);
        static void Hex(StringBuilder sb, byte[] id, int from, int to)
        {
            for (int i = from; i < to; i++) sb.Append(id[i].ToString("x2"));
        }
        Hex(sb, id, 0, 4); sb.Append('-');
        Hex(sb, id, 4, 6); sb.Append('-');
        Hex(sb, id, 6, 8); sb.Append('-');
        Hex(sb, id, 8, 10); sb.Append('-');
        Hex(sb, id, 10, 16);
        return sb.ToString();
    }

    public static bool VerifySignature(byte[] transcript, byte[] rawSignature, byte[] spki)
    {
        try
        {
            using var ecdsa = ECDsa.Create();
            ecdsa.ImportSubjectPublicKeyInfo(spki, out _);
            return ecdsa.VerifyData(
                transcript, rawSignature,
                HashAlgorithmName.SHA256,
                DSASignatureFormat.IeeeP1363FixedFieldConcatenation);
        }
        catch
        {
            return false;
        }
    }
}

// Testable software store: in-memory P-256 key. Used by unit tests with the
// identical curve/hash/encodings as production.
public sealed class SoftwareDeviceIdentityStore : IDeviceIdentityStore, IDisposable
{
    private readonly ECDsa _ecdsa = ECDsa.Create(ECCurve.NamedCurves.nistP256);
    private readonly DeviceIdentity _identity;
    private bool _disposed;

    public SoftwareDeviceIdentityStore()
    {
        byte[] spki = _ecdsa.ExportSubjectPublicKeyInfo();
        _identity = new DeviceIdentity(DeviceIdentityCrypto.DeriveDeviceId(spki), spki);
    }

    public DeviceIdentity Identity() => _identity;

    public byte[] Sign(byte[] data) =>
        _ecdsa.SignData(data, HashAlgorithmName.SHA256,
            DSASignatureFormat.IeeeP1363FixedFieldConcatenation);

    public bool Verify(byte[] data, byte[] rawSignature, byte[]? spki = null) =>
        DeviceIdentityCrypto.VerifySignature(data, rawSignature, spki ?? _identity.PublicKeySpki);

    public void Dispose()
    {
        if (!_disposed)
        {
            _disposed = true;
            _ecdsa.Dispose();
        }
    }
}

// Production store: private key in a persisted CNG user key (non-exportable,
// never logged/transmitted). Open-or-create by name; the identity (deviceId)
// is recomputed deterministically from the public key on every load.
public sealed class CngDeviceIdentityStore : IDeviceIdentityStore, IDisposable
{
    private readonly string _keyName;
    private readonly CngKey _key;
    private readonly ECDsaCng _ecdsa;
    private readonly DeviceIdentity _identity;
    private bool _disposed;

    public CngDeviceIdentityStore(string keyName)
    {
        _keyName = keyName;
        CngKey? key = null;
        try
        {
            key = CngKey.Open(keyName);
        }
        catch (CryptographicException)
        {
            // Not present: create a fresh persisted P-256 signing key.
        }
        key ??= CngKey.Create(
            CngAlgorithm.ECDsaP256,
            keyName,
            new CngKeyCreationParameters
            {
                ExportPolicy = CngExportPolicies.None,
            });
        _key = key;
        _ecdsa = new ECDsaCng(key);
        byte[] spki = _ecdsa.ExportSubjectPublicKeyInfo();
        _identity = new DeviceIdentity(DeviceIdentityCrypto.DeriveDeviceId(spki), spki);
    }

    public DeviceIdentity Identity() => _identity;

    public byte[] Sign(byte[] data) =>
        _ecdsa.SignData(data, HashAlgorithmName.SHA256,
            DSASignatureFormat.IeeeP1363FixedFieldConcatenation);

    public void Dispose()
    {
        if (!_disposed)
        {
            _disposed = true;
            _ecdsa.Dispose();
            _key.Dispose();
        }
    }

    public static void Delete(string keyName)
    {
        try
        {
            using var key = CngKey.Open(keyName);
            key.Delete();
        }
        catch (CryptographicException)
        {
            // Already absent: nothing to do.
        }
    }
}
