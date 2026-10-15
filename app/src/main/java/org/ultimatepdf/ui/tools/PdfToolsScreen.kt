package org.ultimatepdf.ui.tools

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.ultimatepdf.core.model.DocumentModel
import org.ultimatepdf.core.pdf.PdfSecurityManager
import java.io.File

data class ToolItem(
    val id: String,
    val title: String,
    val description: String,
    val icon: ImageVector
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfToolsScreen(
    currentDocument: DocumentModel?,
    securityManager: PdfSecurityManager,
    isTablet: Boolean = false
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var activeDialog by remember { mutableStateOf<String?>(null) }
    var passwordInput by remember { mutableStateOf("") }
    var splitPageInput by remember { mutableStateOf("1") }
    var extractedTextResult by remember { mutableStateOf<String?>(null) }
    var isWorking by remember { mutableStateOf(false) }

    val mergePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.size >= 2) {
            coroutineScope.launch {
                isWorking = true
                val outFile = File(context.cacheDir, "merged_${System.currentTimeMillis()}.pdf")
                val success = securityManager.mergeDocuments(uris, outFile)
                isWorking = false
                if (success) {
                    Toast.makeText(context, "Merged ${uris.size} PDFs into ${outFile.name}", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(context, "Merge failed", Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            Toast.makeText(context, "Please select at least 2 PDF files to merge", Toast.LENGTH_SHORT).show()
        }
    }

    val tools = listOf(
        ToolItem(
            id = "merge",
            title = "Merge Documents",
            description = "Combine multiple PDF files into a single master document",
            icon = Icons.Default.CallMerge
        ),
        ToolItem(
            id = "split",
            title = "Split Document",
            description = "Divide a PDF into separate parts by page intervals",
            icon = Icons.Default.CallSplit
        ),
        ToolItem(
            id = "encrypt",
            title = "AES-256 Password Protect",
            description = "Set user and owner passwords with AES encryption",
            icon = Icons.Default.Lock
        ),
        ToolItem(
            id = "decrypt",
            title = "Remove Password",
            description = "Unlock an encrypted document permanently",
            icon = Icons.Default.LockOpen
        ),
        ToolItem(
            id = "extract_images",
            title = "Extract Images",
            description = "Extract all embedded JPEG and PNG images from the PDF",
            icon = Icons.Default.Image
        ),
        ToolItem(
            id = "extract_text",
            title = "Extract Text",
            description = "Rip pure text content out of the document for indexing",
            icon = Icons.Default.TextFields
        )
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("PDF Swiss-Army Utilities", fontWeight = FontWeight.Bold) }
            )
        }
    ) { padding ->
        val columns = if (isTablet) 3 else 2

        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            items(tools) { tool ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                        .clickable {
                            when (tool.id) {
                                "merge" -> mergePicker.launch(arrayOf("application/pdf"))
                                "split", "encrypt", "decrypt", "extract_images", "extract_text" -> {
                                    if (currentDocument == null) {
                                        Toast.makeText(context, "Open a PDF in the Viewer first", Toast.LENGTH_SHORT).show()
                                    } else {
                                        activeDialog = tool.id
                                    }
                                }
                            }
                        },
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(44.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(tool.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            }
                        }

                        Column {
                            Text(
                                text = tool.title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = tool.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2
                            )
                        }
                    }
                }
            }
        }
    }

    // Encrypt Dialog
    if (activeDialog == "encrypt" && currentDocument != null) {
        AlertDialog(
            onDismissRequest = { activeDialog = null },
            title = { Text("AES-256 Protect PDF") },
            text = {
                OutlinedTextField(
                    value = passwordInput,
                    onValueChange = { passwordInput = it },
                    label = { Text("New Password") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val pwd = passwordInput
                        activeDialog = null
                        coroutineScope.launch {
                            val outFile = File(context.cacheDir, "protected_${System.currentTimeMillis()}.pdf")
                            val success = securityManager.encryptDocument(
                                sourceUri = currentDocument.uri,
                                userPassword = pwd,
                                outputFile = outFile
                            )
                            if (success) {
                                Toast.makeText(context, "Document encrypted with AES-256: ${outFile.name}", Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    enabled = passwordInput.isNotBlank()
                ) {
                    Text("Encrypt")
                }
            },
            dismissButton = {
                TextButton(onClick = { activeDialog = null }) { Text("Cancel") }
            }
        )
    }

    // Extract Text Dialog
    if (activeDialog == "extract_text" && currentDocument != null) {
        LaunchedEffect(Unit) {
            extractedTextResult = securityManager.extractText(currentDocument.uri)
        }
        AlertDialog(
            onDismissRequest = { activeDialog = null },
            title = { Text("Extracted Document Text") },
            text = {
                Text(
                    text = extractedTextResult ?: "Extracting text...",
                    modifier = Modifier.heightIn(max = 300.dp)
                )
            },
            confirmButton = {
                TextButton(onClick = { activeDialog = null }) { Text("Close") }
            }
        )
    }

    // Split Dialog
    if (activeDialog == "split" && currentDocument != null) {
        AlertDialog(
            onDismissRequest = { activeDialog = null },
            title = { Text("Split PDF Document") },
            text = {
                OutlinedTextField(
                    value = splitPageInput,
                    onValueChange = { splitPageInput = it },
                    label = { Text("Split At Page (interval)") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val interval = splitPageInput.toIntOrNull() ?: 1
                        activeDialog = null
                        coroutineScope.launch {
                            val parts = securityManager.splitDocument(currentDocument.uri, interval, context.cacheDir)
                            Toast.makeText(context, "Split into ${parts.size} part(s)", Toast.LENGTH_LONG).show()
                        }
                    }
                ) {
                    Text("Split")
                }
            },
            dismissButton = {
                TextButton(onClick = { activeDialog = null }) { Text("Cancel") }
            }
        )
    }

    // Extract Images Dialog
    if (activeDialog == "extract_images" && currentDocument != null) {
        LaunchedEffect(Unit) {
            coroutineScope.launch {
                val imgs = securityManager.extractImages(currentDocument.uri, context.cacheDir)
                Toast.makeText(context, "Extracted ${imgs.size} image(s)", Toast.LENGTH_LONG).show()
                activeDialog = null
            }
        }
    }
}
