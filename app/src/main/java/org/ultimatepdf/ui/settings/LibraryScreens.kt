package org.ultimatepdf.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.ultimatepdf.R
import org.ultimatepdf.core.signature.SavedSignature
import org.ultimatepdf.core.signature.SavedStamp
import org.ultimatepdf.core.signature.SignatureLibrary
import org.ultimatepdf.core.signature.StampKind
import org.ultimatepdf.core.signature.StampLibrary
import org.ultimatepdf.ui.components.ConfirmDeleteDialog
import org.ultimatepdf.ui.components.RenameDialog
import org.ultimatepdf.ui.components.ScreenTopBar
import org.ultimatepdf.ui.sign.SignaturePadDialog
import org.ultimatepdf.ui.sign.SignaturePreview
import org.ultimatepdf.ui.sign.rememberVectorizeFlow
import org.ultimatepdf.ui.stamps.CustomTextStampDialog
import org.ultimatepdf.ui.stamps.StampImageSourceDialog
import org.ultimatepdf.ui.stamps.TextStampPreview
import org.ultimatepdf.ui.viewer.SignatureColors
import org.ultimatepdf.ui.viewer.StampColors

/** Manage the saved signatures: see them, add new ones, rename and delete. */
@Composable
fun SignaturesScreen(library: SignatureLibrary, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showNew by remember { mutableStateOf(false) }
    var showPad by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<SavedSignature?>(null) }
    var deleting by remember { mutableStateOf<SavedSignature?>(null) }
    val ink = SignatureColors.first()

    fun add(shape: org.ultimatepdf.core.model.SignatureShape) {
        scope.launch {
            library.save(shape, context.getString(R.string.signature_default_name, nextNumber(library.items.map { it.name })))
        }
    }

    val flow = rememberVectorizeFlow(
        isStamp = false,
        colors = SignatureColors,
        color = ink,
        onColorChange = {},
        onShape = ::add
    )

    Scaffold(
        topBar = { ScreenTopBar(stringResource(R.string.library_signatures_title), onBack) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showNew = true },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text(stringResource(R.string.library_new_signature)) }
            )
        }
    ) { padding ->
        if (library.items.isEmpty()) {
            EmptyLibrary(stringResource(R.string.library_empty_signatures), padding)
        } else {
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(padding)
            ) {
                items(library.items, key = { it.id }) { signature ->
                    LibraryRow(
                        name = signature.name,
                        subtitle = null,
                        onRename = { renaming = signature },
                        onDelete = { deleting = signature }
                    ) {
                        Box(
                            Modifier
                                .size(width = 120.dp, height = 60.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color.White)
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
                        ) { SignaturePreview(signature.shape, ink, Modifier.fillMaxSize()) }
                    }
                }
            }
        }
    }

    if (showNew) {
        AlertDialog(
            onDismissRequest = { showNew = false },
            title = { Text(stringResource(R.string.sig_new_title)) },
            text = {
                Column {
                    NewOption(Icons.Default.PhotoCamera, R.string.sig_scan, R.string.sig_scan_desc) { showNew = false; flow.fromCamera() }
                    NewOption(Icons.Default.Gesture, R.string.sig_draw, R.string.sig_draw_desc) { showNew = false; showPad = true }
                    NewOption(Icons.Default.PhotoLibrary, R.string.sig_photo, R.string.sig_photo_desc) { showNew = false; flow.fromGallery() }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showNew = false }) { Text(stringResource(R.string.cancel)) } }
        )
    }
    if (showPad) {
        SignaturePadDialog(color = ink, onDismiss = { showPad = false }, onConfirm = { shape -> showPad = false; add(shape) })
    }
    renaming?.let { target ->
        RenameDialog(
            title = stringResource(R.string.library_rename_signature),
            initial = target.name,
            onDismiss = { renaming = null },
            onConfirm = { name -> renaming = null; scope.launch { library.rename(target.id, name) } }
        )
    }
    deleting?.let { target ->
        ConfirmDeleteDialog(
            title = stringResource(R.string.library_delete_signature_title),
            text = stringResource(R.string.library_delete_signature_text),
            onDismiss = { deleting = null },
            onConfirm = { deleting = null; scope.launch { library.delete(target.id) } }
        )
    }
}

