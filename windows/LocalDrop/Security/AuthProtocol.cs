// M05: authentication wire details (shared-protocol/authentication.md §§2-4).
// Token JSON lives inside the opaque M02 AUTH_REQUEST.token string; the
// transcript is the exact byte string both sides sign. No new message types.
using LocalDrop.Core;
using System.IO;

namespace LocalDrop.Security;

public static class AuthProtocol
{
    public const string StepHello = "auth-hello";
    public const string StepProof = "auth-proof";

    public static byte[] NewNonce()
    {
        byte[] nonce = new byte[AppConfig.AuthNonceBytes];
        System.Security.Cryptography.RandomNumberGenerator.Fill(nonce);
        return nonce;
    }

    public static string B64Encode(byte[] raw) => Convert.ToBase64String(raw);

    public static byte[]? B64Decode(string text)
    {
        try
        {
            return Convert.FromBase64String(text);
        }
        catch (FormatException)
        {
            return null;
        }
    }

    // Canonical transcript both sides sign (ids sorted so ordering is
    // direction-independent):
    //   "LocalDrop-Auth-v1" 0x00 method 0x00 idLo 0x00 idHi 0x00 nonceLo 0x00 nonceHi
    public static byte[] BuildTranscript(
        string method,
        string idA, byte[] nonceA,
        string idB, byte[] nonceB)
    {
        string lo, hi;
        byte[] loNonce, hiNonce;
        if (string.CompareOrdinal(idA, idB) <= 0)
        {
            lo = idA; loNonce = nonceA; hi = idB; hiNonce = nonceB;
        }
        else
        {
            lo = idB; loNonce = nonceB; hi = idA; hiNonce = nonceA;
        }
        using var stream = new MemoryStream();
        void Field(byte[] bytes)
        {
            stream.Write(bytes);
            stream.WriteByte(0);
        }
        Field(System.Text.Encoding.UTF8.GetBytes("LocalDrop-Auth-v1"));
        Field(System.Text.Encoding.UTF8.GetBytes(method));
        Field(System.Text.Encoding.UTF8.GetBytes(lo));
        Field(System.Text.Encoding.UTF8.GetBytes(hi));
        Field(loNonce);
        stream.Write(hiNonce); // no trailing separator
        return stream.ToArray();
    }

    public static string HelloToken(string deviceId, byte[] publicKeySpki, byte[] nonce) =>
        Protocol.JsonCanonical.Serialize(new Protocol.JsonValue.Obj(
        [
            new("step", new Protocol.JsonValue.Str(StepHello)),
            new("deviceId", new Protocol.JsonValue.Str(deviceId)),
            new("publicKey", new Protocol.JsonValue.Str(B64Encode(publicKeySpki))),
            new("nonce", new Protocol.JsonValue.Str(B64Encode(nonce))),
        ]));

    public static string ProofToken(byte[] rawSignature) =>
        Protocol.JsonCanonical.Serialize(new Protocol.JsonValue.Obj(
        [
            new("step", new Protocol.JsonValue.Str(StepProof)),
            new("signature", new Protocol.JsonValue.Str(B64Encode(rawSignature))),
        ]));

    public sealed record ParsedHello(string DeviceId, byte[] PublicKeySpki, byte[] Nonce);
    public sealed record ParsedProof(byte[] RawSignature);

    public static Protocol.PayloadResult<ParsedHello> ParseHello(string token)
    {
        var map = ParseTokenObject(token);
        if (map is null) return new Protocol.PayloadResult<ParsedHello>.Fail("auth token: not an object");
        var r = new Protocol.FieldReader(map, "auth-hello");
        if (r.GetString("step", 1, 32) != StepHello)
            return new Protocol.PayloadResult<ParsedHello>.Fail("auth-hello: wrong step");
        var deviceId = r.GetUuid("deviceId");
        var publicKeyB64 = r.GetString("publicKey", 1, 1024);
        var nonceB64 = r.GetString("nonce", 1, 128);
        var err = r.Error();
        if (err is not null || deviceId is null || publicKeyB64 is null || nonceB64 is null)
            return new Protocol.PayloadResult<ParsedHello>.Fail(err ?? "auth-hello: invalid token");
        var spki = B64Decode(publicKeyB64);
        if (spki is null)
            return new Protocol.PayloadResult<ParsedHello>.Fail("auth-hello: bad publicKey encoding");
        var nonce = B64Decode(nonceB64);
        if (nonce is null)
            return new Protocol.PayloadResult<ParsedHello>.Fail("auth-hello: bad nonce encoding");
        if (nonce.Length != AppConfig.AuthNonceBytes)
            return new Protocol.PayloadResult<ParsedHello>.Fail("auth-hello: bad nonce length");
        return new Protocol.PayloadResult<ParsedHello>.Ok(new ParsedHello(deviceId, spki, nonce));
    }

    public static Protocol.PayloadResult<ParsedProof> ParseProof(string token)
    {
        var map = ParseTokenObject(token);
        if (map is null) return new Protocol.PayloadResult<ParsedProof>.Fail("auth token: not an object");
        var r = new Protocol.FieldReader(map, "auth-proof");
        if (r.GetString("step", 1, 32) != StepProof)
            return new Protocol.PayloadResult<ParsedProof>.Fail("auth-proof: wrong step");
        var sigB64 = r.GetString("signature", 1, 256);
        var err = r.Error();
        if (err is not null || sigB64 is null)
            return new Protocol.PayloadResult<ParsedProof>.Fail(err ?? "auth-proof: invalid token");
        var sig = B64Decode(sigB64);
        if (sig is null)
            return new Protocol.PayloadResult<ParsedProof>.Fail("auth-proof: bad signature encoding");
        if (sig.Length != 64)
            return new Protocol.PayloadResult<ParsedProof>.Fail("auth-proof: bad signature length");
        return new Protocol.PayloadResult<ParsedProof>.Ok(new ParsedProof(sig));
    }

    private static IReadOnlyList<KeyValuePair<string, Protocol.JsonValue>>? ParseTokenObject(string token)
    {
        if (token.Length > 4096) return null;
        return Protocol.JsonCanonical.Parse(token) is Protocol.JsonParseResult.Ok ok &&
            ok.Value is Protocol.JsonValue.Obj obj
            ? obj.Entries
            : null;
    }
}
