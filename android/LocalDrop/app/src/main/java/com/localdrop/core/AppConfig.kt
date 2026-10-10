package com.localdrop.core

// M01 Foundation: centralized configuration boundary.
// Port values are intentionally TBD (Complete Spec 5.5: chosen during
// implementation after validating Android/Windows constraints).
// M01 MUST NOT invent or scatter magic port numbers.
object AppConfig {
    const val PROTOCOL = "localdrop"
    const val PROTOCOL_VERSION = 1

    // Logical checkpoint unit for resume/progress accounting (Master Spec §7).
    // 8 MiB default, must remain configurable. Not used for TCP packet sizing.
    const val DEFAULT_CHECKPOINT_BYTES: Long = 8L * 1024L * 1024L

    // Transfer state vocabulary (UI/UX Spec §13). State machine itself is M02.
    val TRANSFER_STATES: List<String> = listOf(
        "available",
        "connecting",
        "waiting acceptance",
        "transferring",
        "paused",
        "reconnecting",
        "verifying",
        "completed",
        "failed",
        "cancelled"
    )

    // M03 LAN discovery (Complete Spec §5.1/§5.5). Centralized here per M03 §8:
    // no timeout/port constants scattered elsewhere. Values are M03-chosen
    // (the frozen spec authorizes UDP broadcast + centralized ports but does
    // not fix numbers); changing them is a versioned protocol decision.
    // UDP broadcast port shared by Android and Windows announcements.
    const val DISCOVERY_UDP_PORT = 48423
    // Interval between discovery announcements.
    const val DISCOVERY_ANNOUNCE_INTERVAL_MS: Long = 2000L
    // A peer unseen for this long is expired/removed (tolerates ~4 lost
    // announcements at the default interval).
    const val DISCOVERY_PEER_TIMEOUT_MS: Long = 10000L
    // Upper bound for an inbound discovery datagram. Announcements carry a
    // small HELLO record only; larger datagrams are rejected without parsing.
    const val DISCOVERY_MAX_PACKET_BYTES = 4096

    // M04 TCP control channel (Complete Spec §5.2/§5.5, PROTOCOL v1.0 §2).
    // Centralized here per M04 §12: no port/timeout constants scattered
    // elsewhere. Values are M04-chosen (the frozen spec authorizes direct
    // TCP control + centralized ports but does not fix numbers); changing
    // them is a versioned protocol decision.
    // TCP port for the control channel. MUST differ from DISCOVERY_UDP_PORT:
    // discovery is UDP, control is TCP; separate transports, separate ports.
    const val CONTROL_TCP_PORT = 48424
    // Upper bound for establishing the TCP connection itself.
    const val CONTROL_CONNECT_TIMEOUT_MS = 5000
    // Upper bound for completing the HELLO/HELLO_ACK handshake after connect.
    const val CONTROL_HANDSHAKE_TIMEOUT_MS = 5000

    // M05 security (shared-protocol/authentication.md). Centralized here per
    // the M05 prompt: future policy changes must not rewrite auth logic.
    // Authentication method id carried in AUTH_REQUEST.method (exact match).
    const val AUTH_METHOD_ID = "localdrop-ecdsa-p256-v1"
    // Fresh nonce size per side per handshake (platform CSPRNG).
    const val AUTH_NONCE_BYTES = 32
    // Overall bound for the mutual-auth handshake on a control connection.
    const val AUTH_TIMEOUT_MS = 10000
    // Receiver transaction code lifetime (5 minutes, short-lived by design).
    const val TRANSACTION_CODE_LIFETIME_MS: Long = 300000L
    // Transaction codes are exactly this many decimal digits (%06d).
    const val TRANSACTION_CODE_DIGITS = 6
    // Android KeyStore alias for the per-installation device identity key.
    // Private key material never leaves the KeyStore.
    const val DEVICE_IDENTITY_KEY_ALIAS = "localdrop_device_identity"

    // M06 streaming transfer (docs/architecture/m06-transfer-decisions.md).
    // Centralized here per the M06 prompt: no ports/sizes/timeouts scattered
    // elsewhere. Block/buffer numbers are INITIAL CONSERVATIVE values for M15
    // benchmark validation, NOT product limits: file sizes are unbounded
    // (64-bit) and throughput is uncapped. Changing them needs no protocol
    // change (they never appear on the wire).
    // Dedicated TCP data port. MUST differ from discovery (UDP 48423) and
    // control (TCP 48424): separate transports, separate ports.
    const val DATA_TCP_PORT = 48425
    // Upper bound for establishing the data connection.
    const val DATA_CONNECT_TIMEOUT_MS = 5000
    // Application block floor: below this, per-operation overhead dominates.
    const val TRANSFER_BLOCK_MIN_BYTES = 65536
    // Application block ceiling: caps per-transfer heap + operation latency.
    const val TRANSFER_BLOCK_MAX_BYTES = 1048576
    // Initial block cap for file-size-aware startup (see AdaptiveBlockSizer).
    const val TRANSFER_BLOCK_INITIAL_BYTES = 262144
    // Longest a single block operation may take before counting as a stall.
    const val ADAPT_STALL_THRESHOLD_MS: Long = 500L
    // Quiet period after any adaptation before the next may occur.
    const val ADAPT_COOLDOWN_MS: Long = 2000L
    // Consecutive agreeing windows required before growing/shrinking.
    const val ADAPT_CONSECUTIVE_WINDOWS = 3
    // Window throughput reaching this multiple of the best baseline re-arms
    // growth probing after a shrink (genuine improvement required).
    const val ADAPT_GROWTH_RATIO = 1.2
    // Window throughput below this multiple of baseline counts toward shrink.
    const val ADAPT_SHRINK_RATIO = 0.5
    // Minimum gap between progress callbacks (plus unconditional final emit).
    const val TRANSFER_PROGRESS_INTERVAL_MS: Long = 500L
    // Sender: longest to wait for TRANSFER_ACCEPT/REJECT after requesting.
    const val TRANSFER_REQUEST_TIMEOUT_MS = 10000
    // Receiver: longest to wait for TRANSFER_COMPLETE after byte-count match.
    const val TRANSFER_COMPLETE_TIMEOUT_MS = 10000
    // Pending (accepted, data not yet connected) transfer slot lifetime. The
    // sender dials immediately after TRANSFER_ACCEPT in the normal flow; a
    // stale slot must not wedge the single-transfer M06 receiver forever.
    const val DATA_ACCEPT_TIMEOUT_MS: Long = 30000L
    // Data-plane preamble cap: 36-char UUID + '\n' is 37 bytes; larger input
    // is rejected without parsing (see data-transfer.md).
    const val DATA_PREAMBLE_MAX_BYTES = 64

    // M13 service-owned graph (docs/architecture/m13-service-decisions.md).
    // Notification channel for transfer notifications.
    const val NOTIFICATION_CHANNEL_ID = "localdrop_transfers"
    // Foreground summary notification ID (stable; per-transfer IDs come from
    // NotificationIds, never from transferId.hashCode()).
    const val NOTIFICATION_SUMMARY_ID = 1
    // Widget snapshot older than this is rendered as idle (stale active
    // transfers are never presented as current).
    const val WIDGET_STALE_AFTER_MS: Long = 60000L
}
