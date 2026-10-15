package org.ultimatepdf.core.model

import android.net.Uri

/**
 * Metadata and state representing an active PDF document.
 */
data class DocumentModel(
    val uri: Uri,
    val name: String,
    val pageCount: Int,
    val sizeBytes: Long = 0L,
    val isEncrypted: Boolean = false,
    val isReadOnly: Boolean = false
)

/**
 * Represents a single page rendering request and viewport info.
 */
data class PageRenderRequest(
    val pageIndex: Int,
    val targetWidth: Int,
    val targetHeight: Int,
    val invertColors: Boolean = false,
    val zoomFactor: Float = 1.0f
)
