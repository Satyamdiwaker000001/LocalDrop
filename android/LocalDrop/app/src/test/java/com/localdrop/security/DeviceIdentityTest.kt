package com.localdrop.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

// M05 tests 1-2: device identity creation + persistence/reload semantics.
// Uses the software store (AndroidKeyStore is unavailable on the JVM; it
// shares curve/hash/encodings and is covered by review + on-device later).
class DeviceIdentityTest {

    private val uuidRe = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")

    @Test
    fun `identity creation yields bound UUID and SPKI`() {
        val store = SoftwareDeviceIdentityStore()
        val id = store.identity()
        assertTrue("deviceId must be UUIDv4-shaped: ${id.deviceId}", uuidRe.matches(id.deviceId))
        assertTrue(id.publicKeySpki.isNotEmpty())
        // Independent parse check, not just our own formatter.
        UUID.fromString(id.deviceId)
    }

    @Test
    fun `identity is stable across reloads from the same store`() {
        val store = SoftwareDeviceIdentityStore()
        assertEquals(store.identity(), store.identity())
    }

    @Test
    fun `distinct installations get distinct identities`() {
        val a = SoftwareDeviceIdentityStore().identity()
        val b = SoftwareDeviceIdentityStore().identity()
        assertNotEquals(a.deviceId, b.deviceId)
        assertFalse(a.publicKeySpki.contentEquals(b.publicKeySpki))
    }

    @Test
    fun `deviceId derivation is deterministic per key`() {
        val store = SoftwareDeviceIdentityStore()
        val spki = store.identity().publicKeySpki
        assertEquals(
            DeviceIdentityCrypto.deriveDeviceId(spki),
            DeviceIdentityCrypto.deriveDeviceId(spki)
        )
        assertEquals(store.identity().deviceId, DeviceIdentityCrypto.deriveDeviceId(spki))
    }

    @Test
    fun `sign verify round trip succeeds, tampered data fails`() {
        val store = SoftwareDeviceIdentityStore()
        val data = "transcript-bytes".toByteArray()
        val sig = store.sign(data)
        assertEquals(64, sig.size)
        assertTrue(store.verify(data, sig))
        val tampered = data.copyOf().also { it[0] = (it[0].toInt() xor 0xFF).toByte() }
        assertFalse(store.verify(tampered, sig))
    }

    @Test
    fun `wrong key cannot verify, flipped signature fails`() {
        val a = SoftwareDeviceIdentityStore()
        val b = SoftwareDeviceIdentityStore()
        val data = "other".toByteArray()
        val sigFromA = a.sign(data)
        // A's signature does NOT verify under B's key.
        assertFalse(b.verify(data, sigFromA, b.identity().publicKeySpki))
        val flipped = sigFromA.copyOf().also { it[63] = (it[63].toInt() xor 0x01).toByte() }
        assertFalse(a.verify(data, flipped))
    }

    @Test
    fun `DER raw conversion round trips`() {
        val store = SoftwareDeviceIdentityStore()
        val raw = store.sign("x".toByteArray())
        val der = DeviceIdentityCrypto.rawToDer(raw)
        assertEquals(raw.toList(), DeviceIdentityCrypto.derToRaw(der).toList())
    }
}
