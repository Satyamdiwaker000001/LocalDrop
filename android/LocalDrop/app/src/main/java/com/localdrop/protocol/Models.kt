package com.localdrop.protocol

// M02: payload models — data contracts only (shared-protocol/messages.md).
// No behavior: no networking, no pairing/authentication execution, no
// transfer execution. Every parse validates shape + bounds; failures map to
// INVALID_PROTOCOL. Serialization emits canonical key order.

// ---- shared validation helpers ----

private val SHA256_RE = Regex("^[0-9a-f]{64}$")

// ---- capability / hello ----

data class HelloPayload(
    val deviceId: String,
    val deviceName: String,
    val deviceType: String,
    val controlPort: Long,
    val capabilities: List<String>
) {
    fun toJsonMap(): LinkedHashMap<String, JsonValue> = linkedMapOf(
        "deviceId" to JsonValue.Str(deviceId),
        "deviceName" to JsonValue.Str(deviceName),
        "deviceType" to JsonValue.Str(deviceType),
        "controlPort" to JsonValue.Num(controlPort),
        "capabilities" to JsonValue.Arr(capabilities.map { JsonValue.Str(it) })
    )

    companion object {
        fun parse(map: Map<String, JsonValue>): PayloadResult<HelloPayload> {
            val r = FieldReader(map, "HELLO")
            val deviceId = r.uuid("deviceId")
            val deviceName = r.string("deviceName", 1, Protocol.MAX_DEVICE_NAME)
            val deviceType = r.string("deviceType", 1, 16)
            if (deviceType != null && deviceType != "android" && deviceType != "windows") {
                return PayloadResult.Failure("HELLO.deviceType: must be \"android\" or \"windows\"")
            }
            val controlPort = r.int("controlPort", 0, 65535)
            val capabilities = r.stringList(
                "capabilities", Protocol.MAX_CAPABILITIES, 1, Protocol.MAX_CAPABILITY_LEN
            )
            val err = r.error()
            if (err != null || deviceId == null || deviceName == null ||
                deviceType == null || controlPort == null || capabilities == null
            ) {
                return PayloadResult.Failure(err ?: "HELLO: invalid payload")
            }
            return PayloadResult.Success(
                HelloPayload(deviceId, deviceName, deviceType, controlPort, capabilities)
            )
        }
    }
}

data class HelloAckPayload(
    val deviceId: String,
    val deviceName: String,
    val deviceType: String,
    val accepted: Boolean
) {
    fun toJsonMap(): LinkedHashMap<String, JsonValue> = linkedMapOf(
        "deviceId" to JsonValue.Str(deviceId),
        "deviceName" to JsonValue.Str(deviceName),
        "deviceType" to JsonValue.Str(deviceType),
        "accepted" to JsonValue.Bool(accepted)
    )

    companion object {
        fun parse(map: Map<String, JsonValue>): PayloadResult<HelloAckPayload> {
            val r = FieldReader(map, "HELLO_ACK")
            val deviceId = r.uuid("deviceId")
            val deviceName = r.string("deviceName", 1, Protocol.MAX_DEVICE_NAME)
            val deviceType = r.string("deviceType", 1, 16)
            if (deviceType != null && deviceType != "android" && deviceType != "windows") {
                return PayloadResult.Failure("HELLO_ACK.deviceType: must be \"android\" or \"windows\"")
            }
            val accepted = r.bool("accepted")
            val err = r.error()
            if (err != null || deviceId == null || deviceName == null ||
                deviceType == null || accepted == null
            ) {
                return PayloadResult.Failure(err ?: "HELLO_ACK: invalid payload")
            }
            return PayloadResult.Success(HelloAckPayload(deviceId, deviceName, deviceType, accepted))
        }
    }
}

// ---- pairing (structure only; approval is M05) ----

data class PairRequestPayload(
    val deviceId: String,
    val deviceName: String,
    val displayCode: String
) {
    fun toJsonMap(): LinkedHashMap<String, JsonValue> = linkedMapOf(
        "deviceId" to JsonValue.Str(deviceId),
        "deviceName" to JsonValue.Str(deviceName),
        "displayCode" to JsonValue.Str(displayCode)
    )

    companion object {
        fun parse(map: Map<String, JsonValue>): PayloadResult<PairRequestPayload> {
            val r = FieldReader(map, "PAIR_REQUEST")
            val deviceId = r.uuid("deviceId")
            val deviceName = r.string("deviceName", 1, Protocol.MAX_DEVICE_NAME)
            val displayCode = r.string("displayCode", 1, Protocol.MAX_DISPLAY_CODE)
            val err = r.error()
            if (err != null || deviceId == null || deviceName == null || displayCode == null) {
                return PayloadResult.Failure(err ?: "PAIR_REQUEST: invalid payload")
            }
            return PayloadResult.Success(PairRequestPayload(deviceId, deviceName, displayCode))
        }
    }
}

