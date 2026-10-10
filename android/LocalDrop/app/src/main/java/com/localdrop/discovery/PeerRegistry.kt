package com.localdrop.discovery

import com.localdrop.core.AppConfig
import com.localdrop.protocol.DecodeOutcome
import com.localdrop.protocol.DiscoveryRecord
import com.localdrop.protocol.MessageType
import com.localdrop.protocol.ProtocolCodec
import com.localdrop.protocol.TypedPayload

// M03: nearby-peer registry. Pure logic, thread-safe, no sockets.
// Keyed by authoritative device ID (never by IP: hotspot/NAT/multi-interface
// conditions make IP comparison unreliable, M03 §10).
// Expiry is lazy (on snapshot/handle/prune): no timer thread, no timer leak,
// fully deterministic under a fake clock.
class PeerRegistry(
    private val selfDeviceId: String,
    private val clock: DiscoveryClock = SystemDiscoveryClock,
    private val peerTimeoutMs: Long = AppConfig.DISCOVERY_PEER_TIMEOUT_MS
) {
    sealed interface HandleResult {
        // Packet ignored: oversized, malformed, unsupported version/type,
        // invalid record, non-HELLO message, or self announcement.
        data object Ignored : HandleResult
        // First sighting of this device ID.
        data object Discovered : HandleResult
        // Repeat announcement: existing record updated, expiry refreshed.
        data object Refreshed : HandleResult
    }

    private val peers = LinkedHashMap<String, DiscoveredPeer>()

    // Processes one received UDP datagram. Never throws on peer input:
    // every invalid shape maps to Ignored (listener keeps running).
    // senderHost is the datagram source address (dial hint, stored on the
    // peer; null when unavailable). Identity keying stays deviceId-only.
    @Synchronized
    fun handleDatagram(bytes: ByteArray, length: Int, senderHost: String? = null): HandleResult {
        if (length <= 0 || length > AppConfig.DISCOVERY_MAX_PACKET_BYTES) return HandleResult.Ignored
        val slice = bytes.copyOfRange(0, length)
        val outcome = try {
            ProtocolCodec.decodeUtf8(slice)
        } catch (e: Exception) {
            return HandleResult.Ignored
        }
        if (outcome !is DecodeOutcome.Success) return HandleResult.Ignored
        val message = outcome.message
        if (message.type != MessageType.HELLO) return HandleResult.Ignored
        val hello = message.payload as? TypedPayload.Hello ?: return HandleResult.Ignored
        val record = DiscoveryRecord(
            deviceId = hello.v.deviceId,
            deviceName = hello.v.deviceName,
            deviceType = hello.v.deviceType,
            controlPort = hello.v.controlPort,
            capabilities = hello.v.capabilities
        )
        if (record.deviceId == selfDeviceId) return HandleResult.Ignored
        val now = clock.nowMillis()
        // Blank/oversized hosts carry no dial value; normalize to null.
        // (Length cap: an IP literal never approaches this; anything larger
        // is garbage, not an address.)
        val host = senderHost?.trim()?.takeIf { it.isNotEmpty() && it.length <= 256 }
        val existing = peers[record.deviceId]
        return if (existing == null) {
            peers[record.deviceId] = DiscoveredPeer(record, now, now, host)
            HandleResult.Discovered
        } else {
            // Preserve the last KNOWN host: a refresh without source info
            // (e.g. unit-test datagrams) must not erase a real address.
            peers[record.deviceId] = existing.copy(
                record = record,
                lastSeenMillis = now,
                lastSeenHost = host ?: existing.lastSeenHost
            )
            HandleResult.Refreshed
        }
    }

    // Active peers only (prunes expired first). Never exposes self: self is
    // filtered at insert, so it can never appear here.
    @Synchronized
    fun snapshot(): List<DiscoveredPeer> {
        prune()
        return peers.values.toList()
    }

    @Synchronized
    fun prune() {
        val now = clock.nowMillis()
        val it = peers.entries.iterator()
        while (it.hasNext()) {
            if (now - it.next().value.lastSeenMillis >= peerTimeoutMs) it.remove()
        }
    }

    @Synchronized
    fun clear() {
        peers.clear()
    }

    @Synchronized
    fun size(): Int = peers.size
}
