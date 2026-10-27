package org.ultimatepdf.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.lifecycle.lifecycleScope
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import kotlinx.coroutines.launch
import org.ultimatepdf.R
import org.ultimatepdf.core.model.DocumentModel
import org.ultimatepdf.core.pdf.DigitalSignatureService
import org.ultimatepdf.core.pdf.EditPlanProcessor
import org.ultimatepdf.core.pdf.PdfAnnotationStore
import org.ultimatepdf.core.pdf.PdfEngine
import org.ultimatepdf.core.export.PdfExporter
import org.ultimatepdf.core.pdf.PdfContentEditor
import org.ultimatepdf.core.signature.SignatureLibrary
import org.ultimatepdf.core.signature.StampLibrary
import org.ultimatepdf.core.pdf.TableDataExtractor
import org.ultimatepdf.core.scanner.OcrEngine
import org.ultimatepdf.ui.tools.ExportScreen
import org.ultimatepdf.core.pdf.PdfPageOps
import org.ultimatepdf.core.pdf.PdfSecurityManager
import org.ultimatepdf.core.pdf.PdfTextExtractor
import org.ultimatepdf.core.pdf.PdfThumbnails
import org.ultimatepdf.core.settings.AppSettings
import org.ultimatepdf.core.settings.RecentDocuments
import org.ultimatepdf.core.settings.ThemeMode
import org.ultimatepdf.core.updater.AppUpdateManager
import org.ultimatepdf.core.updater.ReleaseInfo
import org.ultimatepdf.core.updater.UpdateCheckResult
import org.ultimatepdf.ui.components.copyToUri
import org.ultimatepdf.ui.components.rememberPdfPicker
import org.ultimatepdf.ui.components.rememberSaveFileLauncher
import org.ultimatepdf.ui.editor.ContentEditorScreen
import org.ultimatepdf.ui.editor.PdfEditorScreen
import org.ultimatepdf.ui.forms.AcroFormsScreen
import org.ultimatepdf.ui.home.HomeScreen
import org.ultimatepdf.ui.home.RecentsScreen
import org.ultimatepdf.ui.navigation.AppDestination
import org.ultimatepdf.ui.scanner.DocumentScannerScreen
import org.ultimatepdf.ui.settings.SettingsScreen
import org.ultimatepdf.ui.settings.UpdateDialog
import org.ultimatepdf.ui.settings.appVersionName
import org.ultimatepdf.ui.sign.DigitalSignScreen
import org.ultimatepdf.ui.theme.UltimatePdfTheme
import org.ultimatepdf.ui.tools.ComposerMode
import org.ultimatepdf.ui.tools.PageComposerScreen
import org.ultimatepdf.ui.tools.PdfToolsScreen
import org.ultimatepdf.ui.tools.SplitScreen
import org.ultimatepdf.ui.viewer.AnnotationSession
import org.ultimatepdf.ui.viewer.PdfViewerScreen
import java.io.File

/**
 * Navigation: Home (no document) → Reader (with its edit mode) → full-screen tools.
 * System back walks this stack in reverse instead of closing the app.
 */
class MainActivity : ComponentActivity() {

    private lateinit var settings: AppSettings
    private lateinit var recents: RecentDocuments
    private lateinit var pdfEngine: PdfEngine
    private lateinit var textExtractor: PdfTextExtractor
    private lateinit var annotationStore: PdfAnnotationStore
    private lateinit var thumbnails: PdfThumbnails
    private lateinit var pageOps: PdfPageOps
    private lateinit var editPlanProcessor: EditPlanProcessor
    private lateinit var securityManager: PdfSecurityManager
    private lateinit var contentEditor: PdfContentEditor
    private lateinit var exporter: PdfExporter
    private lateinit var signatureLibrary: SignatureLibrary
    private lateinit var stampLibrary: StampLibrary
    private lateinit var signatureService: DigitalSignatureService

