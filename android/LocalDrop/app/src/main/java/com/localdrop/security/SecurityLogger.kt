package com.localdrop.security

import com.localdrop.core.AppLogger

// M05: security event logging. Callers pass ONLY safe values (transaction
// ids, peer device ids, event types, reasons, timestamps). Private keys,
// nonces, signatures, public-key bytes, and full transaction codes MUST
// NEVER reach any method here; test 21 asserts their absence.
interface SecurityLogger {
    fun authEvent(succeeded: Boolean, peerDeviceId: String?, detail: String)
    fun transactionEvent(type: String, transactionId: String, peerDeviceId: String?, detail: String)
}

// Production sink: delegates to the existing AppLogger (no cloud sink).
class AppSecurityLogger(
    private val appLogger: AppLogger,
    private val tag: String = "LocalDropSec"
) : SecurityLogger {
    override fun authEvent(succeeded: Boolean, peerDeviceId: String?, detail: String) {
        val line = "auth ok=$succeeded peer=${peerDeviceId ?: "-"} $detail"
        if (succeeded) appLogger.i(tag, line) else appLogger.w(tag, line)
    }

    override fun transactionEvent(type: String, transactionId: String, peerDeviceId: String?, detail: String) {
        appLogger.i(tag, "txn type=$type id=$transactionId peer=${peerDeviceId ?: "-"} $detail")
    }
}

// Test sink: records lines for secret-absence assertions.
class RecordingSecurityLogger : SecurityLogger {
    private val lock = Any()
    val entries = mutableListOf<String>()
    override fun authEvent(succeeded: Boolean, peerDeviceId: String?, detail: String) {
        synchronized(lock) { entries.add("auth ok=$succeeded peer=${peerDeviceId ?: "-"} $detail") }
    }
    override fun transactionEvent(type: String, transactionId: String, peerDeviceId: String?, detail: String) {
        synchronized(lock) { entries.add("txn type=$type id=$transactionId peer=${peerDeviceId ?: "-"} $detail") }
    }
    fun snapshot(): List<String> = synchronized(lock) { entries.toList() }
}
