package com.localdrop.protocol

// M02: common message envelope (shared-protocol/envelope.md).
// Canonical wire key order:
//   protocol, protocolVersion, type, messageId,
//   correlationId?, sessionId?, transferId?, payload
// Optional IDs are omitted (never null) when absent. Validation rejects
// malformed envelopes with INVALID_PROTOCOL — never silently, never by throw.
data class LocalDropMessage(
    val type: MessageType,
    val messageId: String,
    val correlationId: String?,
    val sessionId: String?,
    val transferId: String?,
    val payload: TypedPayload
) {
    fun toJsonMap(): LinkedHashMap<String, JsonValue> {
        val m = linkedMapOf<String, JsonValue>(
            "protocol" to JsonValue.Str(Protocol.PROTOCOL_ID),
            "protocolVersion" to JsonValue.Num(Protocol.PROTOCOL_VERSION.toLong()),
            "type" to JsonValue.Str(type.wire),
            "messageId" to JsonValue.Str(messageId)
        )
        if (correlationId != null) m["correlationId"] = JsonValue.Str(correlationId)
        if (sessionId != null) m["sessionId"] = JsonValue.Str(sessionId)
        if (transferId != null) m["transferId"] = JsonValue.Str(transferId)
        m["payload"] = JsonValue.Obj(payload.toJsonMap())
        return m
    }
}

// Typed payload union: exactly one variant per MessageType.
sealed interface TypedPayload {
    fun toJsonMap(): LinkedHashMap<String, JsonValue>

    data class Hello(val v: HelloPayload) : TypedPayload {
        override fun toJsonMap() = v.toJsonMap()
    }
    data class HelloAck(val v: HelloAckPayload) : TypedPayload {
        override fun toJsonMap() = v.toJsonMap()
    }
    data class PairRequest(val v: PairRequestPayload) : TypedPayload {
        override fun toJsonMap() = v.toJsonMap()
    }
    data class PairResponse(val v: PairResponsePayload) : TypedPayload {
        override fun toJsonMap() = v.toJsonMap()
    }
    data class AuthRequest(val v: AuthRequestPayload) : TypedPayload {
        override fun toJsonMap() = v.toJsonMap()
    }
    data class AuthResponse(val v: AuthResponsePayload) : TypedPayload {
        override fun toJsonMap() = v.toJsonMap()
    }
    data class TransferRequest(val v: TransferRequestPayload) : TypedPayload {
        override fun toJsonMap() = v.toJsonMap()
    }
    data class TransferAccept(val v: TransferAcceptPayload) : TypedPayload {
        override fun toJsonMap() = v.toJsonMap()
    }
    data class TransferReject(val v: TransferRejectPayload) : TypedPayload {
        override fun toJsonMap() = v.toJsonMap()
    }
    data class TransferStart(val v: TransferStartPayload) : TypedPayload {
        override fun toJsonMap() = v.toJsonMap()
    }
    data class TransferProgress(val v: TransferProgressPayload) : TypedPayload {
        override fun toJsonMap() = v.toJsonMap()
    }
    data class Pause(val v: TransferIdOnlyPayload) : TypedPayload {
        override fun toJsonMap() = v.toJsonMap()
    }
    data class Resume(val v: TransferIdOnlyPayload) : TypedPayload {
        override fun toJsonMap() = v.toJsonMap()
    }
    data class Cancel(val v: CancelPayload) : TypedPayload {
        override fun toJsonMap() = v.toJsonMap()
    }
    data class ResumeQuery(val v: ResumeQueryPayload) : TypedPayload {
        override fun toJsonMap() = v.toJsonMap()
    }
    data class ResumeResponse(val v: ResumeResponsePayload) : TypedPayload {
        override fun toJsonMap() = v.toJsonMap()
    }
    data class TransferComplete(val v: TransferCompletePayload) : TypedPayload {
        override fun toJsonMap() = v.toJsonMap()
    }
    data class VerifyRequest(val v: VerifyRequestPayload) : TypedPayload {
        override fun toJsonMap() = v.toJsonMap()
    }
    data class VerifyResult(val v: VerifyResultPayload) : TypedPayload {
        override fun toJsonMap() = v.toJsonMap()
    }
    data class Error(val v: ErrorPayload) : TypedPayload {
        override fun toJsonMap() = v.toJsonMap()
    }
}

sealed interface DecodeOutcome {
    data class Success(val message: LocalDropMessage) : DecodeOutcome
    data class Failure(val code: ErrorCode, val detail: String) : DecodeOutcome
}

object ProtocolCodec {
    fun newMessageId(): String = Uuids.random()

