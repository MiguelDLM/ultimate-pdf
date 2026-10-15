package org.ultimatepdf.ui.stamps

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.ultimatepdf.R
import org.ultimatepdf.ui.viewer.ColorSwatches
import org.ultimatepdf.ui.viewer.StampColors

/** A text stamp as it looks on the page: bold capitals in a double frame. */
@Composable
fun TextStampPreview(text: String, color: Color, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = Color.Transparent,
        border = BorderStroke(2.5.dp, color),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .padding(3.dp)
                .border(BorderStroke(1.dp, color.copy(alpha = 0.5f)), RoundedCornerShape(5.dp))
                .padding(horizontal = 14.dp, vertical = 6.dp)
        ) {
            Text(
                text = text,
                color = color,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                maxLines = 1
            )
        }
    }
}

@Composable
fun CustomTextStampDialog(
    initialText: String,
    initialColor: Color,
    onDismiss: () -> Unit,
    onConfirm: (String, Color) -> Unit
) {
    var text by remember { mutableStateOf(initialText) }
    var color by remember { mutableStateOf(initialColor) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.stamp_create_text)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(R.string.stamp_text_label)) },
                    placeholder = { Text(stringResource(R.string.stamp_text_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Text(stringResource(R.string.stamp_preview), style = MaterialTheme.typography.labelMedium)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(76.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
                    contentAlignment = Alignment.Center
                ) {
                    val previewText = text.trim().ifEmpty { stringResource(R.string.stamp_approved) }.uppercase()
                    TextStampPreview(previewText, color, Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                }

                Text(stringResource(R.string.color), style = MaterialTheme.typography.labelMedium)
                ColorSwatches(StampColors, color) { color = it }
            }
        },
        confirmButton = {
            Button(
                onClick = { if (text.isNotBlank()) onConfirm(text.trim().uppercase(), color) },
                enabled = text.isNotBlank()
            ) { Text(stringResource(R.string.use)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

@Composable
fun StampImageSourceDialog(
    onDismiss: () -> Unit,
    onCamera: () -> Unit,
    onGallery: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.stamp_image_source_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.stamp_take_photo)) },
                    leadingContent = {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer)
                        ) { Icon(Icons.Default.PhotoCamera, null, tint = MaterialTheme.colorScheme.onPrimaryContainer) }
                    },
                    modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { onDismiss(); onCamera() }
                )
                ListItem(
                    headlineContent = { Text(stringResource(R.string.stamp_pick_gallery)) },
                    leadingContent = {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer)
                        ) { Icon(Icons.Default.PhotoLibrary, null, tint = MaterialTheme.colorScheme.onSecondaryContainer) }
                    },
                    modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { onDismiss(); onGallery() }
                )
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}
