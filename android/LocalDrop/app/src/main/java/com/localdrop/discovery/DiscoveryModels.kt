package com.localdrop.discovery

import com.localdrop.protocol.DiscoveryRecord
import com.localdrop.protocol.Protocol
import com.localdrop.protocol.Uuids

// M03: discovery models. Pure Kotlin, no Android imports (unit-testable on
// the JVM). A discovered peer is NOT a trusted peer (same LAN != trusted,
// M05 owns trust). No sockets, no TCP, no pairing, no file access here.

// Local identity this instance announces. Supplied by the application layer;
// validated with the same bounds the M02 contract enforces on peers.
data class DeviceInfo(
    val deviceId: String,
    val deviceName: String,
    val deviceType: String,
    val capabilities: List<String> = emptyList()
) {
    init {
        require(Uuids.isValid(deviceId)) { "deviceId: invalid UUID syntax" }
        require(deviceName.length in 1..Protocol.MAX_DEVICE_NAME) { "deviceName: out of range" }
        require(deviceType == "android" || deviceType == "windows") { "deviceType: must be android|windows" }
        require(capabilities.size <= Protocol.MAX_CAPABILITIES &&
            capabilities.all { it.length in 1..Protocol.MAX_CAPABILITY_LEN }) {
            "capabilities: out of range"
        }
    }
}

// One nearby peer: the validated M02 DiscoveryRecord plus discovery
// lifecycle timestamps. State vocabulary (§16) maps as: absent = Unknown,
// first insert = Discovered, later update = Refreshed, pruned = Expired.
// lastSeenHost is the UDP source address of the most recent announcement
// (dial hint for the M04/M07 control connection; verification audit F4).
// Keying stays deviceId-only (M03 §10): the host is NEVER an identity signal
// (hotspot/NAT/multi-interface conditions make IP comparison unreliable) and
// may be null when the source address is unavailable.
data class DiscoveredPeer(
    val record: DiscoveryRecord,
    val firstSeenMillis: Long,
    val lastSeenMillis: Long,
    val lastSeenHost: String? = null
)

// Clock abstraction: production uses wall time; tests use a fake clock for
// deterministic expiry tests without sleeping.
interface DiscoveryClock {
    fun nowMillis(): Long
}

object SystemDiscoveryClock : DiscoveryClock {
    override fun nowMillis(): Long = System.currentTimeMillis()
}