    // Deterministic canonical serialization (byte-identical with Windows).
    fun encode(message: LocalDropMessage): String =
        JsonCanonical.serialize(JsonValue.Obj(message.toJsonMap()))

    fun encodeUtf8(message: LocalDropMessage): ByteArray =
        encode(message).toByteArray(Charsets.UTF_8)

    // Validates before processing; never throws on peer input.
    // Inputs larger than MAX_FRAME_BYTES are rejected without parsing
    // (bounds allocation from untrusted lengths).
    fun decode(text: String): DecodeOutcome {
        if (text.toByteArray(Charsets.UTF_8).size > Protocol.MAX_FRAME_BYTES) {
            return DecodeOutcome.Failure(ErrorCode.INVALID_PROTOCOL, "message exceeds size limit")
        }
        val root = when (val p = JsonCanonical.parse(text)) {
            is JsonParseResult.Ok -> p.value
            is JsonParseResult.Err -> return DecodeOutcome.Failure(
                ErrorCode.INVALID_PROTOCOL, "malformed JSON: ${p.detail}"
            )
        }
        if (root !is JsonValue.Obj) {
            return DecodeOutcome.Failure(ErrorCode.INVALID_PROTOCOL, "top level must be an object")
        }
        val r = FieldReader(root.entries, "envelope")
        val protocol = r.string("protocol", 1, 64)
        if (protocol != null && protocol != Protocol.PROTOCOL_ID) {
            return DecodeOutcome.Failure(ErrorCode.INVALID_PROTOCOL, "envelope.protocol: unknown protocol")
        }
        val version = r.int("protocolVersion", 0, Int.MAX_VALUE.toLong())
        if (version != null && version != Protocol.PROTOCOL_VERSION.toLong()) {
            return DecodeOutcome.Failure(
                ErrorCode.INVALID_PROTOCOL, "envelope.protocolVersion: unsupported version"
            )
        }
        val typeWire = r.string("type", 1, 64)
        val type = typeWire?.let { MessageType.fromWire(it) }
        if (typeWire != null && type == null) {
            return DecodeOutcome.Failure(
                ErrorCode.INVALID_PROTOCOL, "envelope.type: unknown message type '$typeWire'"
            )
        }
        val messageId = r.uuid("messageId")
        val correlationId = if (root.entries.containsKey("correlationId")) r.uuid("correlationId") else null
        val sessionId = if (root.entries.containsKey("sessionId")) r.uuid("sessionId") else null
        val transferId = if (root.entries.containsKey("transferId")) r.uuid("transferId") else null
        val payloadMap = r.obj("payload")
        val err = r.error()
        if (err != null || protocol == null || version == null || type == null ||
            messageId == null || payloadMap == null
        ) {
            return DecodeOutcome.Failure(ErrorCode.INVALID_PROTOCOL, err ?: "envelope: invalid message")
        }
        if (root.entries.containsKey("correlationId") && correlationId == null) {
            return DecodeOutcome.Failure(ErrorCode.INVALID_PROTOCOL, err ?: "envelope: invalid correlationId")
        }
        if (root.entries.containsKey("sessionId") && sessionId == null) {
            return DecodeOutcome.Failure(ErrorCode.INVALID_PROTOCOL, err ?: "envelope: invalid sessionId")
        }
        if (root.entries.containsKey("transferId") && transferId == null) {
            return DecodeOutcome.Failure(ErrorCode.INVALID_PROTOCOL, err ?: "envelope: invalid transferId")
        }

        val payload = when (type) {
            MessageType.HELLO -> HelloPayload.parse(payloadMap).mapTyped(TypedPayload::Hello)
            MessageType.HELLO_ACK -> HelloAckPayload.parse(payloadMap).mapTyped(TypedPayload::HelloAck)
            MessageType.PAIR_REQUEST -> PairRequestPayload.parse(payloadMap).mapTyped(TypedPayload::PairRequest)
            MessageType.PAIR_RESPONSE -> PairResponsePayload.parse(payloadMap).mapTyped(TypedPayload::PairResponse)
            MessageType.AUTH_REQUEST -> AuthRequestPayload.parse(payloadMap).mapTyped(TypedPayload::AuthRequest)
            MessageType.AUTH_RESPONSE -> AuthResponsePayload.parse(payloadMap).mapTyped(TypedPayload::AuthResponse)
            MessageType.TRANSFER_REQUEST -> TransferRequestPayload.parse(payloadMap).mapTyped(TypedPayload::TransferRequest)
            MessageType.TRANSFER_ACCEPT -> TransferAcceptPayload.parse(payloadMap).mapTyped(TypedPayload::TransferAccept)
            MessageType.TRANSFER_REJECT -> TransferRejectPayload.parse(payloadMap).mapTyped(TypedPayload::TransferReject)
            MessageType.TRANSFER_START -> TransferStartPayload.parse(payloadMap).mapTyped(TypedPayload::TransferStart)
            MessageType.TRANSFER_PROGRESS -> TransferProgressPayload.parse(payloadMap).mapTyped(TypedPayload::TransferProgress)
            MessageType.PAUSE -> TransferIdOnlyPayload.parse(payloadMap, "PAUSE").mapTyped(TypedPayload::Pause)
            MessageType.RESUME -> TransferIdOnlyPayload.parse(payloadMap, "RESUME").mapTyped(TypedPayload::Resume)
            MessageType.CANCEL -> CancelPayload.parse(payloadMap).mapTyped(TypedPayload::Cancel)
            MessageType.RESUME_QUERY -> ResumeQueryPayload.parse(payloadMap).mapTyped(TypedPayload::ResumeQuery)
            MessageType.RESUME_RESPONSE -> ResumeResponsePayload.parse(payloadMap).mapTyped(TypedPayload::ResumeResponse)
            MessageType.TRANSFER_COMPLETE -> TransferCompletePayload.parse(payloadMap).mapTyped(TypedPayload::TransferComplete)
            MessageType.VERIFY_REQUEST -> VerifyRequestPayload.parse(payloadMap).mapTyped(TypedPayload::VerifyRequest)
            MessageType.VERIFY_RESULT -> VerifyResultPayload.parse(payloadMap).mapTyped(TypedPayload::VerifyResult)
            MessageType.ERROR -> ErrorPayload.parse(payloadMap).mapTyped(TypedPayload::Error)
        }
        return when (payload) {
            is PayloadResult.Success -> {
                // Envelope/payload transferId consistency (contract SHOULD rule).
                val inner = transferIdOf(type, payload.value)
                if (transferId != null && inner != null && transferId != inner) {
                    DecodeOutcome.Failure(
                        ErrorCode.INVALID_PROTOCOL,
                        "envelope.transferId does not match payload transferId"
                    )
                } else {
                    DecodeOutcome.Success(
                        LocalDropMessage(type, messageId, correlationId, sessionId, transferId, payload.value)
                    )
                }
            }
            is PayloadResult.Failure -> DecodeOutcome.Failure(ErrorCode.INVALID_PROTOCOL, payload.detail)
        }
    }

