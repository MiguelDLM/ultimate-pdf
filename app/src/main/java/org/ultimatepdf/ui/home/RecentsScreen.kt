package org.ultimatepdf.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.ultimatepdf.R
import org.ultimatepdf.core.pdf.PdfThumbnails
import org.ultimatepdf.core.settings.RecentDocument
import org.ultimatepdf.ui.components.ScreenTopBar

@Composable
fun RecentsScreen(
    recents: List<RecentDocument>,
    thumbnails: PdfThumbnails,
    onOpen: (RecentDocument) -> Unit,
    onRemove: (RecentDocument) -> Unit,
    onClearAll: () -> Unit,
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            ScreenTopBar(stringResource(R.string.recent_title), onBack) {
                if (recents.isNotEmpty()) TextButton(onClick = onClearAll) { Text(stringResource(R.string.recent_clear)) }
            }
        }
    ) { padding ->
        if (recents.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.recent_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(contentPadding = padding) {
                items(recents, key = { it.uri.toString() }) { doc ->
                    RecentRow(doc, thumbnails, onClick = { onOpen(doc) }, onRemove = { onRemove(doc) })
                }
            }
        }
    }
}
