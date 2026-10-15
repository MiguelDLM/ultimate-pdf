package org.ultimatepdf.ui.settings

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import org.ultimatepdf.core.signature.SignatureLibrary
import org.ultimatepdf.core.signature.StampLibrary
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import org.ultimatepdf.R
import org.ultimatepdf.core.settings.AppLanguage
import org.ultimatepdf.core.settings.ThemeMode
import org.ultimatepdf.ui.components.ScreenTopBar

@Composable
fun SettingsScreen(
    theme: ThemeMode,
    language: AppLanguage,
    signatures: SignatureLibrary,
    stamps: StampLibrary,
    onTheme: (ThemeMode) -> Unit,
    onLanguage: (AppLanguage) -> Unit,
    onBack: () -> Unit
) {
    // 0 = the settings list, 1 = signatures, 2 = stamps, 3 = about.
    var page by rememberSaveable { mutableIntStateOf(0) }
    BackHandler(enabled = page != 0) { page = 0 }
    when (page) {
        1 -> return SignaturesScreen(signatures) { page = 0 }
        2 -> return StampsScreen(stamps) { page = 0 }
        3 -> return AboutScreen { page = 0 }
    }
    Scaffold(topBar = { ScreenTopBar(stringResource(R.string.settings_title), onBack) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            SettingsSection(stringResource(R.string.settings_theme)) {
                listOf(
                    ThemeMode.SYSTEM to R.string.settings_theme_system,
                    ThemeMode.LIGHT to R.string.settings_theme_light,
                    ThemeMode.DARK to R.string.settings_theme_dark
                ).forEach { (mode, label) -> ChoiceRow(label, selected = theme == mode) { onTheme(mode) } }
            }
            SettingsSection(stringResource(R.string.settings_language)) {
                ChoiceRow(R.string.settings_language_system, selected = language == AppLanguage.SYSTEM) { onLanguage(AppLanguage.SYSTEM) }
                // Language names are shown in their own language so they're recognizable either way.
                ChoiceRow("English", selected = language == AppLanguage.ENGLISH) { onLanguage(AppLanguage.ENGLISH) }
                ChoiceRow("Español", selected = language == AppLanguage.SPANISH) { onLanguage(AppLanguage.SPANISH) }
            }
            SettingsSection(stringResource(R.string.settings_library)) {
                LinkRow(
                    title = stringResource(R.string.settings_signatures),
                    subtitle = pluralStringResource(R.plurals.signatures_count, signatures.items.size, signatures.items.size)
                ) { page = 1 }
                LinkRow(
                    title = stringResource(R.string.settings_stamps),
                    subtitle = pluralStringResource(R.plurals.stamps_count, stamps.items.size, stamps.items.size)
                ) { page = 2 }
            }
            SettingsSection(stringResource(R.string.settings_about)) {
                LinkRow(
                    title = stringResource(R.string.about_title),
                    subtitle = stringResource(R.string.about_version, appVersionName(LocalContext.current))
                ) { page = 3 }
            }
        }
    }
}

@Composable
private fun LinkRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 4.dp)
    )
    Column(Modifier.selectableGroup(), content = content)
}

@Composable
private fun ChoiceRow(@StringRes label: Int, selected: Boolean, onClick: () -> Unit) =
    ChoiceRow(stringResource(label), selected, onClick)

@Composable
private fun ChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(horizontal = 24.dp, vertical = 14.dp)
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        RadioButton(selected = selected, onClick = null)
    }
}
