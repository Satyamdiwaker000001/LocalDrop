package com.localdrop.protocol

// M02: exact wire message-type vocabulary (PROTOCOL v1.0 §5).
// Exactly these 20 values; anything else is rejected on receipt.
// Models are data contracts only — no feature behavior in M02.
enum class MessageType(val wire: String) {
    HELLO("HELLO"),
    HELLO_ACK("HELLO_ACK"),
    PAIR_REQUEST("PAIR_REQUEST"),
    PAIR_RESPONSE("PAIR_RESPONSE"),
    AUTH_REQUEST("AUTH_REQUEST"),
    AUTH_RESPONSE("AUTH_RESPONSE"),
    TRANSFER_REQUEST("TRANSFER_REQUEST"),
    TRANSFER_ACCEPT("TRANSFER_ACCEPT"),
    TRANSFER_REJECT("TRANSFER_REJECT"),
    TRANSFER_START("TRANSFER_START"),
    TRANSFER_PROGRESS("TRANSFER_PROGRESS"),
    PAUSE("PAUSE"),
    RESUME("RESUME"),
    CANCEL("CANCEL"),
    RESUME_QUERY("RESUME_QUERY"),
    RESUME_RESPONSE("RESUME_RESPONSE"),
    TRANSFER_COMPLETE("TRANSFER_COMPLETE"),
    VERIFY_REQUEST("VERIFY_REQUEST"),
    VERIFY_RESULT("VERIFY_RESULT"),
    ERROR("ERROR");

    companion object {
        fun fromWire(wire: String): MessageType? =
            entries.firstOrNull { it.wire == wire }
    }
}
