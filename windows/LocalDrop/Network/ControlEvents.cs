// M04: control-channel events. The owner (application layer, tests) observes
// connections through this interface; sockets are never exposed to UI.
// Mirrors ControlEvents.kt on Android.
using LocalDrop.Protocol;

namespace LocalDrop.Network;

public enum CloseReason
{
    LOCAL_DISCONNECT,
    REMOTE_CLOSE,
    PROTOCOL_VIOLATION,
    HANDSHAKE_TIMEOUT,
    CONNECT_FAILED,
    IO_ERROR,
}

public interface IControlEvents
{
    // A validated post-handshake M02 message arrived. Carries no business
    // semantics at this layer; correlation completions (SendAndReceiveAsync)
    // are ALSO delivered here so no message is silently swallowed.
    void OnMessage(ControlConnection connection, LocalDropMessage message);

    // The connection reached a terminal state. Exactly once per
    // connect/accept cycle. `detail` is human-readable diagnostics only.
    void OnClosed(ControlConnection connection, CloseReason reason, string detail);
}
