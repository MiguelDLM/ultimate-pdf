package org.ultimatepdf.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.ultimatepdf.R
import org.ultimatepdf.ui.components.ScreenTopBar

private class Release(val version: String, val date: String, @StringRes val items: List<Int>)

/** Released versions, newest first. Add an entry here (and its strings) with every release. */
private val Changelog = listOf(
    Release("1.4", "2026-11-04", listOf(
        R.string.changelog_1_4_password
    )),
    Release("1.3", "2026-11-03", listOf(
        R.string.changelog_1_3_filters,
        R.string.changelog_1_3_quality,
        R.string.changelog_1_3_crop,
        R.string.changelog_1_3_straighten,
        R.string.changelog_1_3_share_images,
        R.string.changelog_1_3_reprocess,
        R.string.changelog_1_3_details,
        R.string.changelog_1_3_fixes
    )),
    Release("1.2", "2026-10-27", listOf(
        R.string.changelog_1_2_updates,
        R.string.changelog_1_2_settings,
        R.string.changelog_1_2_share
    )),
    Release("1.0", "2026-10-15", listOf(
        R.string.changelog_1_0_reader,
        R.string.changelog_1_0_signatures,
        R.string.changelog_1_0_scanner,
        R.string.changelog_1_0_edit,
        R.string.changelog_1_0_files
    ))
)

/** What changed in each released version. */
@Composable
fun ChangelogScreen(onBack: () -> Unit) {
    Scaffold(topBar = { ScreenTopBar(stringResource(R.string.about_changelog), onBack) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 8.dp)
        ) {
            Changelog.forEachIndexed { index, release ->
                if (index > 0) HorizontalDivider(Modifier.padding(vertical = 16.dp))
                Row(verticalAlignment = androidx.compose.ui.Alignment.Bottom) {
                    Text(stringResource(R.string.about_version, release.version), style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.width(12.dp))
                    Text(release.date, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 3.dp))
                }
                Spacer(Modifier.height(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    release.items.forEach { item ->
                        Row {
                            Text("•  ", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(stringResource(item), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}
