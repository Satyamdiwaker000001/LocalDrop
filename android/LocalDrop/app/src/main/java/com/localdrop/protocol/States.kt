package com.localdrop.protocol

// M02: protocol/domain state vocabularies — values only.
// NO transition engine, NO timers, NO workers in M02 (later milestones own
// the engine; M02 owns the words). Parsing rejects any other value.

// Transfer states (UI/UX Spec §13). Wire strings match AppConfig (M01).
enum class TransferState(val wire: String) {
    AVAILABLE("available"),
    CONNECTING("connecting"),
    WAITING_ACCEPTANCE("waiting acceptance"),
    TRANSFERRING("transferring"),
    PAUSED("paused"),
    RECONNECTING("reconnecting"),
    VERIFYING("verifying"),
    COMPLETED("completed"),
    FAILED("failed"),
    CANCELLED("cancelled");

    companion object {
        fun fromWire(wire: String): TransferState? =
            entries.firstOrNull { it.wire == wire }
    }
}

// Session states (PROTOCOL v1.0 §4).
enum class SessionState(val wire: String) {
    DISCOVERED("DISCOVERED"),
    CONNECTING("CONNECTING"),
    NEGOTIATING("NEGOTIATING"),
    AUTHENTICATING("AUTHENTICATING"),
    READY("READY"),
    TRANSFER_REQUESTED("TRANSFER_REQUESTED"),
    ACCEPTED("ACCEPTED"),
    TRANSFERRING("TRANSFERRING"),
    VERIFYING("VERIFYING"),
    COMPLETED("COMPLETED"),
    REJECTED("REJECTED"),
    CANCELLED("CANCELLED"),
    FAILED("FAILED"),
    RECONNECTING("RECONNECTING"),
    RESUMING("RESUMING");

    companion object {
        fun fromWire(wire: String): SessionState? =
            entries.firstOrNull { it.wire == wire }
    }
}
