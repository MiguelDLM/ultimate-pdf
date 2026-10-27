package org.ultimatepdf.ui.settings

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.ultimatepdf.R
import org.ultimatepdf.core.updater.AppUpdateManager
import org.ultimatepdf.core.updater.ReleaseInfo
import org.ultimatepdf.ui.components.formatBytes
import java.io.File

private enum class UpdateUiState {
    IDLE,
    DOWNLOADING,
    DOWNLOADED,
    ERROR
}

@Composable
fun UpdateDialog(
    release: ReleaseInfo,
    currentVersion: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var state by remember { mutableStateOf(UpdateUiState.IDLE) }
    var bytesDownloaded by remember { mutableLongStateOf(0L) }
    var totalBytes by remember { mutableLongStateOf(release.apkSize) }
    var downloadedFile by remember { mutableStateOf<File?>(null) }
    var errorMessage by remember { mutableStateOf("") }
    var hasInstallPermission by remember { mutableStateOf(AppUpdateManager.canInstallPackages(context)) }
    var downloadJob by remember { mutableStateOf<Job?>(null) }

    // When the user returns from settings (where they granted install permission), detect it
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val allowed = AppUpdateManager.canInstallPackages(context)
                hasInstallPermission = allowed
                if (allowed && downloadedFile != null && state == UpdateUiState.DOWNLOADED) {
                    AppUpdateManager.installApk(context, downloadedFile!!)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    fun startDownload() {
        val url = release.apkDownloadUrl
        if (url == null) {
            // No direct APK found; open GitHub release in browser
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(release.releaseUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            return
        }

        state = UpdateUiState.DOWNLOADING
        bytesDownloaded = 0L
        downloadJob = scope.launch {
            val result = AppUpdateManager.downloadApk(
                context = context,
                downloadUrl = url,
                onProgress = { downloaded, total ->
                    bytesDownloaded = downloaded
                    if (total > 0) totalBytes = total
                }
            )
            result.onSuccess { file ->
                downloadedFile = file
                state = UpdateUiState.DOWNLOADED
                if (AppUpdateManager.canInstallPackages(context)) {
                    AppUpdateManager.installApk(context, file)
                } else {
                    AppUpdateManager.openInstallPermissionSettings(context)
                }
            }.onFailure { err ->
                errorMessage = err.localizedMessage ?: "Download failed"
                state = UpdateUiState.ERROR
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        AppUpdateManager.cleanupUpdateFiles(context)
        state = UpdateUiState.IDLE
    }

    AlertDialog(
        onDismissRequest = {
            if (state != UpdateUiState.DOWNLOADING) {
                onDismiss()
            }
        },
        icon = {
            when (state) {
                UpdateUiState.DOWNLOADED -> Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                UpdateUiState.ERROR -> Icon(Icons.Default.ErrorOutline, null, tint = MaterialTheme.colorScheme.error)
                else -> Icon(Icons.Default.SystemUpdate, null, tint = MaterialTheme.colorScheme.primary)
            }
        },
        title = {
            Text(
                stringResource(R.string.update_dialog_title),
                style = MaterialTheme.typography.headlineSmall
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.update_dialog_new_version, release.versionName),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        stringResource(R.string.update_dialog_current_version, currentVersion),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (release.apkSize > 0) {
                    Text(
                        stringResource(R.string.update_dialog_size, formatBytes(release.apkSize)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (release.releaseNotes.isNotBlank()) {
                    Text(
                        stringResource(R.string.update_dialog_changelog),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 160.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                            .padding(8.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            release.releaseNotes,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                when (state) {
                    UpdateUiState.DOWNLOADING -> {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.update_downloading),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        val progress = if (totalBytes > 0) (bytesDownloaded.toFloat() / totalBytes).coerceIn(0f, 1f) else null
                        if (progress != null) {
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                            )
                            Text(
                                stringResource(
                                    R.string.update_download_progress,
                                    formatBytes(bytesDownloaded),
                                    formatBytes(totalBytes),
                                    (progress * 100).toInt()
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                            )
                            Text(
                                formatBytes(bytesDownloaded),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    UpdateUiState.DOWNLOADED -> {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.update_download_complete),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium
                        )
                        if (!hasInstallPermission) {
                            Text(
                                stringResource(R.string.update_permission_required),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }

                    UpdateUiState.ERROR -> {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.update_download_failed, errorMessage),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    UpdateUiState.IDLE -> {
                        if (release.apkDownloadUrl == null) {
                            Text(
                                stringResource(R.string.update_no_apk),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            when (state) {
                UpdateUiState.IDLE -> {
                    if (release.apkDownloadUrl != null) {
                        Button(onClick = { startDownload() }) {
                            Text(stringResource(R.string.update_download_install))
                        }
                    } else {
                        Button(onClick = {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(release.releaseUrl)).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(intent)
                        }) {
                            Text(stringResource(R.string.update_view_on_github))
                        }
                    }
                }

                UpdateUiState.DOWNLOADING -> {
                    OutlinedButton(onClick = { cancelDownload() }) {
                        Text(stringResource(R.string.update_cancel))
                    }
                }

                UpdateUiState.DOWNLOADED -> {
                    if (!hasInstallPermission) {
                        Button(onClick = { AppUpdateManager.openInstallPermissionSettings(context) }) {
                            Text(stringResource(R.string.update_grant_permission))
                        }
                    } else {
                        Button(onClick = {
                            downloadedFile?.let { AppUpdateManager.installApk(context, it) }
                        }) {
                            Text(stringResource(R.string.update_install_now))
                        }
                    }
                }

                UpdateUiState.ERROR -> {
                    Button(onClick = { startDownload() }) {
                        Text(stringResource(R.string.update_retry))
                    }
                }
            }
        },
        dismissButton = {
            if (state == UpdateUiState.IDLE) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(release.releaseUrl)).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                    }) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.update_view_on_github))
                    }
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.update_later))
                    }
                }
            } else if (state != UpdateUiState.DOWNLOADING) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.update_later))
                }
            }
        }
    )
}
