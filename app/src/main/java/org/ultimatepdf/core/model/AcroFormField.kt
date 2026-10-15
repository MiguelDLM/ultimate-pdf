package org.ultimatepdf.core.model

enum class AcroFieldType {
    TEXT,
    CHECKBOX,
    RADIO_BUTTON,
    COMBOBOX,
    LISTBOX,
    SIGNATURE
}

/**
 * Representation of an interactive form field inside a PDF (AcroForms).
 */
data class AcroFormField(
    val fullyQualifiedName: String,
    val partialName: String,
    val fieldType: AcroFieldType,
    var currentValue: String,
    val options: List<String> = emptyList(), // For combobox / radio options
    val isReadOnly: Boolean = false,
    val isRequired: Boolean = false,
    val pageIndex: Int = 0,
    val bounds: PdfRect? = null
)
