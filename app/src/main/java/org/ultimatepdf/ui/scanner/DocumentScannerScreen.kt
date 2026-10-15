package org.ultimatepdf.ui.scanner

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import org.ultimatepdf.R
import org.ultimatepdf.core.model.ScanFilter
import org.ultimatepdf.core.pdf.ScanOutputPage
import org.ultimatepdf.core.pdf.SearchablePdfGenerator
import org.ultimatepdf.core.scanner.OcrEngine
import org.ultimatepdf.core.scanner.ScanMode
import org.ultimatepdf.core.scanner.ScanPipeline
import org.ultimatepdf.core.signature.SignatureImages
import org.ultimatepdf.ui.components.ScreenTopBar
import org.ultimatepdf.ui.components.rememberSaveFileLauncher
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Document scanner: live camera with edge detection, batch capture, then review (crop,
 * filters, rotation) and save as a searchable PDF the user files wherever they want.
 */
@Composable
fun DocumentScannerScreen(
    onPdfCreated: (Uri) -> Unit,
    onBack: () -> Unit,
    @Suppress("UNUSED_PARAMETER") isTablet: Boolean = false
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pipeline = remember { ScanPipeline() }
    val ocr = remember { OcrEngine(context) }
    val session = remember { ScanSession(File(context.cacheDir, "scan_${System.currentTimeMillis()}"), pipeline) }
    var reviewing by rememberSaveable { mutableStateOf(false) }
    var progress by remember { mutableStateOf<String?>(null) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var hasCamera by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }

    val requestCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasCamera = it }
    LaunchedEffect(Unit) { if (!hasCamera) requestCamera.launch(Manifest.permission.CAMERA) }

    val importImages = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            progress = context.getString(R.string.scan_loading)
            for (uri in uris) SignatureImages.loadUpright(context, uri, MAX_PHOTO_SIZE)?.let { session.add(it) }
            progress = null
            if (session.captures.isNotEmpty()) reviewing = true
        }
    }

    val saveLauncher = rememberSaveFileLauncher { uri ->
        progress = null
        if (uri != null) {
            session.clear()
            onPdfCreated(uri)
        }
    }

    fun leave() {
        if (session.captures.isEmpty()) onBack() else confirmDiscard = true
    }

    fun save() {
        scope.launch {
            val captures = session.captures.toList()
            val pages = mutableListOf<ScanOutputPage>()
            try {
                captures.forEachIndexed { i, capture ->
                    progress = context.getString(R.string.scan_processing, i + 1, captures.size)
                    pipeline.process(capture.file, capture.width, capture.corners, capture.filter, capture.rotation, session.mode, MAX_PHOTO_SIZE, capture.filterIntensity)
                        .forEach { pages += ScanOutputPage(it, null, capture.filter == ScanFilter.BLACK_WHITE) }
                }
                val output = if (session.mode == ScanMode.ID_CARD && pages.size == 2) {
                    listOf(ScanOutputPage(pipeline.composeIdCard(pages[0].image, pages[1].image), null))
                } else {
                    pages
                }
                val finished = if (session.ocrEnabled) {
                    output.mapIndexed { i, page ->
                        progress = context.getString(R.string.scan_recognizing, i + 1, output.size)
                        val text = runCatching { ocr.recognize(page.image) }.getOrNull()
                        ScanOutputPage(page.image, text, page.isBlackAndWhite)
                    }
                } else {
                    output
                }
                progress = context.getString(R.string.scan_running)
                val file = File(context.cacheDir, "scan_${System.currentTimeMillis()}.pdf")
                SearchablePdfGenerator().createSearchablePdf(finished, file)
                finished.forEach { it.image.recycle() }
                val name = SimpleDateFormat("yyyy-MM-dd HH.mm", Locale.getDefault()).format(Date())
                saveLauncher.save(context.getString(R.string.scan_file_name, name), file)
            } catch (e: Exception) {
                progress = null
                Toast.makeText(context, R.string.scan_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    BackHandler {
        when {
            progress != null -> Unit
            reviewing -> reviewing = false
            else -> leave()
        }
    }

    when {
        reviewing && session.captures.isNotEmpty() -> ScanReview(
            session = session,
            pipeline = pipeline,
            onAddMore = { reviewing = false },
            onSave = ::save,
            onBack = { reviewing = false }
        )
        hasCamera -> ScanCamera(
            session = session,
            onCaptured = { photo ->
                scope.launch {
                    session.add(photo)
                    photo.recycle()
                    // An ID card is done after its back side.
                    if (session.mode == ScanMode.ID_CARD && session.captures.size == 2) reviewing = true
                }
            },
            onImport = { importImages.launch("image/*") },
            onReview = { reviewing = true },
            onClose = ::leave
        )
        else -> NoCameraPermission(
            onGrant = { requestCamera.launch(Manifest.permission.CAMERA) },
            onImport = { importImages.launch("image/*") },
            onBack = ::leave
        )
    }

    progress?.let { message ->
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(28.dp))
                    Spacer(Modifier.width(16.dp))
                    Text(message)
                }
            }
        )
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.scan_discard_title)) },
            text = { Text(stringResource(R.string.scan_discard_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    session.clear()
                    onBack()
                }) { Text(stringResource(R.string.discard), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.cancel)) } }
        )
    }
}

@Composable
private fun NoCameraPermission(onGrant: () -> Unit, onImport: () -> Unit, onBack: () -> Unit) {
    Scaffold(topBar = { ScreenTopBar(stringResource(R.string.tool_scan_short), onBack) }) { padding ->
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(32.dp)
        ) {
            Text(stringResource(R.string.scan_camera_needed), textAlign = TextAlign.Center)
            Button(onClick = onGrant) { Text(stringResource(R.string.scan_allow_camera)) }
            OutlinedButton(onClick = onImport) { Text(stringResource(R.string.scan_import)) }
        }
    }
}