data class PairResponsePayload(
    val deviceId: String,
    val accepted: Boolean,
    val reason: String?
) {
    fun toJsonMap(): LinkedHashMap<String, JsonValue> {
        val m = linkedMapOf<String, JsonValue>(
            "deviceId" to JsonValue.Str(deviceId),
            "accepted" to JsonValue.Bool(accepted)
        )
        if (reason != null) m["reason"] = JsonValue.Str(reason)
        return m
    }

    companion object {
        fun parse(map: Map<String, JsonValue>): PayloadResult<PairResponsePayload> {
            val r = FieldReader(map, "PAIR_RESPONSE")
            val deviceId = r.uuid("deviceId")
            val accepted = r.bool("accepted")
            val reason = if (map.containsKey("reason")) {
                r.string("reason", 0, Protocol.MAX_REASON_LEN)
            } else null
            val err = r.error()
            if (err != null || deviceId == null || accepted == null) {
                return PayloadResult.Failure(err ?: "PAIR_RESPONSE: invalid payload")
            }
            if (map.containsKey("reason") && reason == null) {
                return PayloadResult.Failure(err ?: "PAIR_RESPONSE: invalid reason")
            }
            return PayloadResult.Success(PairResponsePayload(deviceId, accepted, reason))
        }
    }
}

// ---- authentication (structure only; handshake is M05) ----

data class AuthRequestPayload(
    val deviceId: String,
    val method: String,
    val token: String
) {
    fun toJsonMap(): LinkedHashMap<String, JsonValue> = linkedMapOf(
        "deviceId" to JsonValue.Str(deviceId),
        "method" to JsonValue.Str(method),
        "token" to JsonValue.Str(token)
    )

    companion object {
        fun parse(map: Map<String, JsonValue>): PayloadResult<AuthRequestPayload> {
            val r = FieldReader(map, "AUTH_REQUEST")
            val deviceId = r.uuid("deviceId")
            val method = r.string("method", 1, Protocol.MAX_AUTH_METHOD_LEN)
            val token = r.string("token", 1, Protocol.MAX_AUTH_TOKEN_LEN)
            val err = r.error()
            if (err != null || deviceId == null || method == null || token == null) {
                return PayloadResult.Failure(err ?: "AUTH_REQUEST: invalid payload")
            }
            return PayloadResult.Success(AuthRequestPayload(deviceId, method, token))
        }
    }
}

data class AuthResponsePayload(
    val deviceId: String,
    val accepted: Boolean,
    val reason: String?
) {
    fun toJsonMap(): LinkedHashMap<String, JsonValue> {
        val m = linkedMapOf<String, JsonValue>(
            "deviceId" to JsonValue.Str(deviceId),
            "accepted" to JsonValue.Bool(accepted)
        )
        if (reason != null) m["reason"] = JsonValue.Str(reason)
        return m
    }

    companion object {
        fun parse(map: Map<String, JsonValue>): PayloadResult<AuthResponsePayload> {
            val r = FieldReader(map, "AUTH_RESPONSE")
            val deviceId = r.uuid("deviceId")
            val accepted = r.bool("accepted")
            val reason = if (map.containsKey("reason")) {
                r.string("reason", 0, Protocol.MAX_REASON_LEN)
            } else null
            val err = r.error()
            if (err != null || deviceId == null || accepted == null) {
                return PayloadResult.Failure(err ?: "AUTH_RESPONSE: invalid payload")
            }
            if (map.containsKey("reason") && reason == null) {
                return PayloadResult.Failure(err ?: "AUTH_RESPONSE: invalid reason")
            }
            return PayloadResult.Success(AuthResponsePayload(deviceId, accepted, reason))
        }
    }
}

// ---- transfer metadata (contract only; transfer engine is M06+) ----

