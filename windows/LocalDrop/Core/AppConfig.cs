namespace LocalDrop.Core;

// M01 Foundation: centralized configuration boundary.
// Port values are intentionally TBD (Complete Spec 5.5). M01 MUST NOT
// invent or scatter magic port numbers.
public static class AppConfig
{
    public const string Protocol = "localdrop";
    public const int ProtocolVersion = 1;

    // Logical checkpoint unit for resume/progress accounting (Master Spec §7).
    // 8 MiB default, must remain configurable. Not a TCP packet size.
    public const long DefaultCheckpointBytes = 8L * 1024L * 1024L;

    // Transfer state vocabulary (UI/UX Spec §13). State machine itself is M02.
    public static readonly string[] TransferStates =
    [
        "available",
        "connecting",
        "waiting acceptance",
        "transferring",
        "paused",
        "reconnecting",
        "verifying",
        "completed",
        "failed",
        "cancelled",
    ];

    // M03 LAN discovery (Complete Spec §5.1/§5.5). Centralized here per M03 §8:
    // no timeout/port constants scattered elsewhere. Values are M03-chosen
    // (the frozen spec authorizes UDP broadcast + centralized ports but does
    // not fix numbers); changing them is a versioned protocol decision.
    // UDP broadcast port shared by Android and Windows announcements.
    public const int DiscoveryUdpPort = 48423;
    // Interval between discovery announcements.
    public const long DiscoveryAnnounceIntervalMs = 2000L;
    // A peer unseen for this long is expired/removed (tolerates ~4 lost
    // announcements at the default interval).
    public const long DiscoveryPeerTimeoutMs = 10000L;
    // Upper bound for an inbound discovery datagram. Announcements carry a
    // small HELLO record only; larger datagrams are rejected without parsing.
    public const int DiscoveryMaxPacketBytes = 4096;

    // M04 TCP control channel (Complete Spec §5.2/§5.5, PROTOCOL v1.0 §2).
    // Centralized here per M04 §12: no port/timeout constants scattered
    // elsewhere. Values are M04-chosen (the frozen spec authorizes direct
    // TCP control + centralized ports but does not fix numbers); changing
    // them is a versioned protocol decision.
    // TCP port for the control channel. MUST differ from DiscoveryUdpPort:
    // discovery is UDP, control is TCP; separate transports, separate ports.
    public const int ControlTcpPort = 48424;
    // Upper bound for establishing the TCP connection itself.
    public const int ControlConnectTimeoutMs = 5000;
    // Upper bound for completing the HELLO/HELLO_ACK handshake after connect.
    public const int ControlHandshakeTimeoutMs = 5000;

    // M05 security (shared-protocol/authentication.md). Centralized here per
    // the M05 prompt: future policy changes must not rewrite auth logic.
    // Authentication method id carried in AUTH_REQUEST.method (exact match).
    public const string AuthMethodId = "localdrop-ecdsa-p256-v1";
    // Fresh nonce size per side per handshake (platform CSPRNG).
    public const int AuthNonceBytes = 32;
    // Overall bound for the mutual-auth handshake on a control connection.
    public const int AuthTimeoutMs = 10000;
    // Receiver transaction code lifetime (5 minutes, short-lived by design).
    public const long TransactionCodeLifetimeMs = 300000L;
    // Transaction codes are exactly this many decimal digits (%06d-equivalent).
    public const int TransactionCodeDigits = 6;
    // Windows CNG persisted-key name for the per-installation device identity.
    // Private key material is non-exportable from the OS key store.
    public const string DeviceIdentityKeyName = "LocalDropDeviceIdentity";

    // M06 streaming transfer (docs/architecture/m06-transfer-decisions.md).
    // Centralized here per the M06 prompt: no ports/sizes/timeouts scattered
    // elsewhere. Block/buffer numbers are INITIAL CONSERVATIVE values for M15
    // benchmark validation, NOT product limits: file sizes are unbounded
    // (64-bit) and throughput is uncapped. Changing them needs no protocol
    // change (they never appear on the wire).
    // Dedicated TCP data port. MUST differ from discovery (UDP 48423) and
    // control (TCP 48424): separate transports, separate ports.
    public const int DataTcpPort = 48425;
    // Upper bound for establishing the data connection.
    public const int DataConnectTimeoutMs = 5000;
    // Application block floor: below this, per-operation overhead dominates.
    public const int TransferBlockMinBytes = 65536;
    // Application block ceiling: caps per-transfer heap + operation latency.
    public const int TransferBlockMaxBytes = 1048576;
    // Initial block cap for file-size-aware startup (see AdaptiveBlockSizer).
    public const int TransferBlockInitialBytes = 262144;
    // Longest a single block operation may take before counting as a stall.
    public const long AdaptStallThresholdMs = 500L;
    // Quiet period after any adaptation before the next may occur.
    public const long AdaptCooldownMs = 2000L;
    // Consecutive agreeing windows required before growing/shrinking.
    public const int AdaptConsecutiveWindows = 3;
    // Window throughput reaching this multiple of the best baseline re-arms
    // growth probing after a shrink (genuine improvement required).
    public const double AdaptGrowthRatio = 1.2;
    // Window throughput below this multiple of baseline counts toward shrink.
    public const double AdaptShrinkRatio = 0.5;
    // Minimum gap between progress callbacks (plus unconditional final emit).
    public const long TransferProgressIntervalMs = 500L;
    // Sender: longest to wait for TRANSFER_ACCEPT/REJECT after requesting.
    public const int TransferRequestTimeoutMs = 10000;
    // Receiver: longest to wait for TRANSFER_COMPLETE after byte-count match.
    public const int TransferCompleteTimeoutMs = 10000;
    // Pending (accepted, data not yet connected) transfer slot lifetime. The
    // sender dials immediately after TRANSFER_ACCEPT in the normal flow; a
    // stale slot must not wedge the single-transfer M06 receiver forever.
    public const long DataAcceptTimeoutMs = 30000L;
    // Data-plane preamble cap: 36-char UUID + '\n' is 37 bytes; larger input
    // is rejected without parsing (see data-transfer.md).
    public const int DataPreambleMaxBytes = 64;

    // Verification audit F5: grace after sender-side transport completion
    // before the coordinator tears down the control connection, so the
    // receiver processes TRANSFER_COMPLETE before control loss can fail the
    // commit. INITIAL value pending real-network validation (covers LAN
    // commit latency by orders of magnitude; pathological delays remain the
    // receiver's fail-closed domain).
    public const int TransferCommitLingerMs = 1000;
}