    private var incomingUri by mutableStateOf<Uri?>(null)

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppSettings.wrapWithLanguage(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        settings = AppSettings(this)
        recents = RecentDocuments(this)
        pdfEngine = PdfEngine(applicationContext)
        textExtractor = PdfTextExtractor(applicationContext)
        annotationStore = PdfAnnotationStore(applicationContext)
        thumbnails = PdfThumbnails(applicationContext)
        pageOps = PdfPageOps(applicationContext)
        editPlanProcessor = EditPlanProcessor(applicationContext)
        securityManager = PdfSecurityManager(applicationContext)
        signatureService = DigitalSignatureService(applicationContext)
        contentEditor = PdfContentEditor(applicationContext)
        signatureLibrary = SignatureLibrary(applicationContext)
        stampLibrary = StampLibrary(applicationContext)
        lifecycleScope.launch {
            signatureLibrary.load { n -> getString(R.string.signature_default_name, n) }
            stampLibrary.load()
        }
        exporter = PdfExporter(applicationContext, contentEditor, OcrEngine(applicationContext), thumbnails, TableDataExtractor(applicationContext))

        if (savedInstanceState == null) incomingUri = extractPdfUri(intent)

        setContent {
            val dark = when (settings.themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            UltimatePdfTheme(darkTheme = dark) { App() }
        }
    }

    @Composable
    private fun App() {
        val coroutineScope = rememberCoroutineScope()
        val isTablet = LocalConfiguration.current.screenWidthDp >= 600

        // The URI survives configuration changes; the engine reopens it after recreation.
        var documentUri by rememberSaveable { mutableStateOf<Uri?>(null) }
        var document by remember { mutableStateOf<DocumentModel?>(null) }
        var isEditMode by rememberSaveable { mutableStateOf(false) }
        var openTool by rememberSaveable { mutableStateOf<AppDestination?>(null) }
        // Document a file tool starts from (the open one when launched from the reader).
        var toolSource by rememberSaveable { mutableStateOf<Uri?>(null) }
        var isLoading by remember { mutableStateOf(false) }
        val session = remember { AnnotationSession() }

        var availableUpdate by remember { mutableStateOf<ReleaseInfo?>(null) }
        var isCheckingUpdates by remember { mutableStateOf(false) }

        fun performManualUpdateCheck() {
            if (isCheckingUpdates) return
            coroutineScope.launch {
                isCheckingUpdates = true
                val currentVer = appVersionName(this@MainActivity)
                val result = AppUpdateManager.checkForUpdate(currentVer)
                settings.recordUpdateChecked()
                isCheckingUpdates = false
                when (result) {
                    is UpdateCheckResult.Available -> {
                        availableUpdate = result.release
                    }
                    is UpdateCheckResult.UpToDate -> {
                        Toast.makeText(
                            this@MainActivity,
                            getString(R.string.update_up_to_date, currentVer),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    is UpdateCheckResult.Error -> {
                        Toast.makeText(
                            this@MainActivity,
                            getString(R.string.update_check_failed, result.message),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }

        // Automatic periodic update check on launch
        LaunchedEffect(Unit) {
            if (settings.autoUpdateEnabled) {
                val now = System.currentTimeMillis()
                val elapsed = now - settings.lastUpdateCheckTime
                if (elapsed >= settings.updateFrequency.intervalMillis) {
                    val currentVer = appVersionName(this@MainActivity)
                    val result = AppUpdateManager.checkForUpdate(currentVer)
                    settings.recordUpdateChecked(now)
                    if (result is UpdateCheckResult.Available) {
                        availableUpdate = result.release
                    }
                }
            }
        }

        fun showDocument(doc: DocumentModel) {
            document = doc
            documentUri = doc.uri
            recents.record(doc.uri, doc.name, doc.pageCount, doc.sizeBytes)
        }

        fun openDocument(uri: Uri) {
            coroutineScope.launch {
                isLoading = true
                try {
                    textExtractor.invalidate()
                    session.discard()
                    showDocument(pdfEngine.openDocument(uri))
                    isEditMode = false
                    openTool = null
                } catch (e: Exception) {
                    documentUri = null
                    recents.remove(uri)
                    Toast.makeText(this@MainActivity, R.string.error_open_pdf, Toast.LENGTH_LONG).show()
                } finally {
                    isLoading = false
                }
            }
        }

        fun closeDocument() {
            pdfEngine.close()
            coroutineScope.launch { textExtractor.invalidate() }
            session.discard()
            document = null
            documentUri = null
            isEditMode = false
        }

        fun openToolFor(destination: AppDestination, source: Uri?) {
            toolSource = source
            openTool = destination
        }

        val pickPdf = rememberPdfPicker(::openDocument)

        // When the open document can't be overwritten, its new version is saved as a copy.
        val saveCopy = rememberSaveFileLauncher { uri -> uri?.let(::openDocument) }

        /** Replaces the open document's content with [file] and reopens it. */
        suspend fun replaceDocument(file: File): Boolean {
            val doc = document ?: return false
            pdfEngine.close()
            textExtractor.invalidate()
            thumbnails.invalidate(doc.uri)
            val overwritten = runCatching { copyToUri(this@MainActivity, file, doc.uri) }.isSuccess
            openTool = null
            if (overwritten) {
                file.delete()
                showDocument(pdfEngine.openDocument(doc.uri))
            } else {
                showDocument(pdfEngine.openDocument(doc.uri))
                saveCopy.save(doc.name, file)
            }
            return true
        }

        LaunchedEffect(incomingUri) {
            incomingUri?.let {
                incomingUri = null
                openDocument(it)
            }
        }
        LaunchedEffect(Unit) {
            // Restore after rotation / process recreation.
            val uri = documentUri
            if (document == null && uri != null) {
                try {
                    document = pdfEngine.openDocument(uri)
                } catch (e: Exception) {
                    documentUri = null
                }
            }
        }

        BackHandler(enabled = openTool != null) { openTool = null }

        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            val currentDocument = document
            val onBack = { openTool = null }
            when (val tool = openTool) {
                AppDestination.EXPORT -> ExportScreen(
                    initialSource = toolSource,
                    thumbnails = thumbnails,
                    exporter = exporter,
                    onBack = onBack
                )
                AppDestination.EDIT_CONTENT -> ContentEditorScreen(
                    currentDocument = currentDocument,
                    editor = contentEditor,
                    thumbnails = thumbnails,
                    onSave = ::replaceDocument,
                    onBack = onBack
                )
                AppDestination.ORGANIZE -> PdfEditorScreen(
                    currentDocument = currentDocument,
                    editPlanProcessor = editPlanProcessor,
                    thumbnails = thumbnails,
                    onSave = ::replaceDocument,
                    onBack = onBack,
                    isTablet = isTablet
                )
                AppDestination.FORMS -> AcroFormsScreen(
                    currentDocument = currentDocument,
                    pdfEngine = pdfEngine,
                    onSaved = ::replaceDocument,
                    onBack = onBack,
                    isTablet = isTablet
                )
                AppDestination.CERTIFICATE -> DigitalSignScreen(
                    currentDocument = currentDocument,
                    signatureService = signatureService,
                    onSigned = ::replaceDocument,
                    onBack = onBack,
                    isTablet = isTablet
                )
                AppDestination.SCANNER -> DocumentScannerScreen(
                    onPdfCreated = ::openDocument,
                    onBack = onBack,
                    isTablet = isTablet
                )
                AppDestination.MERGE, AppDestination.EXTRACT_PAGES -> PageComposerScreen(
                    mode = if (tool == AppDestination.MERGE) ComposerMode.MERGE else ComposerMode.EXTRACT,
                    initialSources = listOfNotNull(toolSource),
                    thumbnails = thumbnails,
                    pageOps = pageOps,
                    onOpenResult = ::openDocument,
                    onBack = onBack
                )
                AppDestination.SPLIT -> SplitScreen(
                    initialSource = toolSource,
                    thumbnails = thumbnails,
                    pageOps = pageOps,
                    onBack = onBack
                )
                AppDestination.COMPRESS, AppDestination.RESIZE,
                AppDestination.PROTECT, AppDestination.UNLOCK,
                AppDestination.EXTRACT_TEXT, AppDestination.EXTRACT_IMAGES -> PdfToolsScreen(
                    tool = tool,
                    initialSource = toolSource,
                    thumbnails = thumbnails,
                    securityManager = securityManager,
                    pageOps = pageOps,
                    onBack = onBack
                )
                AppDestination.RECENTS -> RecentsScreen(
                    recents = recents.items,
                    thumbnails = thumbnails,
                    onOpen = { openDocument(it.uri) },
                    onRemove = { recents.remove(it.uri) },
                    onClearAll = recents::clear,
                    onBack = onBack
                )
                AppDestination.SETTINGS -> SettingsScreen(
                    theme = settings.themeMode,
                    language = settings.language,
                    autoUpdateEnabled = settings.autoUpdateEnabled,
                    updateFrequency = settings.updateFrequency,
                    lastUpdateCheckTime = settings.lastUpdateCheckTime,
                    signatures = signatureLibrary,
                    stamps = stampLibrary,
                    isCheckingUpdates = isCheckingUpdates,
                    onTheme = settings::updateTheme,
                    onLanguage = { language ->
                        if (language != settings.language) {
                            settings.updateLanguage(language)
                            recreate()
                        }
                    },
                    onAutoUpdateChange = settings::updateAutoUpdateEnabled,
                    onUpdateFrequencyChange = settings::updateFrequency,
                    onCheckForUpdates = ::performManualUpdateCheck,
                    onBack = onBack
                )
                null -> if (currentDocument == null) {
                    HomeScreen(
                        isLoading = isLoading || documentUri != null,
                        recents = recents.items,
                        thumbnails = thumbnails,
                        onOpenPdf = pickPdf,
                        onOpenRecent = { openDocument(it.uri) },
                        onRemoveRecent = { recents.remove(it.uri) },
                        onTool = { openToolFor(it, null) }
                    )
                } else {
                    PdfViewerScreen(
                        document = currentDocument,
                        pdfEngine = pdfEngine,
                        textExtractor = textExtractor,
                        thumbnails = thumbnails,
                        annotationStore = annotationStore,
                        signatureLibrary = signatureLibrary,
                        stampLibrary = stampLibrary,
                        session = session,
                        isEditMode = isEditMode,
                        onEditModeChange = { isEditMode = it },
                        onClose = ::closeDocument,
                        onOpenAnother = pickPdf,
                        onNavigateToTool = { openToolFor(it, currentDocument.uri) },
                        onDocumentReloaded = ::showDocument,
                        isTablet = isTablet
                    )
                }
            }

            availableUpdate?.let { release ->
                UpdateDialog(
                    release = release,
                    currentVersion = appVersionName(this@MainActivity),
                    onDismiss = { availableUpdate = null }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        extractPdfUri(intent)?.let { incomingUri = it }
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
