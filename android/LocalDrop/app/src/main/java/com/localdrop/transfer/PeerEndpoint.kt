package com.localdrop.transfer

import com.localdrop.discovery.DiscoveredPeer

// Verification audit F4/F5: resolves a discovered peer to a dialable control
// endpoint with NO manual IP/port entry (shared logic with the Windows
// PeerEndpointResolver). Host = recorded UDP source address; port = the
// peer's own HELLO controlPort. Null when either is unknown (caller fails
// cleanly — never dials a guess).
//
// Security note: the endpoint is a DIAL HINT only. Identity is the M05
// ECDSA-verified deviceId and authorization is the consumed transaction
// code; a hostile host:port either fails authentication or is rejected.
// Same LAN does not imply trust.
object PeerEndpointResolver {
    data class Endpoint(val host: String, val port: Int)

    fun resolve(peer: DiscoveredPeer): Endpoint? {
        val host = peer.lastSeenHost?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val port = peer.record.controlPort
        if (port < 1 || port > 65535) return null
        return Endpoint(host, port.toInt())
    }
}
