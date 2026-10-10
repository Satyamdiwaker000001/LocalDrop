package com.localdrop.transfer

import com.localdrop.core.AppConfig
import com.localdrop.discovery.DiscoveredPeer
import com.localdrop.discovery.DeviceInfo
import com.localdrop.discovery.LanDiscovery
import com.localdrop.network.ControlConnection
import com.localdrop.protocol.ErrorCode
import com.localdrop.security.AuthManager
import com.localdrop.security.AuthOutcome
import com.localdrop.security.DeviceIdentityStore
import com.localdrop.security.PairSubmitOutcome
import com.localdrop.security.SecurityLogger
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

// M07 UI closure: minimum discovery-to-transfer integration on the Android
// (sender) side. Mirrors the Windows TransferCoordinator over the same
// existing components — M03 snapshot -> endpoint resolution (no manual IP)
// -> M04 control dial -> M05 mutual auth -> transaction-code authorization
// -> M06 SenderEngine — without new protocols, new discovery, or UI
// redesign. The 6-digit code is relayed by the users (receiver displays,
// sender types), exactly as M05 designed; everything else is resolved
// programmatically. All calls block; invoke off the UI thread.
class SendCoordinator(
    private val local: DeviceInfo,
    private val store: DeviceIdentityStore,
    private val logger: SecurityLogger,
    private val discovery: LanDiscovery,
    private val dataPort: Int = AppConfig.DATA_TCP_PORT,
    private val authTimeoutMs: Long = 30000L,
    private val codeTimeoutMs: Long = 30000L,
    private val commitLingerMs: Long = 1000L
) {
    // Peers the discovery layer currently sees (UI observes this; the
    // coordinator dials from it — the user picks a device, never an IP).
    fun nearbyPeers(): List<DiscoveredPeer> = discovery.snapshot()

    fun start() = discovery.start()

    fun stop() = discovery.stop()

    // Full sender flow for one file to a DISCOVERED peer. Returns a terminal
    // TransferOutcome (never throws on peer/network failure; cooperative
    // cancellation maps to Cancelled per the engine contract).
    fun sendFile(
        peerDeviceId: String,
        source: TransferSource,
        code: String,
        isCancelled: () -> Boolean = { false },
        onProgress: (TransferProgress) -> Unit = {}
    ): TransferOutcome {
        if (isCancelled()) return TransferOutcome.Cancelled(null, 0L)
        val peer = discovery.snapshot().firstOrNull { it.record.deviceId == peerDeviceId }
            ?: return TransferOutcome.Failed(null, ErrorCode.INTERNAL_ERROR,
                "Peer not discovered; select a nearby device.")
        val endpoint = PeerEndpointResolver.resolve(peer)
            ?: return TransferOutcome.Failed(null, ErrorCode.INTERNAL_ERROR,
                "Peer has no dialable endpoint yet; wait for discovery.")
        val mailbox = TransferMailbox()
        val auth = AuthManager(local, store, logger)
        auth.inner = mailbox
        val control = ControlConnection(local, auth)
        if (!control.connect(endpoint.host, endpoint.port)) {
            return if (isCancelled()) TransferOutcome.Cancelled(null, 0L)
            else TransferOutcome.Failed(null, ErrorCode.NETWORK_LOST, "Control connection failed.")
        }
        try {
            val authOutcome = try {
                auth.beginAuth(control).get(authTimeoutMs, TimeUnit.MILLISECONDS)
            } catch (e: TimeoutException) {
                return TransferOutcome.Failed(null, ErrorCode.TIMEOUT, "Authentication timed out.")
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return TransferOutcome.Cancelled(null, 0L)
            } catch (e: Exception) {
                return TransferOutcome.Failed(null, ErrorCode.AUTH_FAILED, "Peer authentication failed.")
            }
            if (isCancelled()) return TransferOutcome.Cancelled(null, 0L)
            if (authOutcome !is AuthOutcome.Authenticated) {
                return TransferOutcome.Failed(null, ErrorCode.AUTH_FAILED, "Peer authentication failed.")
            }
            when (val pair = auth.senderSubmitCode(control, code, codeTimeoutMs)) {
                is PairSubmitOutcome.Accepted -> Unit
                is PairSubmitOutcome.Rejected -> return TransferOutcome.Failed(null,
                    ErrorCode.TRANSFER_REJECTED, "Transfer code rejected: ${pair.reason}")
                is PairSubmitOutcome.Failed -> return TransferOutcome.Failed(null,
                    ErrorCode.TIMEOUT, "Transfer code submission failed: ${pair.reason}")
            }
            val sender = SenderEngine(
                localDeviceId = local.deviceId, control = control, mailbox = mailbox,
                dataPort = dataPort)
            val outcome = sender.send(
                SenderEngine.SendRequest(
                    source = source, receiverDeviceId = peerDeviceId,
                    dataHost = endpoint.host, isCancelled = isCancelled, onProgress = onProgress))
            if (outcome is TransferOutcome.TransportComplete) {
                // Commit grace (same discipline as the Windows coordinator
                // linger): TRANSFER_COMPLETE travels on control while bytes
                // travel on data; hold the channel briefly so teardown cannot
                // race the receiver's commit. The receiver stays
                // authoritative for completion.
                try {
                    Thread.sleep(commitLingerMs)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    // Outcome already decided; teardown grace only.
                }
            }
            return outcome
        } finally {
            try { control.disconnect() } catch (e: Exception) { }
        }
    }
}
