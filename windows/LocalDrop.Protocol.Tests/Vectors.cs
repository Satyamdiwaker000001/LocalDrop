// M02: shared cross-platform test vectors. Byte-identical literals to
// test-assets/protocol-vectors/v1/*.json (no trailing newline) and to the
// Android suite's literals. If these drift, cross-platform compatibility
// is broken.
namespace LocalDrop.Protocol.Tests;

internal static class Vectors
{
    internal const string AndroidDevice = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
    internal const string WindowsDevice = "cccccccc-cccc-4ccc-8ccc-cccccccccccc";
    internal const string Session = "22222222-2222-4222-8222-222222222222";
    internal const string Transfer = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";
    internal const string RequestMsg = "33333333-3333-4333-8333-333333333333";
    internal const string Sha256Empty =
        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    internal const string Hello =
        "{\"protocol\":\"localdrop\",\"protocolVersion\":1,\"type\":\"HELLO\"," +
        "\"messageId\":\"11111111-1111-4111-8111-111111111111\"," +
        "\"payload\":{\"deviceId\":\"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa\"," +
        "\"deviceName\":\"Pixel 8\",\"deviceType\":\"android\"," +
        "\"controlPort\":0,\"capabilities\":[\"transfer\"]}}";

    internal const string TransferRequest =
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
        "\"resumeOffset\":0}}";

    internal const string TransferAccept =
        "{\"protocol\":\"localdrop\",\"protocolVersion\":1,\"type\":\"TRANSFER_ACCEPT\"," +
        "\"messageId\":\"55555555-5555-4555-8555-555555555555\"," +
        "\"correlationId\":\"33333333-3333-4333-8333-333333333333\"," +
        "\"sessionId\":\"22222222-2222-4222-8222-222222222222\"," +
        "\"transferId\":\"bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb\"," +
        "\"payload\":{\"transferId\":\"bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb\"," +
        "\"resumeOffset\":0}}";

    internal const string Error =
        "{\"protocol\":\"localdrop\",\"protocolVersion\":1,\"type\":\"ERROR\"," +
        "\"messageId\":\"44444444-4444-4444-8444-444444444444\"," +
        "\"payload\":{\"code\":\"TRANSFER_REJECTED\"," +
        "\"message\":\"Receiver declined the transfer\"}}";

    internal const string Pause =
        "{\"protocol\":\"localdrop\",\"protocolVersion\":1,\"type\":\"PAUSE\"," +
        "\"messageId\":\"66666666-6666-4666-8666-666666666666\"," +
        "\"transferId\":\"bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb\"," +
        "\"payload\":{\"transferId\":\"bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb\"}}";

    internal static LocalDropMessage HelloMessage() => new(
        MessageType.HELLO,
        "11111111-1111-4111-8111-111111111111",
        null, null, null,
        new TypedPayload.Hello(new HelloPayload(AndroidDevice, "Pixel 8", "android", 0, ["transfer"])));

    internal static LocalDropMessage TransferRequestMessage() => new(
        MessageType.TRANSFER_REQUEST,
        RequestMsg, null, Session, Transfer,
        new TypedPayload.TransferRequest(new TransferRequestPayload(new TransferMetadata(
            Transfer, AndroidDevice, WindowsDevice, "file-001", "Backup.zip",
            10737418240L, 8388608L, Sha256Empty, 0))));

    internal static LocalDropMessage TransferAcceptMessage() => new(
        MessageType.TRANSFER_ACCEPT,
        "55555555-5555-4555-8555-555555555555", RequestMsg, Session, Transfer,
        new TypedPayload.TransferAccept(new TransferAcceptPayload(Transfer, 0)));

    internal static LocalDropMessage ErrorMessage() => new(
        MessageType.ERROR,
        "44444444-4444-4444-8444-444444444444", null, null, null,
        new TypedPayload.Error(new ErrorPayload(
            new ProtocolError(ErrorCode.TRANSFER_REJECTED, "Receiver declined the transfer"), null)));

    internal static LocalDropMessage PauseMessage() => new(
        MessageType.PAUSE,
        "66666666-6666-4666-8666-666666666666", null, null, Transfer,
        new TypedPayload.Pause(new TransferIdOnlyPayload(Transfer)));
}
