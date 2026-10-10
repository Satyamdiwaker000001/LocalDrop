package com.localdrop.transfer

import com.localdrop.core.AppConfig
import com.localdrop.discovery.DeviceInfo
import com.localdrop.discovery.DiscoveredPeer
import com.localdrop.discovery.LanDiscovery
import com.localdrop.network.ControlServer
import com.localdrop.security.AuthManager
import com.localdrop.security.AuthorizedSessionStore
import com.localdrop.security.ReceiverTransaction
import com.localdrop.security.RecordingSecurityLogger
import com.localdrop.security.SecurityLogger
import com.localdrop.security.DeviceIdentityStore
import com.localdrop.security.TransactionAuthorizer
import com.localdrop.security.TransactionManager
import java.io.File
import java.net.InetAddress

// Verification audit F5: minimum discovery-to-receive integration on the
// Android (receiver) side. Hosts the EXISTING components — M03 discovery
// (announcing the LIVE control port) + M04 control server + M05 auth +
// transaction manager + M07 ReceiverEngine — behind one lifecycle, so the
// app can run a receive mode the Windows TransferCoordinator can discover
// and dial with no manual address entry. No new protocol, no new discovery,
// no UI redesign. Transaction codes are created here for the receiver user
// to display; the sender relays the typed code (M05 design).
class ReceiveCoordinator(
    private val local: DeviceInfo,
    private val store: DeviceIdentityStore,
    private val logger: SecurityLogger = RecordingSecurityLogger(),
    private val destDir: File,
    private val checkpoints: com.localdrop.storage.CheckpointDao? = null,
    private val controlPort: Int = AppConfig.CONTROL_TCP_PORT,
    private val dataPort: Int = AppConfig.DATA_TCP_PORT,
    private val discoveryPort: Int = AppConfig.DISCOVERY_UDP_PORT,
    private val broadcastAddress: InetAddress = InetAddress.getByName("255.255.255.255"),
    // Target port for announcements (null = discovery listen port, the
    // production symmetric case). Tests cross-target over loopback.
    private val announcePort: Int? = null
) {
    val sessions = AuthorizedSessionStore()
    val transactions = TransactionManager(logger = logger)
    val serverAuth = AuthManager(local, store, logger)
    val engine: ReceiverEngine
    val server: ControlServer
    val discovery: LanDiscovery

    init {
        engine = ReceiverEngine(local, serverAuth, sessions, destDir, checkpoints, logger = logger, dataPort = dataPort)
        val handler = engine.createHandler()
        val authorizer = TransactionAuthorizer(
            serverAuth, transactions, sessions, logger, local.deviceId, handler)
        serverAuth.inner = authorizer
        server = ControlServer(local, port = controlPort, listenerFactory = { serverAuth })
        discovery = LanDiscovery(local, port = discoveryPort, broadcastAddress = broadcastAddress,
            announcePort = announcePort ?: discoveryPort)
    }

    // Starts control + data listeners, publishes the BOUND control port in
    // announcements, then starts discovery. Idempotent-ish: second start
    // while running is a no-op via the underlying components.
    fun start() {
        server.start()
        engine.start()
        discovery.controlPort = server.localPort().toLong()
        discovery.start()
    }

    fun stop() {
        try { discovery.stop() } catch (e: Exception) { }
        try { engine.stop() } catch (e: Exception) { }
        try { server.stop() } catch (e: Exception) { }
    }

    // Peers currently seen (a sender UI observes this; users pick a device).
    fun snapshot(): List<DiscoveredPeer> = discovery.snapshot()

    // Opens a receiver transaction; the UI displays `code` for the sender
    // user to type (short-lived, single-use — M05 rules unchanged).
    fun createTransaction(): ReceiverTransaction = transactions.createTransaction()

    fun waitForOutcome(transferId: String, timeoutMs: Long): TransferOutcome? =
        engine.waitForOutcome(transferId, timeoutMs)

    fun waitForAnyOutcome(timeoutMs: Long): TransferOutcome? =
        engine.waitForAnyOutcome(timeoutMs)
}
