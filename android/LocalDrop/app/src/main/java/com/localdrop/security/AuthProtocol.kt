package com.localdrop.security

import com.localdrop.core.AppConfig
import com.localdrop.protocol.FieldReader
import com.localdrop.protocol.JsonCanonical
import com.localdrop.protocol.JsonParseResult
import com.localdrop.protocol.JsonValue
import com.localdrop.protocol.PayloadResult
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.Base64

// M05: authentication wire details (shared-protocol/authentication.md §§2-4).
// Token JSON lives inside the opaque M02 AUTH_REQUEST.token string; the
// transcript is the exact byte string both sides sign. No new message types.

object AuthProtocol {
    const val STEP_HELLO = "auth-hello"
    const val STEP_PROOF = "auth-proof"

    fun newNonce(): ByteArray = ByteArray(AppConfig.AUTH_NONCE_BYTES).also {
        SecureRandom().nextBytes(it)
    }

    fun b64encode(raw: ByteArray): String = Base64.getEncoder().encodeToString(raw)

    fun b64decode(text: String): ByteArray? = try {
        Base64.getDecoder().decode(text)
    } catch (e: IllegalArgumentException) {
        null
    }

    // Canonical transcript both sides sign (ids sorted so ordering is
    // direction-independent):
    //   "LocalDrop-Auth-v1" 0x00 method 0x00 idLo 0x00 idHi 0x00 nonceLo 0x00 nonceHi
    fun buildTranscript(
        method: String,
        idA: String, nonceA: ByteArray,
        idB: String, nonceB: ByteArray
    ): ByteArray {
        val (lo, loNonce, hi, hiNonce) = if (idA <= idB) {
            Quad(idA, nonceA, idB, nonceB)
        } else {
            Quad(idB, nonceB, idA, nonceA)
        }
        val out = ByteArrayOutputStream()
        fun field(bytes: ByteArray) {
            out.write(bytes)
            out.write(0)
        }
        field("LocalDrop-Auth-v1".toByteArray(Charsets.UTF_8))
        field(method.toByteArray(Charsets.UTF_8))
        field(lo.toByteArray(Charsets.UTF_8))
        field(hi.toByteArray(Charsets.UTF_8))
        field(loNonce)
        out.write(hiNonce) // no trailing separator
        return out.toByteArray()
    }

    private data class Quad(val a: String, val na: ByteArray, val b: String, val nb: ByteArray)

    fun helloToken(deviceId: String, publicKeySpki: ByteArray, nonce: ByteArray): String =
        JsonCanonical.serialize(
            JsonValue.Obj(
                linkedMapOf(
                    "step" to JsonValue.Str(STEP_HELLO),
                    "deviceId" to JsonValue.Str(deviceId),
                    "publicKey" to JsonValue.Str(b64encode(publicKeySpki)),
                    "nonce" to JsonValue.Str(b64encode(nonce))
                )
            )
        )

    fun proofToken(rawSignature: ByteArray): String =
        JsonCanonical.serialize(
            JsonValue.Obj(
                linkedMapOf(
                    "step" to JsonValue.Str(STEP_PROOF),
                    "signature" to JsonValue.Str(b64encode(rawSignature))
                )
            )
        )

    data class ParsedHello(val deviceId: String, val publicKeySpki: ByteArray, val nonce: ByteArray)
    data class ParsedProof(val rawSignature: ByteArray)

    fun parseHello(token: String): PayloadResult<ParsedHello> {
        val map = parseTokenObject(token, "auth-hello") ?: return PayloadResult.Failure("auth token: not an object")
        val r = FieldReader(map, "auth-hello")
        if (r.string("step", 1, 32) != STEP_HELLO) {
            return PayloadResult.Failure("auth-hello: wrong step")
        }
        val deviceId = r.uuid("deviceId")
        val publicKeyB64 = r.string("publicKey", 1, 1024)
        val nonceB64 = r.string("nonce", 1, 128)
        val err = r.error()
        if (err != null || deviceId == null || publicKeyB64 == null || nonceB64 == null) {
            return PayloadResult.Failure(err ?: "auth-hello: invalid token")
        }
        val spki = b64decode(publicKeyB64)
            ?: return PayloadResult.Failure("auth-hello: bad publicKey encoding")
        val nonce = b64decode(nonceB64)
            ?: return PayloadResult.Failure("auth-hello: bad nonce encoding")
        if (nonce.size != AppConfig.AUTH_NONCE_BYTES) {
            return PayloadResult.Failure("auth-hello: bad nonce length")
        }
        return PayloadResult.Success(ParsedHello(deviceId, spki, nonce))
    }

    fun parseProof(token: String): PayloadResult<ParsedProof> {
        val map = parseTokenObject(token, "auth-proof") ?: return PayloadResult.Failure("auth token: not an object")
        val r = FieldReader(map, "auth-proof")
        if (r.string("step", 1, 32) != STEP_PROOF) {
            return PayloadResult.Failure("auth-proof: wrong step")
        }
        val sigB64 = r.string("signature", 1, 256)
        val err = r.error()
        if (err != null || sigB64 == null) {
            return PayloadResult.Failure(err ?: "auth-proof: invalid token")
        }
        val sig = b64decode(sigB64) ?: return PayloadResult.Failure("auth-proof: bad signature encoding")
        if (sig.size != 64) return PayloadResult.Failure("auth-proof: bad signature length")
        return PayloadResult.Success(ParsedProof(sig))
    }

    private fun parseTokenObject(token: String, ctx: String): Map<String, JsonValue>? {
        if (token.length > 4096) return null
        return when (val parsed = JsonCanonical.parse(token)) {
            is JsonParseResult.Ok -> (parsed.value as? JsonValue.Obj)?.entries
            is JsonParseResult.Err -> null
        }
    }
}
