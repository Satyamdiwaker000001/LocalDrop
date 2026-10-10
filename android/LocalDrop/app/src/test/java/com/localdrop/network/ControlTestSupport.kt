package com.localdrop.network

import com.localdrop.discovery.DeviceInfo
import com.localdrop.protocol.ErrorCode
import com.localdrop.protocol.LocalDropMessage
import com.localdrop.protocol.MessageType
import com.localdrop.protocol.ProtocolCodec
import com.localdrop.protocol.ProtocolError
import com.localdrop.protocol.TypedPayload
import com.localdrop.protocol.CancelPayload
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

// M04 test support: loopback-only helpers. No Internet, no hardware.
object ControlTestSupport {
    val deviceA = DeviceInfo("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "PhoneA", "android")
    val deviceB = DeviceInfo("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", "PcB", "windows")

    fun pauseMessage(transferId: String): LocalDropMessage = LocalDropMessage(
        type = MessageType.PAUSE,
        messageId = ProtocolCodec.newMessageId(),
        correlationId = null,
        sessionId = null,
        transferId = transferId,
        payload = TypedPayload.Pause(com.localdrop.protocol.TransferIdOnlyPayload(transferId))
    )

    fun errorReply(to: LocalDropMessage, code: ErrorCode, text: String): LocalDropMessage =
        LocalDropMessage(
            type = MessageType.ERROR,
            messageId = ProtocolCodec.newMessageId(),
            correlationId = to.messageId,
            sessionId = null,
            transferId = null,
            payload = TypedPayload.Error(
                com.localdrop.protocol.ErrorPayload(ProtocolError(code, text), to.messageId)
            )
        )

    fun cancelMessage(transferId: String): LocalDropMessage = LocalDropMessage(
        type = MessageType.CANCEL,
        messageId = ProtocolCodec.newMessageId(),
        correlationId = null,
        sessionId = null,
        transferId = transferId,
        payload = TypedPayload.Cancel(CancelPayload(transferId, null))
    )

    data class CloseEvent(val reason: CloseReason, val detail: String)

    class Capture : ControlChannelListener {
        val messages = LinkedBlockingQueue<LocalDropMessage>()
        val closes = LinkedBlockingQueue<CloseEvent>()
        override fun onMessage(connection: ControlConnection, message: LocalDropMessage) {
            messages.offer(message)
        }
        override fun onClosed(connection: ControlConnection, reason: CloseReason, detail: String) {
            closes.offer(CloseEvent(reason, detail))
        }
        fun awaitMessage(timeoutMs: Long = 5000): LocalDropMessage =
            messages.poll(timeoutMs, TimeUnit.MILLISECONDS)
                ?: throw AssertionError("timed out waiting for message")
        fun awaitClose(timeoutMs: Long = 5000): CloseEvent =
            closes.poll(timeoutMs, TimeUnit.MILLISECONDS)
                ?: throw AssertionError("timed out waiting for close")
    }

    fun waitFor(description: String, timeoutMs: Long = 8000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(50)
        }
        throw AssertionError("timed out waiting for: $description")
    }

    // Reads one M02 frame (prefix + body) from a raw stream. Null on timeout.
    fun readRawFrame(input: java.io.InputStream, timeoutMs: Int = 5000): ByteArray? {
        val prefix = ByteArray(4)
        var got = 0
        val deadline = System.currentTimeMillis() + timeoutMs
        while (got < 4) {
            if (System.currentTimeMillis() > deadline) return null
            val n = input.read(prefix, got, 4 - got)
            if (n < 0) return null
            got += n
        }
        val n = ((prefix[0].toInt() and 0xFF) shl 24) or
            ((prefix[1].toInt() and 0xFF) shl 16) or
            ((prefix[2].toInt() and 0xFF) shl 8) or
            (prefix[3].toInt() and 0xFF)
        if (n <= 0 || n > 2 * 1024 * 1024) return null
        val body = ByteArray(n)
        got = 0
        while (got < n) {
            if (System.currentTimeMillis() > deadline) return null
            val r = input.read(body, got, n - got)
            if (r < 0) return null
            got += r
        }
        return body
    }

    // Performs the server side of the M04 handshake over a raw socket:
    // reads HELLO, replies HELLO + correlated HELLO_ACK. Returns the
    // client's HELLO message, or null on timeout/failure.
    fun rawHandshake(socket: Socket, local: DeviceInfo): LocalDropMessage? {
        socket.soTimeout = 5000
        val input = socket.getInputStream()
        val helloBytes = readRawFrame(input) ?: return null
        val outcome = ProtocolCodec.decodeUtf8(helloBytes)
        if (outcome !is com.localdrop.protocol.DecodeOutcome.Success) return null
        val hello = outcome.message
        if (hello.type != MessageType.HELLO) return null
        val out = socket.getOutputStream()
        val myHello = LocalDropMessage(
            type = MessageType.HELLO,
            messageId = ProtocolCodec.newMessageId(),
            correlationId = null,
            sessionId = null,
            transferId = null,
            payload = TypedPayload.Hello(
                com.localdrop.protocol.HelloPayload(
                    local.deviceId, local.deviceName, local.deviceType,
                    com.localdrop.core.AppConfig.CONTROL_TCP_PORT.toLong(), emptyList()
                )
            )
        )
        val myHelloBytes = com.localdrop.protocol.FrameCodec.encodeMessage(myHello)
        out.write(myHelloBytes)
        val ack = LocalDropMessage(
            type = MessageType.HELLO_ACK,
            messageId = ProtocolCodec.newMessageId(),
            correlationId = hello.messageId,
            sessionId = null,
            transferId = null,
            payload = TypedPayload.HelloAck(
                com.localdrop.protocol.HelloAckPayload(
                    local.deviceId, local.deviceName, local.deviceType, true
                )
            )
        )
        out.write(com.localdrop.protocol.FrameCodec.encodeMessage(ack))
        out.flush()
        return hello
    }
}
