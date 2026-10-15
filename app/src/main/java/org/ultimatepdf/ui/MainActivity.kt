package org.ultimatepdf.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.ultimatepdf.core.model.DocumentModel
import org.ultimatepdf.core.pdf.DigitalSignatureService
import org.ultimatepdf.core.pdf.EditPlanProcessor
import org.ultimatepdf.core.pdf.PdfEngine
import org.ultimatepdf.core.pdf.PdfSecurityManager
import org.ultimatepdf.ui.annotation.AnnotationScreen
import org.ultimatepdf.ui.editor.PdfEditorScreen
import org.ultimatepdf.ui.forms.AcroFormsScreen
import org.ultimatepdf.ui.navigation.AppDestination
import org.ultimatepdf.ui.scanner.DocumentScannerScreen
import org.ultimatepdf.ui.sign.DigitalSignScreen
import org.ultimatepdf.ui.theme.UltimatePdfTheme
import org.ultimatepdf.ui.tools.PdfToolsScreen
import org.ultimatepdf.ui.viewer.PdfViewerScreen
import java.io.File

class MainActivity : ComponentActivity() {

    private lateinit var pdfEngine: PdfEngine
    private lateinit var editPlanProcessor: EditPlanProcessor
    private lateinit var securityManager: PdfSecurityManager
    private lateinit var signatureService: DigitalSignatureService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        pdfEngine = PdfEngine(applicationContext)
        editPlanProcessor = EditPlanProcessor(applicationContext)
        securityManager = PdfSecurityManager(applicationContext)
        signatureService = DigitalSignatureService(applicationContext)

        val initialUri = extractPdfUri(intent)

