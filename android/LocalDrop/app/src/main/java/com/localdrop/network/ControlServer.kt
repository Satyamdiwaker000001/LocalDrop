package com.localdrop.network

import com.localdrop.core.AppConfig
import com.localdrop.discovery.DeviceInfo
import java.net.ServerSocket
import java.net.Socket

// M04: inbound TCP control listener (one per application instance).
// Accepts sockets and hands each to an independent ControlConnection, so one
// failed peer never affects unrelated peers (M04 §16). Owns no business
// logic; the connection factory wires per-connection listeners.
class ControlServer(
    private val local: DeviceInfo,
    private val port: Int = AppConfig.CONTROL_TCP_PORT,
    private val handshakeTimeoutMs: Long = AppConfig.CONTROL_HANDSHAKE_TIMEOUT_MS.toLong(),
    private val listenerFactory: (() -> ControlChannelListener?)? = null
) {
    val connections = ControlConnections()

    @Volatile private var running = false
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null

    @Synchronized
    fun start() {
        if (running) return
        val server = ServerSocket()
        server.reuseAddress = true
        // NOTE (M03 lesson): no apply{} receiver here, so no member shadowing.
        server.bind(java.net.InetSocketAddress(port))
        running = true
        serverSocket = server
        val thread = Thread(::acceptLoop, "localdrop-control-accept")
        thread.isDaemon = true
        acceptThread = thread
        thread.start()
    }

    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            // Best effort during shutdown.
        }
        try {
            acceptThread?.join(2000)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        serverSocket = null
        acceptThread = null
        connections.closeAll()
    }

    fun isRunning(): Boolean = running

    fun localPort(): Int = serverSocket?.localPort ?: -1

    fun snapshot(): List<ControlConnection> = connections.snapshot()

    private fun acceptLoop() {
        val server = serverSocket ?: return
        while (running) {
            val socket: Socket = try {
                server.accept()
            } catch (e: java.net.SocketException) {
                return // closed by stop()
            } catch (e: java.io.IOException) {
                if (!running) return else continue
            }
            val worker = Thread({
                val userListener = listenerFactory?.invoke()
                val tracking = object : ControlChannelListener {
                    override fun onMessage(
                        c: ControlConnection,
                        m: com.localdrop.protocol.LocalDropMessage
                    ) {
                        userListener?.onMessage(c, m)
                    }
                    override fun onClosed(c: ControlConnection, r: CloseReason, d: String) {
                        try {
                            userListener?.onClosed(c, r, d)
                        } finally {
                            connections.remove(c.id)
                        }
                    }
                }
                val connection = ControlConnection(local, tracking, handshakeTimeoutMs = handshakeTimeoutMs)
                connections.add(connection.id, connection)
                connection.accept(socket)
                if (!connection.isReady()) connections.remove(connection.id)
            }, "localdrop-control-inbound")
            worker.isDaemon = true
            worker.start()
        }
    }
}
