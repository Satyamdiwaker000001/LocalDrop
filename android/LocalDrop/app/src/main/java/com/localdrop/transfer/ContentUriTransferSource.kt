package com.localdrop.transfer

import android.content.ContentResolver
import android.net.Uri
import java.io.FileNotFoundException
import java.io.InputStream

// M07 UI closure: TransferSource over a Storage Access Framework document
// URI (Android file picker result). The display name and size are captured
// once on the UI thread at pick time; the stream is opened per send on a
// worker thread. Never assumes a raw filesystem path: content:// URIs from
// Downloads, Drive, SD cards, etc. all stream through ContentResolver.
class ContentUriTransferSource(
    private val resolver: ContentResolver,
    private val uri: Uri,
    override val displayName: String,
    override val sizeBytes: Long
) : TransferSource {
    override val lastModifiedMs: Long = 0L
    init {
        // Fail fast at pick time (actionable UI error) rather than mid-send:
        // the engine additionally re-validates the base-name shape per M02.
        require(displayName.isNotBlank()) { "Document has no usable file name." }
        require('/' !in displayName && '\\' !in displayName) {
            "Document name is not a base file name."
        }
        require(sizeBytes >= 0L) { "Document has no readable size." }
    }

    override fun openStream(): InputStream =
        resolver.openInputStream(uri)
            ?: throw FileNotFoundException("Document no longer available: $uri")
}