        setContent {
            UltimatePdfTheme {
                val coroutineScope = rememberCoroutineScope()
                var currentDocument by remember { mutableStateOf<DocumentModel?>(null) }
                var currentDestination by remember { mutableStateOf(AppDestination.VIEWER) }

                // Always start in Simple Reader Mode (isEditMode = false)
                var isEditMode by remember { mutableStateOf(false) }

                val configuration = LocalConfiguration.current
                val isTablet = configuration.screenWidthDp >= 600

                // Open initial PDF if launched from an external intent
                LaunchedEffect(initialUri) {
                    initialUri?.let { uri ->
                        try {
                            val doc = pdfEngine.openDocument(uri)
                            currentDocument = doc
                            isEditMode = false // Always open as simple reader
                            currentDestination = AppDestination.VIEWER
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    if (isTablet) {
                        // Tablet UI: Left Navigation Rail (Visible only in Edit Mode) + Content Area
                        Row(modifier = Modifier.fillMaxSize()) {
                            if (isEditMode) {
                                NavigationRail(
                                    modifier = Modifier.fillMaxHeight(),
                                    containerColor = MaterialTheme.colorScheme.surface
                                ) {
                                    Spacer(Modifier.height(16.dp))
                                    AppDestination.values().forEach { dest ->
                                        NavigationRailItem(
                                            selected = currentDestination == dest,
                                            onClick = { currentDestination = dest },
                                            icon = { Icon(dest.icon, contentDescription = dest.title) },
                                            label = { Text(dest.title, maxLines = 1) }
                                        )
                                    }
                                }
                            }

                            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                                RenderScreenContent(
                                    destination = currentDestination,
                                    currentDocument = currentDocument,
                                    pdfEngine = pdfEngine,
                                    editPlanProcessor = editPlanProcessor,
                                    securityManager = securityManager,
                                    signatureService = signatureService,
                                    onDocumentLoaded = { currentDocument = it },
                                    isEditMode = isEditMode,
                                    onToggleEditMode = {
                                        isEditMode = !isEditMode
                                        if (!isEditMode) {
                                            currentDestination = AppDestination.VIEWER
                                        }
                                    },
                                    onNavigateToTool = { toolRoute ->
                                        AppDestination.values().find { it.route == toolRoute }?.let {
                                            currentDestination = it
                                        }
                                    },
                                    isTablet = true
                                )
                            }
                        }
                    } else {
                        // Phone UI: Simple Reader by default. Navigation Bar appears only in Edit Mode
                        Scaffold(
                            bottomBar = {
                                AnimatedVisibility(
                                    visible = isEditMode,
                                    enter = slideInVertically(initialOffsetY = { it }),
                                    exit = slideOutVertically(targetOffsetY = { it })
                                ) {
                                    NavigationBar(
                                        containerColor = MaterialTheme.colorScheme.surface
                                    ) {
                                        AppDestination.values().forEach { dest ->
                                            NavigationBarItem(
                                                selected = currentDestination == dest,
                                                onClick = { currentDestination = dest },
                                                icon = { Icon(dest.icon, contentDescription = dest.title) },
                                                label = { Text(dest.title, maxLines = 1) }
                                            )
                                        }
                                    }
                                }
                            }
                        ) { innerPadding ->
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(innerPadding)
                            ) {
                                RenderScreenContent(
                                    destination = currentDestination,
                                    currentDocument = currentDocument,
                                    pdfEngine = pdfEngine,
                                    editPlanProcessor = editPlanProcessor,
                                    securityManager = securityManager,
                                    signatureService = signatureService,
                                    onDocumentLoaded = { currentDocument = it },
                                    isEditMode = isEditMode,
                                    onToggleEditMode = {
                                        isEditMode = !isEditMode
                                        if (!isEditMode) {
                                            currentDestination = AppDestination.VIEWER
                                        }
                                    },
                                    onNavigateToTool = { toolRoute ->
                                        AppDestination.values().find { it.route == toolRoute }?.let {
                                            currentDestination = it
                                        }
                                    },
                                    isTablet = false
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun RenderScreenContent(
        destination: AppDestination,
        currentDocument: DocumentModel?,
        pdfEngine: PdfEngine,
        editPlanProcessor: EditPlanProcessor,
        securityManager: PdfSecurityManager,
        signatureService: DigitalSignatureService,
        onDocumentLoaded: (DocumentModel) -> Unit,
        isEditMode: Boolean,
        onToggleEditMode: () -> Unit,
        onNavigateToTool: (String) -> Unit,
        isTablet: Boolean
    ) {
        val coroutineScope = rememberCoroutineScope()

        when (destination) {
            AppDestination.VIEWER -> {
                PdfViewerScreen(
                    currentDocument = currentDocument,
                    pdfEngine = pdfEngine,
                    onDocumentLoaded = onDocumentLoaded,
                    isEditMode = isEditMode,
                    onToggleEditMode = onToggleEditMode,
                    onNavigateToTool = onNavigateToTool,
                    isTablet = isTablet
                )
            }

            AppDestination.EDITOR -> {
                PdfEditorScreen(
                    currentDocument = currentDocument,
                    editPlanProcessor = editPlanProcessor,
                    isTablet = isTablet
                )
            }

            AppDestination.SCANNER -> {
                DocumentScannerScreen(
                    onPdfCreated = { file: File ->
                        coroutineScope.launch {
                            val uri = Uri.fromFile(file)
                            val doc = pdfEngine.openDocument(uri)
                            onDocumentLoaded(doc)
                        }
                    },
                    isTablet = isTablet
                )
            }

            AppDestination.ANNOTATE -> {
                AnnotationScreen(
                    currentDocument = currentDocument,
                    pdfEngine = pdfEngine,
                    isTablet = isTablet
                )
            }

            AppDestination.FORMS -> {
                AcroFormsScreen(
                    currentDocument = currentDocument,
                    pdfEngine = pdfEngine,
                    isTablet = isTablet
                )
            }

            AppDestination.SIGN -> {
                DigitalSignScreen(
                    currentDocument = currentDocument,
                    signatureService = signatureService,
                    isTablet = isTablet
                )
            }

            AppDestination.TOOLS -> {
                PdfToolsScreen(
                    currentDocument = currentDocument,
                    securityManager = securityManager,
                    isTablet = isTablet
                )
            }
        }
    }

    private fun extractPdfUri(intent: Intent?): Uri? {
        if (intent == null) return null
        return when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
            else -> null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        pdfEngine.close()
    }
}
