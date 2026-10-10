package com.localdrop.transfer

import java.io.File
import java.io.FileOutputStream

// M07 receiver destination abstraction (approach C: platform-specific
// counterpart to the Windows destDir logic, same behavior contract as M06
// ADR 13 / shared-protocol/data-transfer.md §Temp/finalize).
//
// WHY an interface instead of a raw File dir: on JVM unit tests and on many
// devices a plain app-private directory IS an ordinary filesystem path, but
// Android SAF/document-tree destinations are NOT (no atomic rename, no
// File.length semantics, ContentResolver streams instead). The engine streams
// bytes through this seam so a SAF-backed implementation can be added without
// touching transfer logic; the File implementation below is the M07 default.
interface ReceiverDestination {
    // In-progress temp location for `fileName` (never the final name).
    fun tempFileFor(fileName: String): File
    // Final location; MUST NOT overwrite an unrelated existing file (the
    // File implementation uniquifies with `stem (n).ext`).
    fun uniqueFinalFileFor(fileName: String): File
    // Advisory free-space precheck; null = unknown (caller fails open).
    fun freeSpaceBytes(): Long?
    // Commit temp -> final. Only called after byte-count + TRANSFER_COMPLETE
    // match. Must not overwrite; throws on failure (caller fails the
    // transfer, never reports success).
    fun finalize(temp: File, final: File)
    fun deleteTempQuietly(temp: File)
}

// M07 default: ordinary filesystem directory (app-private files dir on
// device, temp dir in JVM tests). Same naming/uniquification contract as the
// Windows M06 receiver: `<name>.localdrop-part` temp, first-free
// `<stem> (n)<ext>` final, temp deleted on cancel/failure.
//
// Finalization guarantee (precise, no over-claim): the final name is NEVER
// written directly. The commit is either a same-directory rename of the temp
// (one filesystem entry swap on ext4/f2fs/NTFS-class filesystems) or, when
// the rename fails (e.g. cross-volume temp), a copy into a same-directory
// STAGING file (`<final>.localdrop-commit`) followed by a same-directory
// rename of the staging file. A crash can therefore leave a temp or a
// staging file behind, but NEVER a partial file AT the final name; stale
// temps/staging files are never presented as completed and are safe to
// delete. No-clobber is enforced by existence checks plus a CREATE_NEW guard
// on the staging path; a residual TOCTOU window against EXTERNAL actors
// (same as all file managers) is accepted and documented.
open class FileReceiverDestination(private val destDir: File) : ReceiverDestination {
    init {
        destDir.mkdirs()
    }

    override fun tempFileFor(fileName: String): File =
        File(destDir, "$fileName.localdrop-part")

    override fun uniqueFinalFileFor(fileName: String): File {
        var candidate = File(destDir, fileName)
        if (!candidate.exists()) return candidate
        val dot = fileName.lastIndexOf('.')
        val stem = if (dot > 0) fileName.substring(0, dot) else fileName
        val ext = if (dot > 0) fileName.substring(dot) else ""
        for (n in 2..99999) {
            candidate = File(destDir, "$stem ($n)$ext")
            if (!candidate.exists()) return candidate
        }
        // Practically unreachable; fail safe rather than loop forever.
        throw java.io.IOException("No free destination name.")
    }

    override fun freeSpaceBytes(): Long? = try {
        destDir.freeSpace.takeIf { it >= 0 }
    } catch (e: Exception) {
        null
    }

    override fun finalize(temp: File, final: File) {
        if (final.exists()) throw java.io.IOException("Final destination occupied.")
        // Fast path: same-directory rename (one entry swap; never a partial
        // file at the final name by construction). Byte counts were verified
        // by the caller before finalize; here we only confirm the entry
        // landed, and fail closed otherwise.
        if (renameNow(temp, final)) {
            if (!final.exists()) throw java.io.IOException("Finalize verification failed.")
            return
        }
        // Slow path (rename failed, e.g. cross-volume temp): copy into a
        // STAGING file in the destination directory — never directly to the
        // final name — then rename the staging file over the (re-checked)
        // absent final. Any failure deletes the staging file and throws, so
        // no partial file is ever exposed at the final name.
        val staging = File(destDir, "${final.name}.localdrop-commit")
        try {
            deleteQuietly(staging)
            // CREATE_NEW-equivalent guard: fail if something appeared at the
            // staging path between delete and create.
            if (!staging.createNewFile()) throw java.io.IOException("Staging file occupied.")
            temp.inputStream().use { input ->
                FileOutputStream(staging, false).use { output -> input.copyTo(output) }
            }
            if (staging.length() != temp.length()) {
                throw java.io.IOException("Staging copy truncated.")
            }
            if (final.exists()) throw java.io.IOException("Final destination occupied.")
            if (!renameNow(staging, final)) {
                throw java.io.IOException("Commit rename failed.")
            }
        } catch (e: java.io.IOException) {
            deleteQuietly(staging)
            throw e
        } catch (e: Exception) {
            deleteQuietly(staging)
            throw java.io.IOException("Finalize failed: ${e.message}")
        }
    }

    // Rename seam (overridable in tests to force the copy path / failures).
    protected open fun renameNow(source: File, target: File): Boolean =
        try {
            source.renameTo(target)
        } catch (e: Exception) {
            false
        }

    private fun deleteQuietly(file: File) {
        try {
            if (file.exists()) file.delete()
        } catch (e: Exception) {
        }
    }

    // Staging-file marker (tests assert no leftovers after failures).
    internal fun stagingFileFor(final: File): File = File(destDir, "${final.name}.localdrop-commit")

    override fun deleteTempQuietly(temp: File) {
        try {
            if (temp.exists()) temp.delete()
        } catch (e: Exception) {
        }
    }
}