data class TransferMetadata(
    val transferId: String,
    val senderDeviceId: String,
    val receiverDeviceId: String,
    val fileId: String,
    val fileName: String,
    val fileSize: Long,
    val checkpointSize: Long,
    val sha256Hex: String?,
    val resumeOffset: Long
) {
    fun toJsonMap(): LinkedHashMap<String, JsonValue> {
        val m = linkedMapOf<String, JsonValue>(
            "transferId" to JsonValue.Str(transferId),
            "senderDeviceId" to JsonValue.Str(senderDeviceId),
            "receiverDeviceId" to JsonValue.Str(receiverDeviceId),
            "fileId" to JsonValue.Str(fileId),
            "fileName" to JsonValue.Str(fileName),
            "fileSize" to JsonValue.Num(fileSize),
            "checkpointSize" to JsonValue.Num(checkpointSize)
        )
        if (sha256Hex != null) m["sha256Hex"] = JsonValue.Str(sha256Hex)
        m["resumeOffset"] = JsonValue.Num(resumeOffset)
        return m
    }

    companion object {
        // Canonical key order note: sha256Hex is optional and sits between
        // checkpointSize and resumeOffset when present.
        fun parse(map: Map<String, JsonValue>, ctx: String = "TRANSFER_REQUEST"): PayloadResult<TransferMetadata> {
            val r = FieldReader(map, ctx)
            val transferId = r.uuid("transferId")
            val senderDeviceId = r.uuid("senderDeviceId")
            val receiverDeviceId = r.uuid("receiverDeviceId")
            val fileId = r.string("fileId", 1, Protocol.MAX_FILE_ID_LEN)
            val fileName = r.string("fileName", 1, Protocol.MAX_FILE_NAME)
            if (fileName != null && (fileName.contains('/') || fileName.contains('\\') ||
                        fileName == "." || fileName == "..")
            ) {
                return PayloadResult.Failure("$ctx.fileName: must be a base file name")
            }
            val fileSize = r.int("fileSize", 0, Long.MAX_VALUE)
            val checkpointSize = if (map.containsKey("checkpointSize")) {
                r.int("checkpointSize", 1, Protocol.MAX_CHECKPOINT_SIZE)
            } else Protocol.DEFAULT_CHECKPOINT_BYTES
            val shaRaw = if (map.containsKey("sha256Hex")) r.string("sha256Hex", 64, 64) else null
            val sha256Hex = if (map.containsKey("sha256Hex")) {
                if (shaRaw == null || !SHA256_RE.matches(shaRaw)) {
                    return PayloadResult.Failure(
                        r.error() ?: "$ctx.sha256Hex: must be 64 lowercase hex chars"
                    )
                }
                shaRaw
            } else null
            val resumeOffset = r.int("resumeOffset", 0, Long.MAX_VALUE)
            val err = r.error()
            if (err != null || transferId == null || senderDeviceId == null ||
                receiverDeviceId == null || fileId == null || fileName == null ||
                fileSize == null || checkpointSize == null || resumeOffset == null
            ) {
                return PayloadResult.Failure(err ?: "$ctx: invalid payload")
            }
            if (map.containsKey("checkpointSize") && checkpointSize == Protocol.DEFAULT_CHECKPOINT_BYTES) {
                // Present-but-default is fine; no-op (keeps the check explicit).
            }
            if (resumeOffset > fileSize) {
                return PayloadResult.Failure("$ctx.resumeOffset: exceeds fileSize")
            }
            return PayloadResult.Success(
                TransferMetadata(
                    transferId, senderDeviceId, receiverDeviceId, fileId, fileName,
                    fileSize, checkpointSize, sha256Hex, resumeOffset
                )
            )
        }
    }
}

// Envelope transferId SHOULD equal the payload transferId when both present;
// checked in Envelope.kt after dispatch.

// ---- transfer request / response ----

data class TransferRequestPayload(val metadata: TransferMetadata) {
    fun toJsonMap(): LinkedHashMap<String, JsonValue> = metadata.toJsonMap()

    companion object {
        fun parse(map: Map<String, JsonValue>): PayloadResult<TransferRequestPayload> {
            return when (val m = TransferMetadata.parse(map)) {
                is PayloadResult.Success -> PayloadResult.Success(TransferRequestPayload(m.value))
                is PayloadResult.Failure -> m
            }
        }
    }
}

data class TransferAcceptPayload(val transferId: String, val resumeOffset: Long) {
    fun toJsonMap(): LinkedHashMap<String, JsonValue> = linkedMapOf(
        "transferId" to JsonValue.Str(transferId),
        "resumeOffset" to JsonValue.Num(resumeOffset)
    )