/** Manage the custom stamps: see them, create text or image stamps, rename and delete. */
@Composable
fun StampsScreen(library: StampLibrary, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showNew by remember { mutableStateOf(false) }
    var showText by remember { mutableStateOf(false) }
    var showImageSource by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<SavedStamp?>(null) }
    var deleting by remember { mutableStateOf<SavedStamp?>(null) }
    var color by remember { mutableStateOf(StampColors.first()) }

    val flow = rememberVectorizeFlow(
        isStamp = true,
        colors = StampColors,
        color = color,
        onColorChange = { color = it },
        onShape = { shape ->
            scope.launch {
                library.addImage(shape, color.toArgb(), context.getString(R.string.stamp_default_name, nextNumber(library.items.map { it.name })))
            }
        }
    )

    Scaffold(
        topBar = { ScreenTopBar(stringResource(R.string.library_stamps_title), onBack) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showNew = true },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text(stringResource(R.string.library_new_stamp)) }
            )
        }
    ) { padding ->
        if (library.items.isEmpty()) {
            EmptyLibrary(stringResource(R.string.library_empty_stamps), padding)
        } else {
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(padding)
            ) {
                items(library.items, key = { it.id }) { stamp ->
                    LibraryRow(
                        name = stamp.name,
                        subtitle = stringResource(if (stamp.kind == StampKind.TEXT) R.string.stamp_kind_text else R.string.stamp_kind_image),
                        onRename = { renaming = stamp },
                        onDelete = { deleting = stamp }
                    ) {
                        Box(
                            Modifier
                                .size(width = 120.dp, height = 60.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color.White)
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            val stampColor = Color(stamp.colorArgb)
                            if (stamp.kind == StampKind.IMAGE && stamp.shape != null) {
                                SignaturePreview(stamp.shape, stampColor, Modifier.fillMaxSize().padding(6.dp))
                            } else {
                                TextStampPreview(stamp.text, stampColor, Modifier.padding(4.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    if (showNew) {
        AlertDialog(
            onDismissRequest = { showNew = false },
            title = { Text(stringResource(R.string.library_new_stamp)) },
            text = {
                Column {
                    NewOption(Icons.Default.TextFields, R.string.stamp_custom_text, null) { showNew = false; showText = true }
                    NewOption(Icons.Default.PhotoCamera, R.string.stamp_from_image, null) { showNew = false; showImageSource = true }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showNew = false }) { Text(stringResource(R.string.cancel)) } }
        )
    }
    if (showText) {
        CustomTextStampDialog(
            initialText = "",
            initialColor = color,
            onDismiss = { showText = false },
            onConfirm = { text, chosen ->
                showText = false
                color = chosen
                scope.launch { library.addText(text, chosen.toArgb()) }
            }
        )
    }
    if (showImageSource) {
        StampImageSourceDialog(
            onDismiss = { showImageSource = false },
            onCamera = flow.fromCamera,
            onGallery = flow.fromGallery
        )
    }
    renaming?.let { target ->
        RenameDialog(
            title = stringResource(R.string.library_rename_stamp),
            initial = target.name,
            onDismiss = { renaming = null },
            onConfirm = { name -> renaming = null; scope.launch { library.rename(target.id, name) } }
        )
    }
    deleting?.let { target ->
        ConfirmDeleteDialog(
            title = stringResource(R.string.library_delete_stamp_title),
            text = stringResource(R.string.library_delete_stamp_text),
            onDismiss = { deleting = null },
            onConfirm = { deleting = null; scope.launch { library.delete(target.id) } }
        )
    }
}

@Composable
private fun EmptyLibrary(message: String, padding: PaddingValues) {
    Box(Modifier.fillMaxSize().padding(padding).padding(32.dp), contentAlignment = Alignment.Center) {
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun LibraryRow(
    name: String,
    subtitle: String?,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    preview: @Composable () -> Unit
) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp)) {
            preview()
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            IconButton(onClick = onRename) { Icon(Icons.Default.Edit, stringResource(R.string.library_rename)) }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, stringResource(R.string.delete)) }
        }
    }
}

@Composable
private fun NewOption(icon: ImageVector, @androidx.annotation.StringRes title: Int, @androidx.annotation.StringRes subtitle: Int?, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(stringResource(title)) },
        supportingContent = subtitle?.let { { Text(stringResource(it)) } },
        leadingContent = {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer)
            ) { Icon(icon, null, tint = MaterialTheme.colorScheme.onSecondaryContainer) }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick)
    )
}

/** The lowest number n such that "<something> n" isn't used yet among [names]. */
private fun nextNumber(names: List<String>): Int {
    val used = names.mapNotNull { Regex("(\\d+)$").find(it)?.value?.toIntOrNull() }.toSet()
    var n = names.size + 1
    while (n in used) n++
    return n
}
