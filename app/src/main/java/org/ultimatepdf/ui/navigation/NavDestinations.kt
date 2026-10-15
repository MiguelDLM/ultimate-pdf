package org.ultimatepdf.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMerge
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.DynamicForm
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.ui.graphics.vector.ImageVector
import org.ultimatepdf.R

/**
 * Full-screen destinations opened on top of the home screen or the reader. The reader and
 * its markup / signature tools are not destinations: they are the main screen.
 */
enum class AppDestination(
    @StringRes val title: Int,
    @StringRes val shortTitle: Int,
    @StringRes val description: Int,
    val icon: ImageVector
) {
    EDIT_CONTENT(R.string.tool_edit_content, R.string.tool_edit_content_short, R.string.tool_edit_content_desc, Icons.Default.EditNote),
    EXPORT(R.string.tool_export, R.string.tool_export_short, R.string.tool_export_desc, Icons.Default.IosShare),
    ORGANIZE(R.string.tool_organize, R.string.tool_organize_short, R.string.tool_organize_desc, Icons.Default.GridView),
    FORMS(R.string.tool_forms, R.string.tool_forms_short, R.string.tool_forms_desc, Icons.Default.DynamicForm),
    CERTIFICATE(R.string.tool_certificate, R.string.tool_certificate_short, R.string.tool_certificate_desc, Icons.Default.VerifiedUser),
    SCANNER(R.string.tool_scan, R.string.tool_scan_short, R.string.tool_scan_desc, Icons.Default.DocumentScanner),
    MERGE(R.string.tool_merge, R.string.tool_merge, R.string.tool_merge_desc, Icons.AutoMirrored.Filled.CallMerge),
    SPLIT(R.string.tool_split, R.string.tool_split, R.string.tool_split_desc, Icons.Default.CallSplit),
    EXTRACT_PAGES(R.string.tool_extract_pages, R.string.tool_extract_pages_short, R.string.tool_extract_pages_desc, Icons.Default.ContentCut),
    COMPRESS(R.string.tool_compress, R.string.tool_compress_short, R.string.tool_compress_desc, Icons.Default.Compress),
    RESIZE(R.string.tool_resize, R.string.tool_resize_short, R.string.tool_resize_desc, Icons.Default.AspectRatio),
    PROTECT(R.string.tool_protect, R.string.tool_protect, R.string.tool_protect_desc, Icons.Default.Lock),
    UNLOCK(R.string.tool_unlock, R.string.tool_unlock, R.string.tool_unlock_desc, Icons.Default.LockOpen),
    EXTRACT_TEXT(R.string.tool_extract_text, R.string.tool_extract_text_short, R.string.tool_extract_text_desc, Icons.Default.Notes),
    EXTRACT_IMAGES(R.string.tool_extract_images, R.string.tool_extract_images_short, R.string.tool_extract_images_desc, Icons.Default.Image),
    RECENTS(R.string.recent_title, R.string.recent_title, R.string.recent_title, Icons.Default.History),
    SETTINGS(R.string.settings_title, R.string.settings_title, R.string.settings_title, Icons.Default.Settings);

    companion object {
        /** Tools offered on the home screen, in order. */
        val homeTools = listOf(SCANNER, EXPORT, MERGE, SPLIT, EXTRACT_PAGES, COMPRESS, RESIZE, PROTECT, UNLOCK, EXTRACT_TEXT, EXTRACT_IMAGES)

        /** Editing tools for the open document (reader's "More" sheet). */
        val documentTools = listOf(EDIT_CONTENT, ORGANIZE, FORMS, CERTIFICATE)

        /** File tools that start from the open document. */
        val fileTools = listOf(EXPORT, COMPRESS, RESIZE, SPLIT, EXTRACT_PAGES, MERGE, PROTECT, EXTRACT_TEXT, EXTRACT_IMAGES)
    }
}
