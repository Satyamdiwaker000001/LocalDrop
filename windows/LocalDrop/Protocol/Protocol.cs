// M02 protocol foundation: single source of protocol constants.
// Mirrors shared-protocol/ + PROTOCOL v1.0. Wire version is independent of
// the application version. No networking, no I/O, no crypto here.
namespace LocalDrop.Protocol;

public static class Protocol
{
    public const string ProtocolId = "localdrop";
    public const int ProtocolVersion = 1;

    // Control plane carries metadata only (never file bytes).
    // Bounds attacker-controlled allocation: a declared frame larger than
    // this is rejected WITHOUT allocating. Protocol surface: changing this
    // is a versioned decision (shared-protocol/framing-resume-integrity.md).
    public const int MaxFrameBytes = 1024 * 1024;

    // Logical checkpoint default for resume/progress accounting
    // (Master Spec §7). Not a TCP packet size.
    public const long DefaultCheckpointBytes = 8L * 1024L * 1024L;

    // Field bounds (shared-protocol/messages.md, transfer-metadata.md).
    public const int MaxDeviceName = 128;
    public const int MaxCapabilities = 32;
    public const int MaxCapabilityLen = 64;
    public const int MaxFileName = 255;
    public const int MaxFileIdLen = 128;
    public const int MaxDisplayCode = 16;
    public const int MaxAuthMethodLen = 64;
    public const int MaxAuthTokenLen = 4096;
    public const int MaxReasonLen = 256;
    public const int MaxErrorMessageLen = 512;
    public const long MaxCheckpointSize = 1024L * 1024L * 1024L;
}
