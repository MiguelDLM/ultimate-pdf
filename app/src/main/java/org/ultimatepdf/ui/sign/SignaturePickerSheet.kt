package org.ultimatepdf.ui.sign

import androidx.compose.ui.res.stringResource
import org.ultimatepdf.R
import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.launch
import org.ultimatepdf.core.model.SignatureShape
import org.ultimatepdf.core.signature.SavedSignature
import org.ultimatepdf.core.signature.SignatureImages
import org.ultimatepdf.core.signature.SignatureLibrary
import java.io.File

/**
 * Chooses the signature to place: a saved one, a new one drawn on screen, or one signed on
 * paper and captured with the camera (or picked from the gallery) and vectorized.
 * New signatures are saved to the library for next time.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignaturePickerSheet(
    library: SignatureLibrary,
    colors: List<Color>,
    color: Color,
    onColorChange: (Color) -> Unit,
    onDismiss: () -> Unit,
    onPick: (SignatureShape) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showPad by remember { mutableStateOf(false) }

    // New signatures go to the library (named "Signature n") and are placed right away.
    fun pickNew(shape: SignatureShape) {
        scope.launch {
            val n = library.items.size + 1
            library.save(shape, context.getString(R.string.signature_default_name, n))
            onPick(shape)
        }
    }

    val flow = rememberVectorizeFlow(
        isStamp = false,
        colors = colors,
        color = color,
        onColorChange = onColorChange,
        onShape = ::pickNew
    )

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.sig_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp))

            val list = library.items
            if (list.isNotEmpty()) {
                Text(
                    stringResource(R.string.sig_tap_saved),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp)
                )
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(list, key = { it.id }) { signature ->
                        Box {
                            Box(
                                modifier = Modifier
                                    .size(width = 168.dp, height = 84.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color.White)
                                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                                    .clickable { onPick(signature.shape) }
                            ) {
                                SignaturePreview(signature.shape, color, Modifier.fillMaxSize())
                            }
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(4.dp)
                                    .size(24.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .clickable { scope.launch { library.delete(signature.id) } }
                            ) {
                                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.sig_delete), modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(horizontal = 24.dp)
            ) {
                Text(stringResource(R.string.sig_ink), style = MaterialTheme.typography.bodyMedium)
                SignatureColorPicker(colors, color, onColorChange)
            }

            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            NewSignatureOption(
                Icons.Default.PhotoCamera,
                stringResource(R.string.sig_scan),
                stringResource(R.string.sig_scan_desc)
            ) { flow.fromCamera() }
            NewSignatureOption(Icons.Default.Gesture, stringResource(R.string.sig_draw), stringResource(R.string.sig_draw_desc)) { showPad = true }
            NewSignatureOption(Icons.Default.PhotoLibrary, stringResource(R.string.sig_photo), stringResource(R.string.sig_photo_desc)) {
                flow.fromGallery()
            }
        }
    }

    if (showPad) {
        SignaturePadDialog(
            color = color,
            onDismiss = { showPad = false },
            onConfirm = { shape ->
                showPad = false
                pickNew(shape)
            }
        )
    }
}

@Composable
private fun NewSignatureOption(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        leadingContent = {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondaryContainer)
            ) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp)
    )
}
