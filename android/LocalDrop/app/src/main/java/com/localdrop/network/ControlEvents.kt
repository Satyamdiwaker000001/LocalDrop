package com.localdrop.network

import com.localdrop.protocol.LocalDropMessage

// M04: control-channel events. The owner (application layer, tests) observes
// connections through this interface; sockets are never exposed to UI.
// Mirrors ControlEvents.cs on Windows.
enum class CloseReason {
    LOCAL_DISCONNECT,
    REMOTE_CLOSE,
    PROTOCOL_VIOLATION,
    HANDSHAKE_TIMEOUT,
    CONNECT_FAILED,
    IO_ERROR
}

interface ControlChannelListener {
    // A validated post-handshake M02 message arrived. Carries no business
    // semantics at this layer; correlation completions (sendAndReceive) are
    // ALSO delivered here so no message is silently swallowed.
    fun onMessage(connection: ControlConnection, message: LocalDropMessage)

    // The connection reached a terminal state. Exactly once per
    // connect/accept cycle. `detail` is human-readable diagnostics only.
    fun onClosed(connection: ControlConnection, reason: CloseReason, detail: String)
}
