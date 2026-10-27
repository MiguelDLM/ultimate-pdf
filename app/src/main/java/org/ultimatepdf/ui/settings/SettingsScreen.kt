package org.ultimatepdf.ui.settings

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.HistoryEdu
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.ultimatepdf.R
import org.ultimatepdf.core.settings.AppLanguage
import org.ultimatepdf.core.settings.ThemeMode
import org.ultimatepdf.core.settings.UpdateFrequency
import org.ultimatepdf.core.signature.SignatureLibrary
import org.ultimatepdf.core.signature.StampLibrary
import org.ultimatepdf.ui.components.ScreenTopBar

@Composable
fun SettingsScreen(
    theme: ThemeMode,
    language: AppLanguage,
    autoUpdateEnabled: Boolean,
    updateFrequency: UpdateFrequency,
    lastUpdateCheckTime: Long,
    signatures: SignatureLibrary,
    stamps: StampLibrary,
    isCheckingUpdates: Boolean,
    onTheme: (ThemeMode) -> Unit,
    onLanguage: (AppLanguage) -> Unit,
    onAutoUpdateChange: (Boolean) -> Unit,
    onUpdateFrequencyChange: (UpdateFrequency) -> Unit,
    onCheckForUpdates: () -> Unit,
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
                .padding(vertical = 12.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SettingsSection(stringResource(R.string.settings_theme), icon = Icons.Default.Palette) {
                listOf(
                    ThemeMode.SYSTEM to R.string.settings_theme_system,
                    ThemeMode.LIGHT to R.string.settings_theme_light,
                    ThemeMode.DARK to R.string.settings_theme_dark
                ).forEachIndexed { index, (mode, label) ->
                    if (index > 0) SectionDivider()
                    ChoiceRow(label, selected = theme == mode) { onTheme(mode) }
                }
            }

            SettingsSection(stringResource(R.string.settings_language), icon = Icons.Default.Language) {
                ChoiceRow(R.string.settings_language_system, selected = language == AppLanguage.SYSTEM) {
                    onLanguage(AppLanguage.SYSTEM)
                }
                SectionDivider()
                // Language names are shown in their own language so they're recognizable either way.
                ChoiceRow("English", selected = language == AppLanguage.ENGLISH) {
                    onLanguage(AppLanguage.ENGLISH)
                }
                SectionDivider()
                ChoiceRow("Español", selected = language == AppLanguage.SPANISH) {
                    onLanguage(AppLanguage.SPANISH)
                }
            }

            SettingsSection(stringResource(R.string.settings_updates), icon = Icons.Default.SystemUpdate) {
                SwitchRow(
                    title = stringResource(R.string.settings_auto_updates),
                    subtitle = stringResource(R.string.settings_auto_updates_desc),
                    checked = autoUpdateEnabled,
                    onCheckedChange = onAutoUpdateChange
                )
                if (autoUpdateEnabled) {
                    SectionDivider()
                    Text(
                        stringResource(R.string.settings_updates_frequency),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 4.dp)
                    )
                    ChoiceRow(
                        label = stringResource(R.string.settings_updates_frequency_daily),
                        selected = updateFrequency == UpdateFrequency.DAILY
                    ) { onUpdateFrequencyChange(UpdateFrequency.DAILY) }
                    SectionDivider()
                    ChoiceRow(
                        label = stringResource(R.string.settings_updates_frequency_weekly),
                        selected = updateFrequency == UpdateFrequency.WEEKLY
                    ) { onUpdateFrequencyChange(UpdateFrequency.WEEKLY) }
                }
                SectionDivider()
                ActionRow(
                    title = stringResource(R.string.settings_check_updates_now),
                    subtitle = if (lastUpdateCheckTime <= 0L) {
                        stringResource(R.string.settings_last_check, stringResource(R.string.settings_last_check_never))
                    } else {
                        stringResource(R.string.settings_last_check, formatLastCheckTime(LocalContext.current, lastUpdateCheckTime))
                    },
                    trailing = {
                        if (isCheckingUpdates) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                        }
                    },
                    onClick = onCheckForUpdates
                )
            }

            SettingsSection(stringResource(R.string.settings_library), icon = Icons.Default.HistoryEdu) {
                LinkRow(
                    title = stringResource(R.string.settings_signatures),
                    subtitle = pluralStringResource(R.plurals.signatures_count, signatures.items.size, signatures.items.size)
                ) { page = 1 }
                SectionDivider()
                LinkRow(
                    title = stringResource(R.string.settings_stamps),
                    subtitle = pluralStringResource(R.plurals.stamps_count, stamps.items.size, stamps.items.size)
                ) { page = 2 }
            }

            SettingsSection(stringResource(R.string.settings_about), icon = Icons.Default.Info) {
                LinkRow(
                    title = stringResource(R.string.about_title),
                    subtitle = stringResource(R.string.about_version, appVersionName(LocalContext.current))
                ) { page = 3 }
            }
        }
    }
}

private fun formatLastCheckTime(context: Context, timestamp: Long): String {
    if (timestamp <= 0L) return context.getString(R.string.settings_last_check_never)
    val date = java.util.Date(timestamp)
    val dateFormat = android.text.format.DateFormat.getMediumDateFormat(context)
    val timeFormat = android.text.format.DateFormat.getTimeFormat(context)
    return "${dateFormat.format(date)} ${timeFormat.format(date)}"
}

@Composable
private fun SectionDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    )
}

@Composable
private fun SettingsSection(
    title: String,
    icon: ImageVector? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 6.dp, bottom = 8.dp)
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
        }

        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            ),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectableGroup(),
                content = content
            )
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(16.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
private fun ActionRow(
    title: String,
    subtitle: String,
    trailing: @Composable () -> Unit = {},
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing()
    }
}

@Composable
private fun LinkRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
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
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        RadioButton(selected = selected, onClick = null)
    }
}