    fun decodeUtf8(bytes: ByteArray): DecodeOutcome {
        val text = try {
            String(bytes, Charsets.UTF_8)
        } catch (e: Exception) {
            return DecodeOutcome.Failure(ErrorCode.INVALID_PROTOCOL, "bytes are not valid UTF-8")
        }
        // Strict UTF-8 check: round-trip must reproduce the bytes
        // (rejects overlong forms / invalid sequences Java replaces with U+FFFD).
        if (!text.toByteArray(Charsets.UTF_8).contentEquals(bytes)) {
            return DecodeOutcome.Failure(ErrorCode.INVALID_PROTOCOL, "bytes are not valid UTF-8")
        }
        return decode(text)
    }

    private fun <T> PayloadResult<T>.mapTyped(wrap: (T) -> TypedPayload): PayloadResult<TypedPayload> =
        when (this) {
            is PayloadResult.Success -> PayloadResult.Success(wrap(value))
            is PayloadResult.Failure -> this
        }

    private fun transferIdOf(type: MessageType, payload: TypedPayload): String? = when (payload) {
        is TypedPayload.TransferRequest -> payload.v.metadata.transferId
        is TypedPayload.TransferAccept -> payload.v.transferId
        is TypedPayload.TransferReject -> payload.v.transferId
        is TypedPayload.TransferStart -> payload.v.transferId
        is TypedPayload.TransferProgress -> payload.v.transferId
        is TypedPayload.Pause -> payload.v.transferId
        is TypedPayload.Resume -> payload.v.transferId
        is TypedPayload.Cancel -> payload.v.transferId
        is TypedPayload.ResumeQuery -> payload.v.transferId
        is TypedPayload.ResumeResponse -> payload.v.transferId
        is TypedPayload.TransferComplete -> payload.v.transferId
        is TypedPayload.VerifyRequest -> payload.v.transferId
        is TypedPayload.VerifyResult -> payload.v.transferId
        else -> null // type has no transferId; `type` param kept for readability
    }
}
