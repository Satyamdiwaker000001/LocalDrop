package com.localdrop.discovery

import com.localdrop.core.AppConfig
import com.localdrop.protocol.HelloPayload
import com.localdrop.protocol.LocalDropMessage
import com.localdrop.protocol.MessageType
import com.localdrop.protocol.ProtocolCodec
import com.localdrop.protocol.TypedPayload
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

// M03: LAN-only UDP broadcast discovery (Complete Spec §5.1).
// One wire protocol shared with Windows: each announcement datagram is the
// UTF-8 canonical JSON of an M02 HELLO envelope (same bytes the control
// channel will carry in M04; datagram boundaries replace framing here).
// Lives OUTSIDE Compose UI: no Activity/Composable owns sockets.
// Lifecycle: start() / stop(), both idempotent. No background service
// (M13 owns that), no TCP, no pairing, no file access.
class LanDiscovery(
    private val local: DeviceInfo,
    private val port: Int = AppConfig.DISCOVERY_UDP_PORT,
    private val announceIntervalMs: Long = AppConfig.DISCOVERY_ANNOUNCE_INTERVAL_MS,
    private val broadcastAddress: InetAddress = InetAddress.getByName("255.255.255.255"),
    private val clock: DiscoveryClock = SystemDiscoveryClock,
    peerTimeoutMs: Long = AppConfig.DISCOVERY_PEER_TIMEOUT_MS,
    // Target port for announcements. Defaults to the listen port (production:
    // symmetric). Tests cross-target two instances over loopback.
    private val announcePort: Int = port,
    // Control-plane TCP port advertised in announcements (M02 HELLO
    // controlPort, range 0..65535). Default 0 preserves the M03 announcement
    // shape; production wiring sets the live port (directly or via
    // ReceiveCoordinator) so dialers learn the real endpoint (verification
    // audit F4 — no manual IP/port). A var so the coordinator can publish
    // the bound port AFTER the control server starts; the announce loop
    // reads it on every iteration (@Volatile for cross-thread visibility).
    @Volatile var controlPort: Long = 0L
) {
    val registry = PeerRegistry(local.deviceId, clock, peerTimeoutMs)

    @Volatile private var running = false
    private var listenerSocket: DatagramSocket? = null
    private var announcerSocket: DatagramSocket? = null
    private var listenerThread: Thread? = null
    private var announcerThread: Thread? = null

    // Idempotent: a second start() while running is a no-op (no duplicate
    // listeners, no duplicate announcement loops).
    @Synchronized
    fun start() {
        if (running) return
        val listener = DatagramSocket(null).apply {
            reuseAddress = true
            // NOTE: must qualify outer port explicitly. Inside apply{}, the
            // bare name `port` resolves to DatagramSocket.getPort() (the
            // unbound socket reports -1), NOT LanDiscovery.port.
            bind(InetSocketAddress(this@LanDiscovery.port))
        }
        val announcer = DatagramSocket().apply { broadcast = true }
        running = true
        listenerSocket = listener
        announcerSocket = announcer
        listenerThread = Thread(::listenLoop, "localdrop-discovery-listen").apply {
            isDaemon = true
            start()
        }
        announcerThread = Thread(::announceLoop, "localdrop-discovery-announce").apply {
            isDaemon = true
            start()
        }
    }

    // Idempotent. Closes sockets (unblocks the receiver immediately),
    // interrupts the announcer sleep, joins both threads. No socket/thread
    // leaks; restart via start() rebinds cleanly.
    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        try {
            listenerSocket?.close()
        } catch (e: Exception) {
            // Best effort during shutdown.
        }
        try {
            announcerSocket?.close()
        } catch (e: Exception) {
            // Best effort during shutdown.
        }
        announcerThread?.interrupt()
        try {
            listenerThread?.join(2000)
            announcerThread?.join(2000)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        listenerSocket = null
        announcerSocket = null
        listenerThread = null
        announcerThread = null
    }

    fun isRunning(): Boolean = running

    // Bound UDP port (useful when constructed with port 0 in tests).
    fun localPort(): Int = listenerSocket?.localPort ?: -1

    // Validated nearby peers (expired pruned). UI observes this, never UDP.
    fun snapshot(): List<DiscoveredPeer> = registry.snapshot()

    private fun listenLoop() {
        val socket = listenerSocket ?: return
        val buf = ByteArray(AppConfig.DISCOVERY_MAX_PACKET_BYTES)
        while (running) {
            try {
                val packet = DatagramPacket(buf, buf.size)
                socket.receive(packet)
                // The source address is the dial hint the registry stores
                // alongside the device-keyed record (audit F4).
                registry.handleDatagram(packet.data, packet.length, packet.address?.hostAddress)
            } catch (e: java.net.SocketException) {
                // Socket closed by stop(): exit quietly. Any other socket
                // error also ends the loop; restart is explicit via start().
                return
            } catch (e: java.io.IOException) {
                // Malformed LAN traffic must not kill discovery: ignore and
                // keep listening while still running.
                if (!running) return
            } catch (e: Exception) {
                if (!running) return
            }
        }
    }

    private fun announceLoop() {
        val socket = announcerSocket ?: return
        while (running) {
            try {
                val bytes = buildAnnouncement(local, controlPort)
                val packet = DatagramPacket(bytes, bytes.size, broadcastAddress, announcePort)
                socket.send(packet)
            } catch (e: Exception) {
                // Send failures (e.g. transient interface loss) must not kill
                // the loop; the next interval retries.
                if (!running) return
            }
            try {
                Thread.sleep(announceIntervalMs)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }

    companion object {
        // One announcement = one HELLO envelope, fresh messageId per send.
        // controlPort 0 = unassigned (M03 default); callers pass the live
        // M04 control port so peers can dial without manual entry (F4).
        // The value is M02-legal (0..65535): no wire change.
        fun buildAnnouncement(local: DeviceInfo, controlPort: Long = 0L): ByteArray {
            val message = LocalDropMessage(
                type = MessageType.HELLO,
                messageId = ProtocolCodec.newMessageId(),
                correlationId = null,
                sessionId = null,
                transferId = null,
                payload = TypedPayload.Hello(
                    HelloPayload(
                        deviceId = local.deviceId,
                        deviceName = local.deviceName,
                        deviceType = local.deviceType,
                        controlPort = controlPort,
                        capabilities = local.capabilities
                    )
                )
            )
            return ProtocolCodec.encodeUtf8(message)
        }
    }
}
