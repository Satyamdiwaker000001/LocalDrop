package com.localdrop.protocol

// M02 protocol foundation: single source of protocol constants.
// Mirrors shared-protocol/ + PROTOCOL v1.0. Wire version is independent of
// the application version. No networking, no I/O, no crypto here.
object Protocol {
    const val PROTOCOL_ID = "localdrop"
    const val PROTOCOL_VERSION = 1

    // Control plane carries metadata only (never file bytes).
    // Bounds attacker-controlled allocation: a declared frame larger than
    // this is rejected WITHOUT allocating. Protocol surface: changing this
    // is a versioned decision (shared-protocol/framing-resume-integrity.md).
    const val MAX_FRAME_BYTES = 1024 * 1024

    // Logical checkpoint default for resume/progress accounting
    // (Master Spec §7). Not a TCP packet size.
    const val DEFAULT_CHECKPOINT_BYTES: Long = 8L * 1024L * 1024L

    // Field bounds (shared-protocol/messages.md, transfer-metadata.md).
    const val MAX_DEVICE_NAME = 128
    const val MAX_CAPABILITIES = 32
    const val MAX_CAPABILITY_LEN = 64
    const val MAX_FILE_NAME = 255
    const val MAX_FILE_ID_LEN = 128
    const val MAX_DISPLAY_CODE = 16
    const val MAX_AUTH_METHOD_LEN = 64
    const val MAX_AUTH_TOKEN_LEN = 4096
    const val MAX_REASON_LEN = 256
    const val MAX_ERROR_MESSAGE_LEN = 512
    const val MAX_CHECKPOINT_SIZE: Long = 1024L * 1024L * 1024L
}
