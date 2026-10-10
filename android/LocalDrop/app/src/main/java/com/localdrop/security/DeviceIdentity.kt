package com.localdrop.security

import com.localdrop.core.AppConfig
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.UUID

// M05: cryptographic device identity (shared-protocol/authentication.md §1).
// One P-256 keypair per installation; the M02 deviceId UUID is derived from
// SHA-256(SPKI) with version/variant bits set, so the id is cryptographically
// bound to the key. Standard platform primitives only (EC, SHA-256withECDSA,
// SHA-256): NO custom cryptography. Wire forms: SPKI DER (public key) and
// 64-byte raw R||S (signatures); Java DER output is converted to/from raw.

// Bound identity handle. The private key itself is never exposed here;
// signing happens inside the store that owns the key material.
data class DeviceIdentity(
    val deviceId: String,
    val publicKeySpki: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DeviceIdentity) return false
        return deviceId == other.deviceId && publicKeySpki.contentEquals(other.publicKeySpki)
    }
    override fun hashCode(): Int = 31 * deviceId.hashCode() + publicKeySpki.contentHashCode()
}

interface DeviceIdentityStore {
    // Load-or-create the installation identity. The same identity (same
    // deviceId + key) must be returned across restarts (test 2).
    fun identity(): DeviceIdentity

    // ECDSA/SHA-256 sign with the installation private key.
    // Returns the 64-byte raw R||S signature.
    fun sign(data: ByteArray): ByteArray
}

object DeviceIdentityCrypto {
    const val EC_CURVE = "secp256r1"

    // deviceId = UUID(SHA-256(SPKI)[0..16], version=4, variant=10xx).
    fun deriveDeviceId(publicKeySpki: ByteArray): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(publicKeySpki)
        val id = hash.copyOfRange(0, 16)
        id[6] = ((id[6].toInt() and 0x0F) or 0x40).toByte()
        id[8] = ((id[8].toInt() and 0x3F) or 0x80).toByte()
        return UUID(
            ((id[0].toLong() and 0xFF) shl 56) or ((id[1].toLong() and 0xFF) shl 48) or
                ((id[2].toLong() and 0xFF) shl 40) or ((id[3].toLong() and 0xFF) shl 32) or
                ((id[4].toLong() and 0xFF) shl 24) or ((id[5].toLong() and 0xFF) shl 16) or
                ((id[6].toLong() and 0xFF) shl 8) or (id[7].toLong() and 0xFF),
            ((id[8].toLong() and 0xFF) shl 56) or ((id[9].toLong() and 0xFF) shl 48) or
                ((id[10].toLong() and 0xFF) shl 40) or ((id[11].toLong() and 0xFF) shl 32) or
                ((id[12].toLong() and 0xFF) shl 24) or ((id[13].toLong() and 0xFF) shl 16) or
                ((id[14].toLong() and 0xFF) shl 8) or (id[15].toLong() and 0xFF)
        ).toString()
    }

    fun publicKeyFromSpki(spki: ByteArray): PublicKey =
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(spki))

    // DER SEQUENCE{INTEGER r, INTEGER s} -> 64-byte raw R||S.
    fun derToRaw(der: ByteArray): ByteArray = derToRawStrict(der)

    private fun derToRawStrict(der: ByteArray): ByteArray {
        var pos = 0
        require(der[pos++] == 0x30.toByte()) { "not a DER sequence" }
        pos = readLength(der, pos).second
        require(der[pos++] == 0x02.toByte()) { "expected INTEGER r" }
        var rLen: Int
        readLength(der, pos).also { rLen = it.first; pos = it.second }
        var r = der.copyOfRange(pos, pos + rLen)
        pos += rLen
        require(der[pos++] == 0x02.toByte()) { "expected INTEGER s" }
        var sLen: Int
        readLength(der, pos).also { sLen = it.first; pos = it.second }
        var s = der.copyOfRange(pos, pos + sLen)
        r = stripLeadingZero(r)
        s = stripLeadingZero(s)
        require(r.size <= 32 && s.size <= 32) { "integer too large for P-256" }
        val out = ByteArray(64)
        r.copyInto(out, 32 - r.size)
        s.copyInto(out, 64 - s.size)
        return out
    }

    // 64-byte raw R||S -> DER SEQUENCE{INTEGER r, INTEGER s}.
    fun rawToDer(raw: ByteArray): ByteArray {
        require(raw.size == 64) { "raw P-256 signature must be 64 bytes" }
        val r = encodeInteger(raw.copyOfRange(0, 32))
        val s = encodeInteger(raw.copyOfRange(32, 64))
        val total = r.size + s.size
        val header = byteArrayOf(0x30.toByte()) + encodeLength(total)
        return header + r + s
    }

    private fun stripLeadingZero(v: ByteArray): ByteArray {
        var i = 0
        while (i < v.size - 1 && v[i] == 0.toByte()) i++
        return if (i == 0) v else v.copyOfRange(i, v.size)
    }

    private fun encodeInteger(v: ByteArray): ByteArray {
        val stripped = stripLeadingZero(v)
        val withSign = if (stripped[0].toInt() and 0x80 != 0) byteArrayOf(0) + stripped else stripped
        return byteArrayOf(0x02.toByte()) + encodeLength(withSign.size) + withSign
    }

    private fun encodeLength(len: Int): ByteArray {
        if (len < 128) return byteArrayOf(len.toByte())
        if (len < 256) return byteArrayOf(0x81.toByte(), len.toByte())
        return byteArrayOf(0x82.toByte(), (len ushr 8).toByte(), len.toByte())
    }

    private fun readLength(der: ByteArray, pos: Int): Pair<Int, Int> {
        val first = der[pos].toInt() and 0xFF
        if (first < 128) return first to pos + 1
        val count = first and 0x7F
        require(count in 1..4) { "bad DER length" }
        var len = 0
        for (i in 0 until count) len = (len shl 8) or (der[pos + 1 + i].toInt() and 0xFF)
        return len to pos + 1 + count
    }
}

