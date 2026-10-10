package com.localdrop.network

import java.util.concurrent.ConcurrentHashMap

// M04: tracks the independent control connections of one instance
// (one entry per peer). A failed connection never affects the others:
// each entry owns its socket/threads and closes on its own. The usual key
// is the peer deviceId (known after handshake); ControlServer uses the
// connection id until then. No business logic here.
class ControlConnections {
    private val entries = ConcurrentHashMap<String, ControlConnection>()

    fun add(key: String, connection: ControlConnection) {
        entries[key] = connection
    }

    fun get(key: String): ControlConnection? = entries[key]

    fun remove(key: String): ControlConnection? = entries.remove(key)

    fun remove(connection: ControlConnection): Boolean = entries.values.remove(connection)

    fun rekey(oldKey: String, newKey: String) {
        val existing = entries.remove(oldKey) ?: return
        entries[newKey] = existing
    }

    fun keys(): Set<String> = entries.keys.toSet()

    fun snapshot(): List<ControlConnection> = entries.values.toList()

    fun size(): Int = entries.size

    fun closeAll() {
        for (connection in entries.values.toList()) {
            try {
                connection.disconnect()
            } catch (e: Exception) {
                // Best effort per connection; keep closing the rest.
            }
        }
        entries.clear()
    }
}
