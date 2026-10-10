package com.localdrop.security

import com.localdrop.protocol.PayloadResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// M05: token structure + transcript contract (shared-protocol/authentication.md).
class AuthProtocolTest {

    private val storeA = SoftwareDeviceIdentityStore()
    private val storeB = SoftwareDeviceIdentityStore()

    @Test
    fun `hello token round trips`() {
        val id = storeA.identity()
        val nonce = AuthProtocol.newNonce()
        assertEquals(32, nonce.size)
        val token = AuthProtocol.helloToken(id.deviceId, id.publicKeySpki, nonce)
        val parsed = AuthProtocol.parseHello(token)
        assertTrue(parsed is PayloadResult.Success)
        val hello = (parsed as PayloadResult.Success).value
        assertEquals(id.deviceId, hello.deviceId)
        assertTrue(hello.publicKeySpki.contentEquals(id.publicKeySpki))
        assertTrue(hello.nonce.contentEquals(nonce))
    }

    @Test
    fun `proof token round trips`() {
        val sig = storeA.sign("data".toByteArray())
        val parsed = AuthProtocol.parseProof(AuthProtocol.proofToken(sig))
        assertTrue(parsed is PayloadResult.Success)
        assertTrue((parsed as PayloadResult.Success).value.rawSignature.contentEquals(sig))
    }

    @Test
    fun `malformed tokens are rejected`() {
        assertTrue(AuthProtocol.parseHello("{not json") is PayloadResult.Failure)
        assertTrue(AuthProtocol.parseHello("{}") is PayloadResult.Failure)
        assertTrue(AuthProtocol.parseProof("{}") is PayloadResult.Failure)
        // wrong step discriminator
        val swapped = AuthProtocol.proofToken(ByteArray(64))
        assertTrue(AuthProtocol.parseHello(swapped) is PayloadResult.Failure)
        // bad base64
        val badB64 = "{\"step\":\"auth-hello\",\"deviceId\":\"${storeA.identity().deviceId}\"," +
            "\"publicKey\":\"!!!\",\"nonce\":\"!!!\"}"
        assertTrue(AuthProtocol.parseHello(badB64) is PayloadResult.Failure)
        // short nonce
        val shortNonce = AuthProtocol.helloToken(
            storeA.identity().deviceId, storeA.identity().publicKeySpki, ByteArray(4)
        )
        assertTrue(AuthProtocol.parseHello(shortNonce) is PayloadResult.Failure)
        // short signature
        val shortSig = AuthProtocol.proofToken(ByteArray(10))
        assertTrue(AuthProtocol.parseProof(shortSig) is PayloadResult.Failure)
        // oversized token
        assertTrue(AuthProtocol.parseHello("x".repeat(5000)) is PayloadResult.Failure)
    }

    @Test
    fun `transcript is deterministic and order independent`() {
        val idA = storeA.identity().deviceId
        val idB = storeB.identity().deviceId
        val nA = ByteArray(32) { 0x11 }
        val nB = ByteArray(32) { 0x22 }
        val t1 = AuthProtocol.buildTranscript("m", idA, nA, idB, nB)
        val t2 = AuthProtocol.buildTranscript("m", idA, nA, idB, nB)
        val t3 = AuthProtocol.buildTranscript("m", idB, nB, idA, nA)
        assertTrue(t1.contentEquals(t2))
        assertTrue(t1.contentEquals(t3))
        assertFalse(t1.contentEquals(AuthProtocol.buildTranscript("other", idA, nA, idB, nB)))
        assertFalse(t1.contentEquals(AuthProtocol.buildTranscript("m", idA, nB, idB, nA)))
    }
}
