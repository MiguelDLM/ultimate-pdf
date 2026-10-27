package org.ultimatepdf.ui.home

import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.ultimatepdf.R
import org.ultimatepdf.core.pdf.PageRef
import org.ultimatepdf.core.pdf.PdfThumbnails
import org.ultimatepdf.core.settings.RecentDocument
import org.ultimatepdf.ui.components.PageThumbnail
import org.ultimatepdf.ui.components.deleteFileFromDevice
import org.ultimatepdf.ui.components.formatBytes
import org.ultimatepdf.ui.components.sharePdf
import org.ultimatepdf.ui.navigation.AppDestination

private const val RECENTS_ON_HOME = 3

/** Start screen: open a file, pick up a recent one, or start a tool. */
@Composable
fun HomeScreen(
    isLoading: Boolean,
    recents: List<RecentDocument>,
    thumbnails: PdfThumbnails,
    onOpenPdf: () -> Unit,
    onOpenRecent: (RecentDocument) -> Unit,
    onRemoveRecent: ((RecentDocument) -> Unit)? = null,
    onTool: (AppDestination) -> Unit
) {
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 16.dp)
            ) {
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                IconButton(onClick = { onTool(AppDestination.SETTINGS) }) {
                    Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.settings_title))
                }
            }

            OpenFileCard(isLoading = isLoading, onClick = onOpenPdf)

            SectionHeader(
                title = stringResource(R.string.recent_title),
                action = if (recents.size > RECENTS_ON_HOME) stringResource(R.string.recent_see_all) else null,
                onAction = { onTool(AppDestination.RECENTS) }
            )
            if (recents.isEmpty()) {
                Text(
                    stringResource(R.string.recent_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            } else {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {
                        recents.take(RECENTS_ON_HOME).forEachIndexed { i, doc ->
                            if (i > 0) HorizontalDivider(Modifier.padding(start = 72.dp), color = MaterialTheme.colorScheme.outlineVariant)
                            RecentRow(
                                doc = doc,
                                thumbnails = thumbnails,
                                onClick = { onOpenRecent(doc) },
                                onRemove = onRemoveRecent?.let { { it(doc) } }
                            )
                        }
                    }
                }
            }

            SectionHeader(title = stringResource(R.string.tools_title))
            ToolGrid(AppDestination.homeTools, onTool)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun OpenFileCard(isLoading: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = !isLoading,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp)
        ) {
            if (isLoading) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
            } else {
                Icon(Icons.Default.FolderOpen, contentDescription = null)
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.home_open_pdf), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.home_open_pdf_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
                )
            }
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
        }
    }
}

@Composable
private fun SectionHeader(title: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 28.dp, bottom = 8.dp)
            .heightIn(min = 40.dp)
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        if (action != null) TextButton(onClick = onAction) { Text(action) }
    }
}

/** One recent document: first-page preview, name and details. Supports tap to open, long-press or 3-dots for options. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RecentRow(
    doc: RecentDocument,
    thumbnails: PdfThumbnails,
    onClick: () -> Unit,
    onRemove: (() -> Unit)? = null
) {
    val context = LocalContext.current
    var showMenu by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = { showMenu = true }
            )
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Box(Modifier.width(40.dp)) {
            PageThumbnail(
                page = PageRef(doc.uri, 0),
                thumbnails = thumbnails,
                widthHint = 48.dp,
                borderColor = MaterialTheme.colorScheme.outlineVariant
            )
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(doc.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(
                    pluralStringResource(R.plurals.page_count, doc.pageCount, doc.pageCount),
                    doc.sizeBytes.takeIf { it > 0 }?.let(::formatBytes),
                    DateUtils.getRelativeTimeSpanString(doc.openedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Box {
            IconButton(onClick = { showMenu = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.more_options))
            }
            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.share)) },
                    leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
                    onClick = {
                        showMenu = false
                        sharePdf(context, doc.uri, doc.name)
                    }
                )
                if (onRemove != null) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.recent_remove)) },
                        leadingIcon = { Icon(Icons.Default.Close, contentDescription = null) },
                        onClick = {
                            showMenu = false
                            onRemove()
                        }
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(R.string.delete_from_device),
                                color = MaterialTheme.colorScheme.error
                            )
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Default.DeleteForever,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error
                            )
                        },
                        onClick = {
                            showMenu = false
                            showDeleteConfirm = true
                        }
                    )
                }
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.delete_device_title)) },
            text = { Text(stringResource(R.string.delete_device_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        val ok = deleteFileFromDevice(context, doc.uri)
                        onRemove?.invoke()
                        if (ok) {
                            Toast.makeText(context, R.string.delete_device_success, Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, R.string.delete_device_failed, Toast.LENGTH_SHORT).show()
                        }
                    }
                ) {
                    Text(
                        stringResource(R.string.delete),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun ToolGrid(tools: List<AppDestination>, onTool: (AppDestination) -> Unit) {
    val columns = 4
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        tools.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { tool -> ToolTile(tool, Modifier.weight(1f)) { onTool(tool) } }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun ToolTile(tool: AppDestination, modifier: Modifier, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
        ) {
            Icon(tool.icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(tool.shortTitle),
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

