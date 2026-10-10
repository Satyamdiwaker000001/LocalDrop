package com.localdrop.transfer

import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

// M06 source abstraction (ADR 1). The engine streams from an InputStream and
// needs only a base file name + a 64-bit size up front, so content/document
// URIs work without ever assuming a raw filesystem path.
interface TransferSource {
    // Base file name only (no directories); validated against M02 rules.
    val displayName: String
    val sizeBytes: Long
    val lastModifiedMs: Long
    fun openStream(): InputStream
}

class FileTransferSource(private val file: File) : TransferSource {
    override val displayName: String get() = file.name
    override val sizeBytes: Long get() = file.length()
    override val lastModifiedMs: Long get() = file.lastModified()
    override fun openStream(): InputStream = FileInputStream(file)
}

// Test/sample source: deterministic in-memory bytes (also useful for
// generated fixtures without touching disk).
class ByteArrayTransferSource(
    override val displayName: String,
    private val bytes: ByteArray
) : TransferSource {
    override val sizeBytes: Long get() = bytes.size.toLong()
    override val lastModifiedMs: Long get() = 0L
    override fun openStream(): InputStream = ByteArrayInputStream(bytes)
}