    companion object {
        fun parse(map: Map<String, JsonValue>): PayloadResult<TransferAcceptPayload> {
            val r = FieldReader(map, "TRANSFER_ACCEPT")
            val transferId = r.uuid("transferId")
            val resumeOffset = r.int("resumeOffset", 0, Long.MAX_VALUE)
            val err = r.error()
            if (err != null || transferId == null || resumeOffset == null) {
                return PayloadResult.Failure(err ?: "TRANSFER_ACCEPT: invalid payload")
            }
            return PayloadResult.Success(TransferAcceptPayload(transferId, resumeOffset))
        }
    }
}

data class TransferRejectPayload(val transferId: String, val error: ProtocolError) {
    fun toJsonMap(): LinkedHashMap<String, JsonValue> = linkedMapOf(
        "transferId" to JsonValue.Str(transferId),
        "error" to JsonValue.Obj(error.toJsonMap())
    )

    companion object {
        fun parse(map: Map<String, JsonValue>): PayloadResult<TransferRejectPayload> {
            val r = FieldReader(map, "TRANSFER_REJECT")
            val transferId = r.uuid("transferId")
            val errObj = r.obj("error")
            val err = r.error()
            if (err != null || transferId == null || errObj == null) {
                return PayloadResult.Failure(err ?: "TRANSFER_REJECT: invalid payload")
            }
            return when (val e = ProtocolError.parse(errObj)) {
                is PayloadResult.Success -> PayloadResult.Success(TransferRejectPayload(transferId, e.value))
                is PayloadResult.Failure -> e
            }
        }
    }
}

// ---- progress signals (contract only) ----

data class TransferStartPayload(val transferId: String, val offset: Long) {
    fun toJsonMap(): LinkedHashMap<String, JsonValue> = linkedMapOf(
        "transferId" to JsonValue.Str(transferId),
        "offset" to JsonValue.Num(offset)
    )

    companion object {
        fun parse(map: Map<String, JsonValue>): PayloadResult<TransferStartPayload> {
            val r = FieldReader(map, "TRANSFER_START")
            val transferId = r.uuid("transferId")
            val offset = r.int("offset", 0, Long.MAX_VALUE)
            val err = r.error()
            if (err != null || transferId == null || offset == null) {
                return PayloadResult.Failure(err ?: "TRANSFER_START: invalid payload")
            }
            return PayloadResult.Success(TransferStartPayload(transferId, offset))
        }
    }
}

data class TransferProgressPayload(
    val transferId: String,
    val transferredBytes: Long,
    val totalBytes: Long
) {
    fun toJsonMap(): LinkedHashMap<String, JsonValue> = linkedMapOf(
        "transferId" to JsonValue.Str(transferId),
        "transferredBytes" to JsonValue.Num(transferredBytes),
        "totalBytes" to JsonValue.Num(totalBytes)
    )

    companion object {
        fun parse(map: Map<String, JsonValue>): PayloadResult<TransferProgressPayload> {
            val r = FieldReader(map, "TRANSFER_PROGRESS")
            val transferId = r.uuid("transferId")
            val transferredBytes = r.int("transferredBytes", 0, Long.MAX_VALUE)
            val totalBytes = r.int("totalBytes", 0, Long.MAX_VALUE)
            val err = r.error()
            if (err != null || transferId == null || transferredBytes == null || totalBytes == null) {
                return PayloadResult.Failure(err ?: "TRANSFER_PROGRESS: invalid payload")
            }
            if (transferredBytes > totalBytes) {
                return PayloadResult.Failure("TRANSFER_PROGRESS.transferredBytes: exceeds totalBytes")
            }
            return PayloadResult.Success(TransferProgressPayload(transferId, transferredBytes, totalBytes))
        }
    }
}

data class TransferIdOnlyPayload(val transferId: String) {
    fun toJsonMap(): LinkedHashMap<String, JsonValue> = linkedMapOf(
        "transferId" to JsonValue.Str(transferId)
    )

    companion object {
        // Shared by PAUSE and RESUME (identical shape, validated under own name).
        fun parse(map: Map<String, JsonValue>, ctx: String): PayloadResult<TransferIdOnlyPayload> {
            val r = FieldReader(map, ctx)
            val transferId = r.uuid("transferId")
            val err = r.error()
            if (err != null || transferId == null) {
                return PayloadResult.Failure(err ?: "$ctx: invalid payload")
            }
            return PayloadResult.Success(TransferIdOnlyPayload(transferId))
        }
    }
}

