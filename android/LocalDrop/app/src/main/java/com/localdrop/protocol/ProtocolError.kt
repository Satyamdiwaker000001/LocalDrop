package com.localdrop.protocol

// M02: structured protocol error model (PROTOCOL v1.0 §10).
// Exactly these 12 codes; unknown codes are rejected, never passed through.
// Errors are serializable VALUES (see ERROR payload), not thrown exceptions.
enum class ErrorCode(val wire: String) {
    INVALID_PROTOCOL("INVALID_PROTOCOL"),
    AUTH_FAILED("AUTH_FAILED"),
    PAIRING_REQUIRED("PAIRING_REQUIRED"),
    TRANSFER_REJECTED("TRANSFER_REJECTED"),
    NOT_ENOUGH_STORAGE("NOT_ENOUGH_STORAGE"),
    FILE_NOT_FOUND("FILE_NOT_FOUND"),
    NETWORK_LOST("NETWORK_LOST"),
    RESUME_UNAVAILABLE("RESUME_UNAVAILABLE"),
    CHECKSUM_MISMATCH("CHECKSUM_MISMATCH"),
    CANCELLED("CANCELLED"),
    TIMEOUT("TIMEOUT"),
    INTERNAL_ERROR("INTERNAL_ERROR");

    companion object {
        fun fromWire(wire: String): ErrorCode? =
            entries.firstOrNull { it.wire == wire }
    }
}

// Structured error value. `message` must not contain secrets (SECURITY v1.0 §5).
data class ProtocolError(val code: ErrorCode, val message: String) {
    fun toJsonMap(): LinkedHashMap<String, JsonValue> = linkedMapOf(
        "code" to JsonValue.Str(code.wire),
        "message" to JsonValue.Str(message)
    )

    companion object {
        fun parse(map: Map<String, JsonValue>): PayloadResult<ProtocolError> {
            val r = FieldReader(map, "error")
            val codeWire = r.string("code", 1, 64)
            val code = codeWire?.let { ErrorCode.fromWire(it) }
            if (codeWire != null && code == null) {
                return PayloadResult.Failure("error.code: unknown error code '$codeWire'")
            }
            val message = r.string("message", 0, Protocol.MAX_ERROR_MESSAGE_LEN)
            val err = r.error()
            if (err != null || code == null || message == null) {
                return PayloadResult.Failure(err ?: "error: invalid error object")
            }
            return PayloadResult.Success(ProtocolError(code, message))
        }
    }
}
