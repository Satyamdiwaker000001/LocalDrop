package com.localdrop.network

// M04: explicit TCP control-channel lifecycle (PROTOCOL v1.0 §4 terminology
// where it applies to transport: CONNECTING -> NEGOTIATING -> READY, with
// FAILED for failure paths). DISCONNECTED/DISCONNECTING are transport-only
// additions (no session exists yet); the transfer state machine is NOT here.
// Mirrors ControlState.cs on Windows.
enum class ControlState {
    DISCONNECTED,
    CONNECTING,
    NEGOTIATING,
    READY,
    DISCONNECTING,
    FAILED
}