data class CancelPayload(val transferId: String, val reason: String?) {
    fun toJsonMap(): LinkedHashMap<String, JsonValue> {
        val m = linkedMapOf<String, JsonValue>("transferId" to JsonValue.Str(transferId))
        if (reason != null) m["reason"] = JsonValue.Str(reason)
        return m
    }

    companion object {
        fun parse(map: Map<String, JsonValue>): PayloadResult<CancelPayload> {
            val r = FieldReader(map, "CANCEL")
            val transferId = r.uuid("transferId")
            val reason = if (map.containsKey("reason")) {
                r.string("reason", 0, Protocol.MAX_REASON_LEN)
            } else null
            val err = r.error()
            if (err != null || transferId == null) {
                return PayloadResult.Failure(err ?: "CANCEL: invalid payload")
            }
            if (map.containsKey("reason") && reason == null) {
                return PayloadResult.Failure(err ?: "CANCEL: invalid reason")
            }
            return PayloadResult.Success(CancelPayload(transferId, reason))
        }
    }
}

// ---- resume negotiation (contract only; durable behavior is M10) ----

data class ResumeQueryPayload(val transferId: String, val fileId: String, val fileSize: Long) {
    fun toJsonMap(): LinkedHashMap<String, JsonValue> = linkedMapOf(
        "transferId" to JsonValue.Str(transferId),
        "fileId" to JsonValue.Str(fileId),
        "fileSize" to JsonValue.Num(fileSize)
    )

    companion object {
        fun parse(map: Map<String, JsonValue>): PayloadResult<ResumeQueryPayload> {
            val r = FieldReader(map, "RESUME_QUERY")
            val transferId = r.uuid("transferId")
            val fileId = r.string("fileId", 1, Protocol.MAX_FILE_ID_LEN)
            val fileSize = r.int("fileSize", 0, Long.MAX_VALUE)
            val err = r.error()
            if (err != null || transferId == null || fileId == null || fileSize == null) {
                return PayloadResult.Failure(err ?: "RESUME_QUERY: invalid payload")
            }
            return PayloadResult.Success(ResumeQueryPayload(transferId, fileId, fileSize))
        }
    }
}

data class ResumeResponsePayload(val transferId: String, val confirmedOffset: Long, val fileSize: Long) {
    fun toJsonMap(): LinkedHashMap<String, JsonValue> = linkedMapOf(
        "transferId" to JsonValue.Str(transferId),
        "confirmedOffset" to JsonValue.Num(confirmedOffset),
        "fileSize" to JsonValue.Num(fileSize)
    )

    companion object {
        fun parse(map: Map<String, JsonValue>): PayloadResult<ResumeResponsePayload> {
            val r = FieldReader(map, "RESUME_RESPONSE")
            val transferId = r.uuid("transferId")
            val confirmedOffset = r.int("confirmedOffset", 0, Long.MAX_VALUE)
            val fileSize = r.int("fileSize", 0, Long.MAX_VALUE)
            val err = r.error()
            if (err != null || transferId == null || confirmedOffset == null || fileSize == null) {
                return PayloadResult.Failure(err ?: "RESUME_RESPONSE: invalid payload")
            }
            return PayloadResult.Success(ResumeResponsePayload(transferId, confirmedOffset, fileSize))
        }
    }
}

// ---- completion / verification (contract only; enforcement is M11) ----

private fun sha256Field(r: FieldReader, ctx: String, key: String): String? {
    val s = r.string(key, 64, 64)
    if (s != null && !SHA256_RE.matches(s)) return null
    return s
}

data class TransferCompletePayload(val transferId: String, val fileSize: Long, val sha256Hex: String) {
    fun toJsonMap(): LinkedHashMap<String, JsonValue> = linkedMapOf(
        "transferId" to JsonValue.Str(transferId),
        "fileSize" to JsonValue.Num(fileSize),
        "sha256Hex" to JsonValue.Str(sha256Hex)
    )

    companion object {
        fun parse(map: Map<String, JsonValue>): PayloadResult<TransferCompletePayload> {
            val r = FieldReader(map, "TRANSFER_COMPLETE")
            val transferId = r.uuid("transferId")
            val fileSize = r.int("fileSize", 0, Long.MAX_VALUE)
            val sha = sha256Field(r, "TRANSFER_COMPLETE", "sha256Hex")
            val err = r.error()
            if (err != null || transferId == null || fileSize == null || sha == null ||
                !SHA256_RE.matches(sha)
            ) {
                return PayloadResult.Failure(err ?: "TRANSFER_COMPLETE.sha256Hex: must be 64 lowercase hex chars")
            }
            return PayloadResult.Success(TransferCompletePayload(transferId, fileSize, sha))
        }
    }
}

