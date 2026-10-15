package org.ultimatepdf.ui.forms

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DynamicForm
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.ultimatepdf.core.model.AcroFieldType
import org.ultimatepdf.core.model.AcroFormField
import org.ultimatepdf.core.model.DocumentModel
import org.ultimatepdf.core.pdf.PdfEngine
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AcroFormsScreen(
    currentDocument: DocumentModel?,
    pdfEngine: PdfEngine,
    isTablet: Boolean = false
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var formFields by remember { mutableStateOf<List<AcroFormField>>(emptyList()) }
    val fieldValues = remember { mutableStateMapOf<String, String>() }
    var isLoading by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }

    LaunchedEffect(currentDocument) {
        if (currentDocument != null) {
            isLoading = true
            try {
                val extracted = pdfEngine.extractAcroForms(currentDocument.uri)
                formFields = extracted
                fieldValues.clear()
                extracted.forEach { field ->
                    fieldValues[field.fullyQualifiedName] = field.currentValue
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                isLoading = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Interactive Forms (AcroForms)", fontWeight = FontWeight.Bold) },
                actions = {
                    Button(
                        onClick = {
                            if (currentDocument != null) {
                                coroutineScope.launch {
                                    isSaving = true
                                    val outFile = File(context.cacheDir, "form_filled_${System.currentTimeMillis()}.pdf")
                                    val ok = pdfEngine.saveFormValues(
                                        sourceUri = currentDocument.uri,
                                        fieldValues = fieldValues.toMap(),
                                        outputFile = outFile
                                    )
                                    isSaving = false
                                    if (ok) {
                                        Toast.makeText(context, "Saved filled form to ${outFile.name}", Toast.LENGTH_LONG).show()
                                    } else {
                                        Toast.makeText(context, "Error saving form", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        },
                        enabled = !isSaving && formFields.isNotEmpty(),
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        if (isSaving) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = MaterialTheme.colorScheme.onPrimary)
                        } else {
                            Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Save Form")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (currentDocument == null) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Open a PDF in the Viewer tab to detect and fill AcroForms.")
                }
            } else if (isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else if (formFields.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.DynamicForm,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "No interactive AcroForm fields detected in this PDF.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.DynamicForm, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    text = "Found ${formFields.size} form field(s). Edit the values below and tap 'Save Form'.",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }

                    items(formFields) { field ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = field.partialName,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold
                                )

                                when (field.fieldType) {
                                    AcroFieldType.TEXT -> {
                                        OutlinedTextField(
                                            value = fieldValues[field.fullyQualifiedName] ?: "",
                                            onValueChange = { fieldValues[field.fullyQualifiedName] = it },
                                            modifier = Modifier.fillMaxWidth(),
                                            label = { Text(field.fullyQualifiedName) },
                                            readOnly = field.isReadOnly
                                        )
                                    }

                                    AcroFieldType.CHECKBOX -> {
                                        val isChecked = fieldValues[field.fullyQualifiedName].equals("Yes", ignoreCase = true) ||
                                                fieldValues[field.fullyQualifiedName].equals("On", ignoreCase = true)
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text("Checked", style = MaterialTheme.typography.bodyMedium)
                                            Switch(
                                                checked = isChecked,
                                                onCheckedChange = { checked ->
                                                    fieldValues[field.fullyQualifiedName] = if (checked) "Yes" else "Off"
                                                },
                                                enabled = !field.isReadOnly
                                            )
                                        }
                                    }

                                    AcroFieldType.RADIO_BUTTON, AcroFieldType.COMBOBOX -> {
                                        Column {
                                            field.options.forEach { opt ->
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    modifier = Modifier.fillMaxWidth()
                                                ) {
                                                    RadioButton(
                                                        selected = fieldValues[field.fullyQualifiedName] == opt,
                                                        onClick = { fieldValues[field.fullyQualifiedName] = opt },
                                                        enabled = !field.isReadOnly
                                                    )
                                                    Spacer(Modifier.width(8.dp))
                                                    Text(opt, style = MaterialTheme.typography.bodyMedium)
                                                }
                                            }
                                        }
                                    }

                                    else -> {
                                        OutlinedTextField(
                                            value = fieldValues[field.fullyQualifiedName] ?: "",
                                            onValueChange = { fieldValues[field.fullyQualifiedName] = it },
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