// JVM-testable software store: EC P-256 via the default provider. Used by
// unit tests (AndroidKeyStore is unavailable on the JVM) and as the
// algorithm-identical fallback. Same curve/hash/encodings as production.
class SoftwareDeviceIdentityStore : DeviceIdentityStore {
    private val keyPair = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec(DeviceIdentityCrypto.EC_CURVE))
    }.generateKeyPair()
    private val cached = DeviceIdentity(
        DeviceIdentityCrypto.deriveDeviceId(keyPair.public.encoded),
        keyPair.public.encoded
    )

    override fun identity(): DeviceIdentity = cached

    override fun sign(data: ByteArray): ByteArray {
        val der = Signature.getInstance("SHA256withECDSA").apply {
            initSign(keyPair.private)
            update(data)
        }.sign()
        return DeviceIdentityCrypto.derToRaw(der)
    }

    fun verify(data: ByteArray, rawSignature: ByteArray, spki: ByteArray = cached.publicKeySpki): Boolean {
        return try {
            val der = DeviceIdentityCrypto.rawToDer(rawSignature)
            Signature.getInstance("SHA256withECDSA").apply {
                initVerify(DeviceIdentityCrypto.publicKeyFromSpki(spki))
                update(data)
            }.verify(der)
        } catch (e: Exception) {
            false
        }
    }
}

// Production store: private key in AndroidKeyStore (non-exportable, never
// logged/transmitted). Instantiation touches android.security.keystore, so
// this class is never loaded by JVM unit tests (they use the software store).
class AndroidKeyStoreDeviceIdentityStore(
    private val alias: String = AppConfig.DEVICE_IDENTITY_KEY_ALIAS
) : DeviceIdentityStore {
    private val keyStore: java.security.KeyStore by lazy {
        java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    }

    private fun ensureKeyPair(): PrivateKey {
        if (!keyStore.containsAlias(alias)) {
            val spec = android.security.keystore.KeyGenParameterSpec.Builder(
                alias,
                android.security.keystore.KeyProperties.PURPOSE_SIGN
            )
                .setDigests(android.security.keystore.KeyProperties.DIGEST_SHA256)
                .setAlgorithmParameterSpec(ECGenParameterSpec(DeviceIdentityCrypto.EC_CURVE))
                .build()
            KeyPairGenerator.getInstance("EC", "AndroidKeyStore").apply {
                initialize(spec)
            }.generateKeyPair()
        }
        return keyStore.getKey(alias, null) as PrivateKey
    }

    override fun identity(): DeviceIdentity {
        ensureKeyPair()
        val spki = (keyStore.getCertificate(alias).publicKey as java.security.interfaces.ECPublicKey)
            .encoded
        return DeviceIdentity(DeviceIdentityCrypto.deriveDeviceId(spki), spki)
    }

    override fun sign(data: ByteArray): ByteArray {
        val privateKey = ensureKeyPair()
        val der = Signature.getInstance("SHA256withECDSA").apply {
            initSign(privateKey)
            update(data)
        }.sign()
        return DeviceIdentityCrypto.derToRaw(der)
    }
}
