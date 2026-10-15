package org.ultimatepdf.ui.scanner

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PointF
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.ultimatepdf.core.model.QuadCorners
import org.ultimatepdf.core.model.ScanFilter
import org.ultimatepdf.core.model.ScannedPage
import org.ultimatepdf.core.pdf.SearchablePdfGenerator
import org.ultimatepdf.core.scanner.DocumentDetector
import org.ultimatepdf.core.scanner.OcrEngine
import org.ultimatepdf.core.scanner.PerspectiveCorrector
import java.io.File
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentScannerScreen(
    onPdfCreated: (File) -> Unit,
    isTablet: Boolean = false
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val detector = remember { DocumentDetector() }
    val corrector = remember { PerspectiveCorrector() }
    val ocrEngine = remember { OcrEngine(context) }
    val searchablePdfGen = remember { SearchablePdfGenerator() }

    var rawBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var corners by remember { mutableStateOf<QuadCorners?>(null) }
    var warpedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var activeFilter by remember { mutableStateOf(ScanFilter.MAGIC_COLOR) }

    var isProcessing by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("") }

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            coroutineScope.launch {
                isProcessing = true
                statusText = "Loading image..."
                try {
                    context.contentResolver.openInputStream(it)?.use { stream ->
                        val bmp = BitmapFactory.decodeStream(stream)
                        rawBitmap = bmp
                        val detected = detector.detectDocumentCorners(bmp)
                        corners = detected
                        warpedBitmap = null
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    isProcessing = false
                    statusText = ""
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Smart Scanner & OCR", fontWeight = FontWeight.Bold) },
                actions = {
                    FilledTonalButton(
                        onClick = { imagePicker.launch("image/*") },
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Icon(Icons.Default.AddPhotoAlternate, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Import Image")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (rawBitmap == null) {
                // Empty view with scanner prompt
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.DocumentScanner,
                            contentDescription = null,
                            modifier = Modifier.size(90.dp),
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                        )
                        Text(
                            text = "Scan Paper Documents with Edge Detection & OCR",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Automatic quadrilateral detection, perspective flattening, and hidden OCR text layer embedding (MakeACopy & FairScan architecture).",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(
                            onClick = { imagePicker.launch("image/*") },
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.CameraAlt, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Capture or Import Document")
                        }
                    }
                }
            } else {
                // Main editor workspace
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    val currentDisplay = warpedBitmap ?: rawBitmap!!

                    Image(
                        bitmap = currentDisplay.asImageBitmap(),
                        contentDescription = "Document Scan",
                        modifier = Modifier.fillMaxSize()
                    )

                    // Draw interactive corner handles if not yet warped
                    if (warpedBitmap == null && corners != null) {
                        val currentCorners = corners!!
                        InteractiveCropOverlay(
                            corners = currentCorners,
                            bitmapWidth = rawBitmap!!.width.toFloat(),
                            bitmapHeight = rawBitmap!!.height.toFloat(),
                            onCornersChanged = { corners = it }
                        )
                    }

                    if (isProcessing) {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                Text(statusText, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }

                // Bottom toolbar for adjustments and Searchable PDF generation
                Surface(
                    tonalElevation = 4.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (warpedBitmap == null) {
                            // Stage 1: Crop and Warp
                            Button(
                                onClick = {
                                    val bmp = rawBitmap ?: return@Button
                                    val c = corners ?: return@Button
                                    val warped = corrector.warpPerspective(bmp, c)
                                    val filtered = corrector.applyFilter(warped, activeFilter)
                                    warpedBitmap = filtered
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.Default.CropFree, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Correct Perspective & Warp")
                            }
                        } else {
                            // Stage 2: Filter selection & OCR Searchable PDF creation
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                FilterChip(
                                    selected = activeFilter == ScanFilter.MAGIC_COLOR,
                                    onClick = {
                                        activeFilter = ScanFilter.MAGIC_COLOR
                                        warpedBitmap = corrector.applyFilter(warpedBitmap!!, ScanFilter.MAGIC_COLOR)
                                    },
                                    label = { Text("Magic Color") }
                                )
                                FilterChip(
                                    selected = activeFilter == ScanFilter.GRAYSCALE,
                                    onClick = {
                                        activeFilter = ScanFilter.GRAYSCALE
                                        warpedBitmap = corrector.applyFilter(warpedBitmap!!, ScanFilter.GRAYSCALE)
                                    },
                                    label = { Text("Grayscale") }
                                )
                                FilterChip(
                                    selected = activeFilter == ScanFilter.BLACK_WHITE,
                                    onClick = {
                                        activeFilter = ScanFilter.BLACK_WHITE
                                        warpedBitmap = corrector.applyFilter(warpedBitmap!!, ScanFilter.BLACK_WHITE)
                                    },
                                    label = { Text("B&W") }
                                )
                            }

                            Button(
                                onClick = {
                                    coroutineScope.launch {
                                        isProcessing = true
                                        statusText = "Running OCR & creating Searchable PDF..."

                                        val finalBmp = warpedBitmap ?: return@launch
                                        val ocrWords = ocrEngine.recognizeText(finalBmp)

                                        val scannedPage = ScannedPage(
                                            id = UUID.randomUUID().toString(),
                                            rawImage = rawBitmap!!,
                                            corners = corners!!,
                                            processedImage = finalBmp,
                                            filter = activeFilter,
                                            ocrWords = ocrWords
                                        )

                                        val outFile = File(context.cacheDir, "scan_searchable_${System.currentTimeMillis()}.pdf")
                                        val success = searchablePdfGen.createSearchablePdf(
                                            pages = listOf(scannedPage),
                                            outputFile = outFile
                                        )

                                        isProcessing = false
                                        statusText = ""

                                        if (success) {
                                            Toast.makeText(context, "Searchable PDF generated with ${ocrWords.size} OCR tokens!", Toast.LENGTH_LONG).show()
                                            onPdfCreated(outFile)
                                        } else {
                                            Toast.makeText(context, "Failed to create searchable PDF", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.Default.AutoAwesome, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Generate Searchable PDF (OCR Layer)")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun InteractiveCropOverlay(
    corners: QuadCorners,
    bitmapWidth: Float,
    bitmapHeight: Float,
    onCornersChanged: (QuadCorners) -> Unit
) {
    Canvas(
        modifier = Modifier.fillMaxSize()
    ) {
        val scaleX = size.width / bitmapWidth
        val scaleY = size.height / bitmapHeight

        val pTL = Offset(corners.topLeft.x * scaleX, corners.topLeft.y * scaleY)
        val pTR = Offset(corners.topRight.x * scaleX, corners.topRight.y * scaleY)
        val pBR = Offset(corners.bottomRight.x * scaleX, corners.bottomRight.y * scaleY)
        val pBL = Offset(corners.bottomLeft.x * scaleX, corners.bottomLeft.y * scaleY)

        // Draw boundary polygon path
        val path = Path().apply {
            moveTo(pTL.x, pTL.y)
            lineTo(pTR.x, pTR.y)
            lineTo(pBR.x, pBR.y)
            lineTo(pBL.x, pBL.y)
            close()
        }

        drawPath(
            path = path,
            color = Color(0xFF00E676),
            style = Stroke(width = 4.dp.toPx())
        )

        // Draw corner pins
        val pinRadius = 14.dp.toPx()
        val pinColor = Color(0xFF00E676)
        val pinCenterColor = Color.White

        listOf(pTL, pTR, pBR, pBL).forEach { p ->
            drawCircle(color = pinColor, radius = pinRadius, center = p)
            drawCircle(color = pinCenterColor, radius = pinRadius * 0.45f, center = p)
        }
    }
}
