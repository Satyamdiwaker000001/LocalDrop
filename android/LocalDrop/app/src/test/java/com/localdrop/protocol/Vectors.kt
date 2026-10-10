package com.localdrop.protocol

// M02: shared cross-platform test vectors. Byte-identical literals to
// test-assets/protocol-vectors/v1/*.json (no trailing newline) and to the
// literals embedded in the Windows test suite. If these drift, cross-
// platform compatibility is broken.
object Vectors {
    const val ANDROID_DEVICE = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    const val WINDOWS_DEVICE = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
    const val SESSION = "22222222-2222-4222-8222-222222222222"
    const val TRANSFER = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
    const val REQUEST_MSG = "33333333-3333-4333-8333-333333333333"
    const val SHA256_EMPTY =
        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

    const val HELLO =
        "{\"protocol\":\"localdrop\",\"protocolVersion\":1,\"type\":\"HELLO\"," +
            "\"messageId\":\"11111111-1111-4111-8111-111111111111\"," +
            "\"payload\":{\"deviceId\":\"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa\"," +
            "\"deviceName\":\"Pixel 8\",\"deviceType\":\"android\"," +
            "\"controlPort\":0,\"capabilities\":[\"transfer\"]}}"

    const val TRANSFER_REQUEST =
        "{\"protocol\":\"localdrop\",\"protocolVersion\":1,\"type\":\"TRANSFER_REQUEST\"," +
            "\"messageId\":\"33333333-3333-4333-8333-333333333333\"," +
            "\"sessionId\":\"22222222-2222-4222-8222-222222222222\"," +
            "\"transferId\":\"bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb\"," +
            "\"payload\":{\"transferId\":\"bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb\"," +
            "\"senderDeviceId\":\"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa\"," +
            "\"receiverDeviceId\":\"cccccccc-cccc-4ccc-8ccc-cccccccccccc\"," +
            "\"fileId\":\"file-001\",\"fileName\":\"Backup.zip\"," +
            "\"fileSize\":10737418240,\"checkpointSize\":8388608," +
            "\"sha256Hex\":\"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855\"," +
            "\"resumeOffset\":0}}"

    const val TRANSFER_ACCEPT =
        "{\"protocol\":\"localdrop\",\"protocolVersion\":1,\"type\":\"TRANSFER_ACCEPT\"," +
            "\"messageId\":\"55555555-5555-4555-8555-555555555555\"," +
            "\"correlationId\":\"33333333-3333-4333-8333-333333333333\"," +
            "\"sessionId\":\"22222222-2222-4222-8222-222222222222\"," +
            "\"transferId\":\"bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb\"," +
            "\"payload\":{\"transferId\":\"bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb\"," +
            "\"resumeOffset\":0}}"

    const val ERROR =
        "{\"protocol\":\"localdrop\",\"protocolVersion\":1,\"type\":\"ERROR\"," +
            "\"messageId\":\"44444444-4444-4444-8444-444444444444\"," +
            "\"payload\":{\"code\":\"TRANSFER_REJECTED\"," +
            "\"message\":\"Receiver declined the transfer\"}}"

    const val PAUSE =
        "{\"protocol\":\"localdrop\",\"protocolVersion\":1,\"type\":\"PAUSE\"," +
            "\"messageId\":\"66666666-6666-4666-8666-666666666666\"," +
            "\"transferId\":\"bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb\"," +
            "\"payload\":{\"transferId\":\"bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb\"}}"

    fun helloMessage() = LocalDropMessage(
        type = MessageType.HELLO,
        messageId = "11111111-1111-4111-8111-111111111111",
        correlationId = null,
        sessionId = null,
        transferId = null,
        payload = TypedPayload.Hello(
            HelloPayload(ANDROID_DEVICE, "Pixel 8", "android", 0, listOf("transfer"))
        )
    )

    fun transferRequestMessage() = LocalDropMessage(
        type = MessageType.TRANSFER_REQUEST,
        messageId = REQUEST_MSG,
        correlationId = null,
        sessionId = SESSION,
        transferId = TRANSFER,
        payload = TypedPayload.TransferRequest(
            TransferRequestPayload(
                TransferMetadata(
                    transferId = TRANSFER,
                    senderDeviceId = ANDROID_DEVICE,
                    receiverDeviceId = WINDOWS_DEVICE,
                    fileId = "file-001",
                    fileName = "Backup.zip",
                    fileSize = 10737418240L,
                    checkpointSize = 8388608L,
                    sha256Hex = SHA256_EMPTY,
                    resumeOffset = 0
                )
            )
        )
    )

    fun transferAcceptMessage() = LocalDropMessage(
        type = MessageType.TRANSFER_ACCEPT,
        messageId = "55555555-5555-4555-8555-555555555555",
        correlationId = REQUEST_MSG,
        sessionId = SESSION,
        transferId = TRANSFER,
        payload = TypedPayload.TransferAccept(TransferAcceptPayload(TRANSFER, 0))
    )

    fun errorMessage() = LocalDropMessage(
        type = MessageType.ERROR,
        messageId = "44444444-4444-4444-8444-444444444444",
        correlationId = null,
        sessionId = null,
        transferId = null,
        payload = TypedPayload.Error(
            ErrorPayload(ProtocolError(ErrorCode.TRANSFER_REJECTED, "Receiver declined the transfer"), null)
        )
    )

    fun pauseMessage() = LocalDropMessage(
        type = MessageType.PAUSE,
        messageId = "66666666-6666-4666-8666-666666666666",
        correlationId = null,
        sessionId = null,
        transferId = TRANSFER,
        payload = TypedPayload.Pause(TransferIdOnlyPayload(TRANSFER))
    )
}