data class VerifyRequestPayload(val transferId: String, val sha256Hex: String) {
    fun toJsonMap(): LinkedHashMap<String, JsonValue> = linkedMapOf(
        "transferId" to JsonValue.Str(transferId),
        "sha256Hex" to JsonValue.Str(sha256Hex)
    )

    companion object {
        fun parse(map: Map<String, JsonValue>): PayloadResult<VerifyRequestPayload> {
            val r = FieldReader(map, "VERIFY_REQUEST")
            val transferId = r.uuid("transferId")
            val sha = sha256Field(r, "VERIFY_REQUEST", "sha256Hex")
            val err = r.error()
            if (err != null || transferId == null || sha == null || !SHA256_RE.matches(sha)) {
                return PayloadResult.Failure(err ?: "VERIFY_REQUEST.sha256Hex: must be 64 lowercase hex chars")
            }
            return PayloadResult.Success(VerifyRequestPayload(transferId, sha))
        }
    }
}

data class VerifyResultPayload(val transferId: String, val match: Boolean, val sha256Hex: String) {
    fun toJsonMap(): LinkedHashMap<String, JsonValue> = linkedMapOf(
        "transferId" to JsonValue.Str(transferId),
        "match" to JsonValue.Bool(match),
        "sha256Hex" to JsonValue.Str(sha256Hex)
    )

    companion object {
        fun parse(map: Map<String, JsonValue>): PayloadResult<VerifyResultPayload> {
            val r = FieldReader(map, "VERIFY_RESULT")
            val transferId = r.uuid("transferId")
            val match = r.bool("match")
            val sha = sha256Field(r, "VERIFY_RESULT", "sha256Hex")
            val err = r.error()
            if (err != null || transferId == null || match == null || sha == null ||
                !SHA256_RE.matches(sha)
            ) {
                return PayloadResult.Failure(err ?: "VERIFY_RESULT.sha256Hex: must be 64 lowercase hex chars")
            }
            return PayloadResult.Success(VerifyResultPayload(transferId, match, sha))
        }
    }
}

// ---- ERROR ----

data class ErrorPayload(val error: ProtocolError, val refMessageId: String?) {
    fun toJsonMap(): LinkedHashMap<String, JsonValue> {
        val m = linkedMapOf<String, JsonValue>(
            "code" to JsonValue.Str(error.code.wire),
            "message" to JsonValue.Str(error.message)
        )
        if (refMessageId != null) m["refMessageId"] = JsonValue.Str(refMessageId)
        return m
    }

    companion object {
        fun parse(map: Map<String, JsonValue>): PayloadResult<ErrorPayload> {
            val r = FieldReader(map, "ERROR")
            val codeWire = r.string("code", 1, 64)
            val code = codeWire?.let { ErrorCode.fromWire(it) }
            if (codeWire != null && code == null) {
                return PayloadResult.Failure("ERROR.code: unknown error code '$codeWire'")
            }
            val message = r.string("message", 0, Protocol.MAX_ERROR_MESSAGE_LEN)
            val ref = if (map.containsKey("refMessageId")) r.uuid("refMessageId") else null
            val err = r.error()
            if (err != null || code == null || message == null) {
                return PayloadResult.Failure(err ?: "ERROR: invalid payload")
            }
            if (map.containsKey("refMessageId") && ref == null) {
                return PayloadResult.Failure(err ?: "ERROR: invalid refMessageId")
            }
            return PayloadResult.Success(ErrorPayload(ProtocolError(code, message), ref))
        }
    }
}

// ---- discovery record (PROTOCOL v1.0 §3; behavior is M03) ----

data class DiscoveryRecord(
    val deviceId: String,
    val deviceName: String,
    val deviceType: String,
    val controlPort: Long,
    val capabilities: List<String>
) {
    fun toHelloPayload() = HelloPayload(deviceId, deviceName, deviceType, controlPort, capabilities)

    companion object {
        fun parse(map: Map<String, JsonValue>): PayloadResult<DiscoveryRecord> {
            return when (val h = HelloPayload.parse(map)) {
                is PayloadResult.Success -> PayloadResult.Success(
                    DiscoveryRecord(
                        h.value.deviceId, h.value.deviceName, h.value.deviceType,
                        h.value.controlPort, h.value.capabilities
                    )
                )
                is PayloadResult.Failure -> h
            }
        }
    }
}
