package com.localdrop.service

import com.localdrop.storage.CheckpointDao

// M13: visible database-failure policy (framework-free, JVM-tested).
//
// Database initialization failure MUST NOT silently disable resume: resolve()
// captures the exception and failureNote() renders the user-visible text the
// UI shows. A null dao with a null failure means "no store configured"
// (tests); a null dao with a non-null failure means "resume disabled, and
// the user was told".
data class CheckpointResolution(
    val dao: CheckpointDao?,
    val failure: Exception?
)

object CheckpointStore {
    fun resolve(factory: (() -> CheckpointDao)?): CheckpointResolution {
        if (factory == null) return CheckpointResolution(null, null)
        return try {
            CheckpointResolution(factory.invoke(), null)
        } catch (e: Exception) {
            CheckpointResolution(null, e)
        }
    }

    fun failureNote(failure: Exception?): String? =
        if (failure != null)
            "Resume unavailable: local database could not be opened (${failure.message}). Transfers still work from the start."
        else null
}
