package com.localdrop.security

import java.util.concurrent.ConcurrentHashMap

// M05: the authenticated + authorized session M06 will consume.
// Holds ids and timestamps ONLY: never codes, keys, nonces, or signatures.
data class AuthorizedSession(
    val peerDeviceId: String,
    val transactionId: String,
    val authorizedAtMillis: Long
)

class AuthorizedSessionStore(
    private val clock: com.localdrop.discovery.DiscoveryClock =
        com.localdrop.discovery.SystemDiscoveryClock
) {
    private val sessions = ConcurrentHashMap<Pair<String, String>, AuthorizedSession>()

    fun authorize(peerDeviceId: String, transactionId: String): AuthorizedSession {
        val session = AuthorizedSession(peerDeviceId, transactionId, clock.nowMillis())
        sessions[peerDeviceId to transactionId] = session
        return session
    }

    fun isAuthorized(peerDeviceId: String, transactionId: String): Boolean =
        sessions.containsKey(peerDeviceId to transactionId)

    fun sessionsFor(peerDeviceId: String): List<AuthorizedSession> =
        sessions.values.filter { it.peerDeviceId == peerDeviceId }

    fun snapshot(): List<AuthorizedSession> = sessions.values.toList()

    fun revoke(peerDeviceId: String, transactionId: String): Boolean =
        sessions.remove(peerDeviceId to transactionId) != null

    fun clear() = sessions.clear()

    fun size(): Int = sessions.size
}
