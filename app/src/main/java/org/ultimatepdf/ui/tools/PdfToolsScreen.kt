package org.ultimatepdf.ui.tools

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.ultimatepdf.R
import org.ultimatepdf.core.pdf.CompressionLevel
import org.ultimatepdf.core.pdf.CompressionResult
import org.ultimatepdf.core.pdf.ExtractedTable
import org.ultimatepdf.core.pdf.PageStandard
import org.ultimatepdf.core.pdf.PdfPageOps
import org.ultimatepdf.core.pdf.PdfSecurityManager
import org.ultimatepdf.core.pdf.PdfThumbnails
import org.ultimatepdf.core.pdf.TableDataExtractor
import org.ultimatepdf.ui.components.ScreenTopBar
import org.ultimatepdf.ui.components.rememberSaveFileLauncher
import org.ultimatepdf.ui.components.rememberSaveToFolderLauncher
import org.ultimatepdf.ui.navigation.AppDestination
import java.io.File

enum class TextExtractFormat {
    PLAIN_TEXT,
    CSV,
    HTML
}

/** Single-file tools: protect, unlock, extract text/tables, compress, resize, extract images. */
@Composable
fun PdfToolsScreen(
    tool: AppDestination,
    initialSource: Uri?,
    thumbnails: PdfThumbnails,
    securityManager: PdfSecurityManager,
    pageOps: PdfPageOps? = null,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var source by remember { mutableStateOf<SourceInfo?>(null) }
    var isWorking by remember { mutableStateOf(false) }

    val saveFile = rememberSaveFileLauncher { uri ->
        isWorking = false
        if (uri != null) Toast.makeText(context, R.string.saved_title, Toast.LENGTH_SHORT).show()
    }
    val saveText = rememberSaveFileLauncher(mimeType = "text/plain") { uri ->
        if (uri != null) Toast.makeText(context, R.string.saved_title, Toast.LENGTH_SHORT).show()
    }
    val saveCsv = rememberSaveFileLauncher(mimeType = "text/csv") { uri ->
        if (uri != null) Toast.makeText(context, R.string.saved_title, Toast.LENGTH_SHORT).show()
    }
    val saveHtml = rememberSaveFileLauncher(mimeType = "text/html") { uri ->
        if (uri != null) Toast.makeText(context, R.string.saved_title, Toast.LENGTH_SHORT).show()
    }
    val saveFolder = rememberSaveToFolderLauncher { saved, folder ->
        isWorking = false
        if (saved > 0) {
            Toast.makeText(context, context.resources.getQuantityString(R.plurals.images_saved, saved, saved, folder ?: ""), Toast.LENGTH_LONG).show()
        }
    }

    SourcePicker(initialSource, thumbnails, onSource = { source = it }) { pick ->
        Scaffold(topBar = { ScreenTopBar(stringResource(tool.shortTitle), onBack) }) { padding ->
            val info = source
            if (info == null) {
                EmptySource(padding, pick)
                return@Scaffold
            }
            val baseName = info.name.removeSuffix(".pdf")
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                SourceCard(info, thumbnails, onChange = pick)
                when (tool) {
                    AppDestination.COMPRESS -> {
                        var level by remember { mutableStateOf(CompressionLevel.MEDIUM) }
                        var resultInfo by remember { mutableStateOf<CompressionResult?>(null) }
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(stringResource(R.string.compress_level), style = MaterialTheme.typography.titleSmall)
                            val levels = listOf(
                                CompressionLevel.LOW to stringResource(R.string.compress_low),
                                CompressionLevel.MEDIUM to stringResource(R.string.compress_medium),
                                CompressionLevel.HIGH to stringResource(R.string.compress_high)
                            )
                            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                                levels.forEachIndexed { i, (option, label) ->
                                    SegmentedButton(
                                        selected = level == option,
                                        onClick = { level = option },
                                        shape = SegmentedButtonDefaults.itemShape(i, levels.size)
                                    ) { Text(label, maxLines = 1) }
                                }
                            }
                            if (resultInfo != null) {
                                val r = resultInfo!!
                                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                                    Text(
                                        stringResource(R.string.compress_saved, r.savingsPercent),
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.padding(16.dp)
                                    )
                                }
                            }
                            Button(
                                onClick = {
                                    scope.launch {
                                        isWorking = true
                                        val out = File(context.cacheDir, "compressed_${System.currentTimeMillis()}.pdf")
                                        val res = securityManager.compressDocument(info.uri, level, out)
                                        resultInfo = res
                                        saveFile.save(context.getString(R.string.compress_file_name, baseName), out)
                                    }
                                },
                                enabled = !isWorking,
                                modifier = Modifier.fillMaxWidth().height(52.dp)
                            ) {
                                Text(stringResource(R.string.compress_action))
                            }
                        }
                    }

                    AppDestination.RESIZE -> {
                        var standard by remember { mutableStateOf(PageStandard.A4) }
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(stringResource(R.string.resize_standard), style = MaterialTheme.typography.titleSmall)
                            val standards = listOf(
                                Triple(PageStandard.A4, "A4", stringResource(R.string.standard_a4)),
                                Triple(PageStandard.LETTER, stringResource(R.string.standard_letter_label), stringResource(R.string.standard_letter)),
                                Triple(PageStandard.LEGAL, stringResource(R.string.standard_legal_label), stringResource(R.string.standard_legal))
                            )
                            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                                standards.forEachIndexed { i, (option, label, _) ->
                                    SegmentedButton(
                                        selected = standard == option,
                                        onClick = { standard = option },
                                        shape = SegmentedButtonDefaults.itemShape(i, standards.size)
                                    ) { Text(label) }
                                }
                            }
                            val standardDesc = standards.firstOrNull { it.first == standard }?.third ?: ""
                            Text(standardDesc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Button(
                                onClick = {
                                    scope.launch {
                                        if (pageOps == null) return@launch
                                        isWorking = true
                                        val out = File(context.cacheDir, "resized_${System.currentTimeMillis()}.pdf")
                                        if (pageOps.resizePages(info.uri, standard, outputFile = out)) {
                                            saveFile.save(context.getString(R.string.resize_file_name, baseName), out)
                                        } else {
                                            isWorking = false
                                            Toast.makeText(context, R.string.save_failed, Toast.LENGTH_LONG).show()
                                        }
                                    }
                                },
                                enabled = !isWorking && pageOps != null,
                                modifier = Modifier.fillMaxWidth().height(52.dp)
                            ) {
                                Text(stringResource(R.string.resize_action))
                            }
                        }
                    }

                    AppDestination.PROTECT -> ProtectForm(
                        action = stringResource(R.string.protect_action),
                        isWorking = isWorking
                    ) { userPass, ownerPass, canPrint, canCopy ->
                        scope.launch {
                            isWorking = true
                            val out = File(context.cacheDir, "protected_${System.currentTimeMillis()}.pdf")
                            val effectiveOwner = if (ownerPass.isNotBlank()) ownerPass else userPass
                            if (securityManager.encryptDocument(info.uri, userPass, effectiveOwner, canPrint, canCopy, outputFile = out)) {
                                saveFile.save(context.getString(R.string.protect_file_name, baseName), out)
                            } else {
                                isWorking = false
                                Toast.makeText(context, R.string.save_failed, Toast.LENGTH_LONG).show()
                            }
                        }
                    }

                    AppDestination.UNLOCK -> PasswordForm(
                        confirm = false,
                        action = stringResource(R.string.unlock_action),
                        isWorking = isWorking
                    ) { password ->
                        scope.launch {
                            isWorking = true
                            val out = File(context.cacheDir, "unlocked_${System.currentTimeMillis()}.pdf")
                            if (securityManager.decryptDocument(info.uri, password, out)) {
                                saveFile.save(context.getString(R.string.unlock_file_name, baseName), out)
                            } else {
                                isWorking = false
                                Toast.makeText(context, R.string.unlock_failed, Toast.LENGTH_LONG).show()
                            }
                        }
                    }

                    AppDestination.EXTRACT_TEXT -> {
                        var format by remember { mutableStateOf(TextExtractFormat.PLAIN_TEXT) }
                        var plainText by remember(info.uri) { mutableStateOf<String?>(null) }
                        var tables by remember(info.uri) { mutableStateOf<List<ExtractedTable>?>(null) }

                        LaunchedEffect(info.uri) {
                            plainText = securityManager.extractText(info.uri)
                            tables = TableDataExtractor(context).extractTablesFromPdf(info.uri)
                        }

                        val currentResult = when (format) {
                            TextExtractFormat.PLAIN_TEXT -> plainText
                            TextExtractFormat.CSV -> tables?.joinToString("\n\n") { it.toCsv() }
                            TextExtractFormat.HTML -> tables?.joinToString("\n") { it.toHtml() }
                        }

                        if (currentResult == null) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        } else if (currentResult.isBlank()) {
                            Notice(stringResource(R.string.extract_text_empty), isError = true)
                        } else {
                            Text(stringResource(R.string.extract_mode), style = MaterialTheme.typography.titleSmall)
                            val formats = listOf(
                                TextExtractFormat.PLAIN_TEXT to stringResource(R.string.extract_as_txt),
                                TextExtractFormat.CSV to stringResource(R.string.extract_as_csv),
                                TextExtractFormat.HTML to stringResource(R.string.extract_as_html)
                            )
                            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                                formats.forEachIndexed { i, (option, label) ->
                                    SegmentedButton(
                                        selected = format == option,
                                        onClick = { format = option },
                                        shape = SegmentedButtonDefaults.itemShape(i, formats.size)
                                    ) { Text(label, maxLines = 1) }
                                }
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilledTonalButton(onClick = { copyText(context, currentResult) }) { Text(stringResource(R.string.copy)) }
                                FilledTonalButton(onClick = {
                                    when (format) {
                                        TextExtractFormat.PLAIN_TEXT -> {
                                            val out = File(context.cacheDir, "text_${System.currentTimeMillis()}.txt").apply { writeText(currentResult) }
                                            saveText.save("$baseName.txt", out)
                                        }
                                        TextExtractFormat.CSV -> {
                                            val out = File(context.cacheDir, "table_${System.currentTimeMillis()}.csv").apply { writeText(currentResult) }
                                            saveCsv.save("$baseName.csv", out)
                                        }
                                        TextExtractFormat.HTML -> {
                                            val out = File(context.cacheDir, "table_${System.currentTimeMillis()}.html").apply { writeText(currentResult) }
                                            saveHtml.save("$baseName.html", out)
                                        }
                                    }
                                }) { Text(stringResource(R.string.save)) }
                            }
                            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                                SelectionContainer {
                                    Text(currentResult, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
                                }
                            }
                        }
                    }

                    AppDestination.EXTRACT_IMAGES -> {
                        var images by remember(info.uri) { mutableStateOf<List<File>?>(null) }
                        LaunchedEffect(info.uri) {
                            val dir = File(context.cacheDir, "images_${System.currentTimeMillis()}").apply { mkdirs() }
                            images = securityManager.extractImages(info.uri, dir)
                        }
                        val found = images
                        when {
                            found == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                            found.isEmpty() -> Notice(stringResource(R.string.extract_images_empty), isError = true)
                            else -> {
                                Text(pluralStringResource(R.plurals.images_found, found.size, found.size), style = MaterialTheme.typography.titleSmall)
                                Button(
                                    onClick = {
                                        isWorking = true
                                        saveFolder.save(found)
                                    },
                                    enabled = !isWorking,
                                    modifier = Modifier.fillMaxWidth().height(52.dp)
                                ) { Text(stringResource(R.string.save_to_folder)) }
                            }
                        }
                    }

                    else -> Unit
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun ProtectForm(
    action: String,
    isWorking: Boolean,
    onSubmit: (userPass: String, ownerPass: String, canPrint: Boolean, canCopy: Boolean) -> Unit
) {
    var userPassword by remember { mutableStateOf("") }
    var repeatPassword by remember { mutableStateOf("") }
    var ownerPassword by remember { mutableStateOf("") }
    var canPrint by remember { mutableStateOf(true) }
    var canCopy by remember { mutableStateOf(true) }
    val mismatch = repeatPassword.isNotEmpty() && repeatPassword != userPassword

    OutlinedTextField(
        value = userPassword,
        onValueChange = { userPassword = it },
        label = { Text(stringResource(R.string.password)) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    )
    OutlinedTextField(
        value = repeatPassword,
        onValueChange = { repeatPassword = it },
        label = { Text(stringResource(R.string.password_repeat)) },
        singleLine = true,
        isError = mismatch,
        supportingText = if (mismatch) ({ Text(stringResource(R.string.password_mismatch)) }) else null,
        visualTransformation = PasswordVisualTransformation(),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    )
    OutlinedTextField(
        value = ownerPassword,
        onValueChange = { ownerPassword = it },
        label = { Text(stringResource(R.string.protect_owner_password)) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    )
    Text(stringResource(R.string.protect_permissions), style = MaterialTheme.typography.titleSmall)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.protect_can_print), modifier = Modifier.weight(1f))
        Switch(checked = canPrint, onCheckedChange = { canPrint = it })
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.protect_can_copy), modifier = Modifier.weight(1f))
        Switch(checked = canCopy, onCheckedChange = { canCopy = it })
    }
    Button(
        onClick = { onSubmit(userPassword, ownerPassword, canPrint, canCopy) },
        enabled = userPassword.isNotEmpty() && repeatPassword == userPassword && !isWorking,
        modifier = Modifier.fillMaxWidth().height(52.dp)
    ) { Text(action) }
}

@Composable
private fun PasswordForm(confirm: Boolean, action: String, isWorking: Boolean, onSubmit: (String) -> Unit) {
    var password by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    val mismatch = confirm && repeat.isNotEmpty() && repeat != password
    OutlinedTextField(
        value = password,
        onValueChange = { password = it },
        label = { Text(stringResource(R.string.password)) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    )
    if (confirm) {
        OutlinedTextField(
            value = repeat,
            onValueChange = { repeat = it },
            label = { Text(stringResource(R.string.password_repeat)) },
            singleLine = true,
            isError = mismatch,
            supportingText = if (mismatch) ({ Text(stringResource(R.string.password_mismatch)) }) else null,
            visualTransformation = PasswordVisualTransformation(),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        )
    }
    Button(
        onClick = { onSubmit(password) },
        enabled = password.isNotEmpty() && (!confirm || repeat == password) && !isWorking,
        modifier = Modifier.fillMaxWidth().height(52.dp)
    ) { Text(action) }
}

private fun copyText(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("PDF text", text))
    Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show()
}
