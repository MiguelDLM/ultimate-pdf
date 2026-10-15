package org.ultimatepdf.ui.settings

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.ultimatepdf.R
import org.ultimatepdf.ui.components.ScreenTopBar

const val PRODUCER_URL = "https://museovirtualnacional.com"
const val SOURCE_URL = "https://github.com/MiguelDLM/ultimate-pdf"

private class Credit(val name: String, @StringRes val role: Int, val license: String, val url: String)

/** Open-source projects whose ideas and design shaped the app. */
private val Inspirations = listOf(
    Credit("MakeACopy", R.string.about_role_makeacopy, "Apache-2.0", "https://github.com/egdels/makeacopy"),
    Credit("FairScan", R.string.about_role_fairscan, "GPL-3.0", "https://github.com/pynicolas/FairScan"),
    Credit("AnonPDF", R.string.about_role_anonpdf, "MIT", "https://github.com/FanFeast/anonpdf"),
    Credit("Images to PDF", R.string.about_role_images_to_pdf, "GPL-3.0", "https://github.com/Swati4star/Images-to-PDF"),
    Credit("Open PDF Studio", R.string.about_role_open_pdf_studio, "LGPL-3.0", "https://github.com/OpenAEC-Foundation/open-pdf-studio"),
    Credit("Open PDF Sign", R.string.about_role_open_pdf_sign, "Apache-2.0", "https://github.com/open-pdf-sign/open-pdf-sign"),
    Credit("MuPDF", R.string.about_role_mupdf, "AGPL-3.0", "https://github.com/ArtifexSoftware/mupdf-android-viewer"),
    Credit("PdfiumAndroid", R.string.about_role_pdfium, "Apache-2.0", "https://github.com/oothp/PdfiumAndroid")
)

/** Libraries and assets that ship inside the app. */
private val Components = listOf(
    Credit("PdfBox-Android", R.string.about_role_pdfbox, "Apache-2.0", "https://github.com/TomRoush/PdfBox-Android"),
    Credit("Bouncy Castle", R.string.about_role_bouncycastle, "Bouncy Castle (MIT)", "https://www.bouncycastle.org/licence.html"),
    Credit("OpenCV", R.string.about_role_opencv, "Apache-2.0", "https://opencv.org/license/"),
    Credit("Google ML Kit", R.string.about_role_mlkit, "ML Kit Terms", "https://developers.google.com/ml-kit/terms"),
    Credit("Jetpack Compose · Material 3", R.string.about_role_compose, "Apache-2.0", "https://developer.android.com/jetpack/compose"),
    Credit("CameraX", R.string.about_role_camerax, "Apache-2.0", "https://developer.android.com/training/camerax"),
    Credit("Kotlin · Coroutines", R.string.about_role_kotlin, "Apache-2.0", "https://kotlinlang.org"),
    Credit("Liberation Fonts", R.string.about_role_liberation, "SIL OFL 1.1", "https://github.com/liberationfonts/liberation-fonts"),
    Credit("Material Icons", R.string.about_role_icons, "Apache-2.0", "https://fonts.google.com/icons")
)

/** The installed version name, e.g. "1.0". */
fun appVersionName(context: Context): String =
    runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: ""

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    Scaffold(topBar = { ScreenTopBar(stringResource(R.string.about_title), onBack) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
        ) {
            Column(Modifier.padding(horizontal = 24.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
                Text(
                    stringResource(R.string.about_version, appVersionName(context)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            HorizontalDivider()
            AboutLink(
                title = stringResource(R.string.about_producer),
                subtitle = stringResource(R.string.about_produced_by) + " · museovirtualnacional.com",
                onClick = { uriHandler.openUri(PRODUCER_URL) }
            )
            AboutLink(
                title = stringResource(R.string.about_source_code),
                subtitle = "github.com/MiguelDLM/ultimate-pdf",
                onClick = { uriHandler.openUri(SOURCE_URL) }
            )
            CreditSection(R.string.about_based_on, R.string.about_based_note, Inspirations) { uriHandler.openUri(it) }
            CreditSection(R.string.about_components, R.string.about_components_note, Components) { uriHandler.openUri(it) }
            Text(
                stringResource(R.string.about_license_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp)
            )
        }
    }
}

@Composable
private fun CreditSection(@StringRes title: Int, @StringRes note: Int, credits: List<Credit>, onOpen: (String) -> Unit) {
    Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 4.dp)) {
        Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    credits.forEach { credit ->
        AboutLink(
            title = credit.name,
            subtitle = stringResource(credit.role) + " · " + credit.license,
            onClick = { onOpen(credit.url) }
        )
    }
}

@Composable
private fun AboutLink(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 12.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Filled.OpenInNew, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
    }
}
