package org.ultimatepdf.ui.sign

import androidx.compose.ui.res.stringResource
import org.ultimatepdf.R
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.ultimatepdf.core.model.DigitalSignatureConfig
import org.ultimatepdf.core.model.DocumentModel
import org.ultimatepdf.core.model.PdfRect
import org.ultimatepdf.core.model.SignatureLevel
import org.ultimatepdf.core.pdf.DigitalSignatureService
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DigitalSignScreen(
    currentDocument: DocumentModel?,
    signatureService: DigitalSignatureService,
    onSigned: suspend (File) -> Boolean,
    onBack: () -> Unit,
    isTablet: Boolean = false
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    if (currentDocument == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }

    var signerName by remember { mutableStateOf("") }
    val defaultReason = stringResource(R.string.cert_reason_default)
    var reason by remember { mutableStateOf(defaultReason) }
    var location by remember { mutableStateOf("") }
    var selectedCertUri by remember { mutableStateOf<Uri?>(null) }
    var certPassword by remember { mutableStateOf("") }
    var isSigning by remember { mutableStateOf(false) }

    // Handwritten pad points
    val strokePaths = remember { mutableStateListOf<List<Offset>>() }
    var activeStroke by remember { mutableStateOf<List<Offset>>(emptyList()) }

    val certPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        selectedCertUri = uri
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                title = { Text(stringResource(R.string.tool_certificate_short)) }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header card
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.VerifiedUser, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = stringResource(R.string.cert_intro),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            // 1. Signature Pad Canvas
            Text(stringResource(R.string.cert_draw), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
            ) {
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { offset ->
                                    activeStroke = listOf(offset)
                                },
                                onDrag = { change, _ ->
                                    change.consume()
                                    activeStroke = activeStroke + change.position
                                },
                                onDragEnd = {
                                    if (activeStroke.isNotEmpty()) {
                                        strokePaths.add(activeStroke)
                                        activeStroke = emptyList()
                                    }
                                }
                            )
                        }
                ) {
                    // Draw committed strokes
                    strokePaths.forEach { stroke ->
                        if (stroke.size > 1) {
                            val p = Path().apply {
                                moveTo(stroke[0].x, stroke[0].y)
                                for (i in 1 until stroke.size) lineTo(stroke[i].x, stroke[i].y)
                            }
                            drawPath(p, color = Color(0xFF0F172A), style = Stroke(width = 4.dp.toPx()))
                        }
                    }

                    // Draw in-progress stroke
                    if (activeStroke.size > 1) {
                        val p = Path().apply {
                            moveTo(activeStroke[0].x, activeStroke[0].y)
                            for (i in 1 until activeStroke.size) lineTo(activeStroke[i].x, activeStroke[i].y)
                        }
                        drawPath(p, color = Color(0xFF0F172A), style = Stroke(width = 4.dp.toPx()))
                    }
                }

                // Clear button on pad
                IconButton(
                    onClick = {
                        strokePaths.clear()
                        activeStroke = emptyList()
                    },
                    modifier = Modifier.align(Alignment.TopEnd)
                ) {
                    Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.cert_clear), tint = Color.Gray)
                }
            }

            // 2. Cryptographic Certificate details
            Text(stringResource(R.string.cert_details), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)

            OutlinedTextField(
                value = signerName,
                onValueChange = { signerName = it },
                label = { Text(stringResource(R.string.cert_name)) },
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = reason,
                onValueChange = { reason = it },
                label = { Text(stringResource(R.string.cert_reason)) },
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = location,
                onValueChange = { location = it },
                label = { Text(stringResource(R.string.cert_location)) },
                modifier = Modifier.fillMaxWidth()
            )

            // Certificate selection
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = selectedCertUri?.lastPathSegment ?: stringResource(R.string.cert_none),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = stringResource(if (selectedCertUri != null) R.string.cert_loaded else R.string.cert_optional),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                FilledTonalButton(onClick = { certPicker.launch(arrayOf("*/*")) }) {
                    Text(stringResource(R.string.cert_select))
                }
            }

            if (selectedCertUri != null) {
                OutlinedTextField(
                    value = certPassword,
                    onValueChange = { certPassword = it },
                    label = { Text(stringResource(R.string.cert_password)) },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Sign action button
            Button(
                onClick = {
                    coroutineScope.launch {
                        isSigning = true

                        // Generate signature bitmap from pad
                        val padBitmap = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888)
                        val canvas = Canvas(padBitmap)
                        canvas.drawColor(android.graphics.Color.TRANSPARENT)
                        val paint = Paint().apply {
                            color = android.graphics.Color.BLACK
                            strokeWidth = 6f
                            style = Paint.Style.STROKE
                            isAntiAlias = true
                        }

                        strokePaths.forEach { stroke ->
                            if (stroke.size > 1) {
                                val p = android.graphics.Path().apply {
                                    moveTo(stroke[0].x, stroke[0].y)
                                    for (i in 1 until stroke.size) lineTo(stroke[i].x, stroke[i].y)
                                }
                                canvas.drawPath(p, paint)
                            }
                        }

                        val outFile = File(context.cacheDir, "signed_${System.currentTimeMillis()}.pdf")
                        val config = DigitalSignatureConfig(
                            pageIndex = 0,
                            visualRect = PdfRect(50f, 50f, 320f, 150f),
                            signerName = signerName,
                            reason = reason,
                            location = location,
                            signatureLevel = if (selectedCertUri != null) SignatureLevel.PADES_B else SignatureLevel.VISUAL_ONLY,
                            certificateUri = selectedCertUri?.toString(),
                            certificatePassword = certPassword,
                            handwrittenBitmap = padBitmap
                        )

                        val success = signatureService.signDocument(
                            sourceUri = currentDocument.uri,
                            config = config,
                            outputFile = outFile
                        )

                        // The signed file replaces the open document (or is saved as a copy).
                        val delivered = success && onSigned(outFile)
                        isSigning = false
                        Toast.makeText(context, if (delivered) R.string.cert_signed else R.string.cert_failed, Toast.LENGTH_SHORT).show()
                    }
                },
                enabled = !isSigning && (strokePaths.isNotEmpty() || signerName.isNotBlank()),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                if (isSigning) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Icon(Icons.Default.Verified, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.cert_sign))
                }
            }
        }
    }
}
