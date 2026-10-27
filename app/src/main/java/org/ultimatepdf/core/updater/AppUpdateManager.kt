package org.ultimatepdf.core.updater

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class ReleaseInfo(
    val tagName: String,
    val versionName: String,
    val releaseName: String,
    val releaseNotes: String,
    val releaseUrl: String,
    val apkDownloadUrl: String?,
    val apkSize: Long
)

sealed interface UpdateCheckResult {
    data class Available(val release: ReleaseInfo) : UpdateCheckResult
    data object UpToDate : UpdateCheckResult
    data class Error(val message: String) : UpdateCheckResult
}

object AppUpdateManager {
    const val GITHUB_OWNER = "MiguelDLM"
    const val GITHUB_REPO = "ultimate-pdf"
    private const val LATEST_RELEASE_API =
        "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"
    const val RELEASES_PAGE_URL = "https://github.com/$GITHUB_OWNER/$GITHUB_REPO/releases"

    /**
     * Checks GitHub releases for a newer version than [currentVersion].
     */
    suspend fun checkForUpdate(currentVersion: String): UpdateCheckResult = withContext(Dispatchers.IO) {
        try {
            val url = URL(LATEST_RELEASE_API)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github.v3+json")
                setRequestProperty("User-Agent", "UltimatePdf-Android")
                connectTimeout = 10_000
                readTimeout = 10_000
            }

            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_NOT_FOUND) {
                return@withContext UpdateCheckResult.UpToDate
            }
            if (responseCode != HttpURLConnection.HTTP_OK) {
                val errorMsg = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                return@withContext UpdateCheckResult.Error("HTTP $responseCode: ${connection.responseMessage} $errorMsg".trim())
            }

            val jsonText = connection.inputStream.bufferedReader().use { it.readText() }
            val root = JSONObject(jsonText)

            val tagName = root.optString("tag_name", "").trim()
            val releaseName = root.optString("name", tagName).trim()
            val releaseNotes = root.optString("body", "").trim()
            val releaseUrl = root.optString("html_url", RELEASES_PAGE_URL)

            val remoteVersion = tagName.removePrefix("v").removePrefix("V").trim()
            if (remoteVersion.isBlank()) {
                return@withContext UpdateCheckResult.UpToDate
            }

            // Look for APK asset attached to this release
            var apkUrl: String? = null
            var apkSize = 0L
            val assets = root.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val assetName = asset.optString("name", "")
                    val contentType = asset.optString("content_type", "")
                    if (assetName.endsWith(".apk", ignoreCase = true) ||
                        contentType == "application/vnd.android.package-archive"
                    ) {
                        apkUrl = asset.optString("browser_download_url").ifBlank { null }
                        apkSize = asset.optLong("size", 0L)
                        // If multiple APKs exist, prefer arm64-v8a or first matching apk
                        if (assetName.contains("arm64-v8a", ignoreCase = true)) {
                            break
                        }
                    }
                }
            }

            if (isNewerVersion(current = currentVersion, remote = remoteVersion)) {
                UpdateCheckResult.Available(
                    ReleaseInfo(
                        tagName = tagName,
                        versionName = remoteVersion,
                        releaseName = releaseName.ifBlank { "v$remoteVersion" },
                        releaseNotes = releaseNotes,
                        releaseUrl = releaseUrl,
                        apkDownloadUrl = apkUrl,
                        apkSize = apkSize
                    )
                )
            } else {
                UpdateCheckResult.UpToDate
            }
        } catch (e: Exception) {
            UpdateCheckResult.Error(e.localizedMessage ?: "Network error")
        }
    }

    /**
     * Splits version strings like "1.0.3" or "v2.1" into numeric segments: [1, 0, 3].
     */
    fun parseVersionNumbers(version: String): List<Int> {
        val clean = version.trim().removePrefix("v").removePrefix("V")
        val numericPart = clean.substringBefore("-").substringBefore("+")
        return numericPart.split(".")
            .mapNotNull { it.trim().toIntOrNull() }
    }

    /**
     * Returns true if [remote] represents a newer version than [current].
     */
    fun isNewerVersion(current: String, remote: String): Boolean {
        val currentParts = parseVersionNumbers(current)
        val remoteParts = parseVersionNumbers(remote)
        val maxLen = maxOf(currentParts.size, remoteParts.size)
        for (i in 0 until maxLen) {
            val curr = currentParts.getOrElse(i) { 0 }
            val rem = remoteParts.getOrElse(i) { 0 }
            if (rem > curr) return true
            if (rem < curr) return false
        }
        return false
    }

    /**
     * Downloads the APK file from [downloadUrl] to app storage, reporting progress.
     * Follows HTTP redirects (e.g. GitHub Releases -> AWS S3 / Azure Blob).
     */
    suspend fun downloadApk(
        context: Context,
        downloadUrl: String,
        onProgress: (bytesDownloaded: Long, totalBytes: Long) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        val updateDir = context.getExternalFilesDir("updates") ?: File(context.cacheDir, "updates")
        if (!updateDir.exists()) updateDir.mkdirs()
        val apkFile = File(updateDir, "update.apk")
        if (apkFile.exists()) apkFile.delete()

        var currentUrl = downloadUrl
        var redirects = 0
        val maxRedirects = 6
        var connection: HttpURLConnection? = null

        try {
            while (redirects < maxRedirects) {
                val url = URL(currentUrl)
                connection = (url.openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    setRequestProperty("User-Agent", "UltimatePdf-Android")
                    connectTimeout = 15_000
                    readTimeout = 30_000
                }

                val status = connection.responseCode
                if (status == HttpURLConnection.HTTP_MOVED_PERM ||
                    status == HttpURLConnection.HTTP_MOVED_TEMP ||
                    status == HttpURLConnection.HTTP_SEE_OTHER ||
                    status == 307 || status == 308
                ) {
                    val location = connection.getHeaderField("Location")
                        ?: return@withContext Result.failure(Exception("Redirect without Location header"))
                    currentUrl = location
                    connection.disconnect()
                    redirects++
                } else if (status == HttpURLConnection.HTTP_OK) {
                    break
                } else {
                    return@withContext Result.failure(Exception("HTTP error $status: ${connection.responseMessage}"))
                }
            }

            val finalConn = connection ?: return@withContext Result.failure(Exception("Connection failed"))
            val totalBytes = finalConn.contentLengthLong.let { if (it > 0) it else -1L }

            finalConn.inputStream.use { input ->
                FileOutputStream(apkFile).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesDownloaded = 0L
                    var lastReported = 0L
                    var read: Int

                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        bytesDownloaded += read
                        val now = System.currentTimeMillis()
                        if (now - lastReported > 80 || bytesDownloaded == totalBytes) {
                            lastReported = now
                            onProgress(bytesDownloaded, totalBytes)
                        }
                    }
                    output.flush()
                }
            }
            Result.success(apkFile)
        } catch (e: Exception) {
            if (apkFile.exists()) apkFile.delete()
            Result.failure(e)
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Checks whether this app has permission to install unknown apps (Android 8.0+).
     */
    fun canInstallPackages(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    /**
     * Opens system settings so the user can grant permission to install unknown apps.
     */
    fun openInstallPermissionSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            context.startActivity(intent)
        }
    }

    /**
     * Launches the system PackageInstaller to install or update from [apkFile].
     */
    fun installApk(context: Context, apkFile: File): Result<Unit> {
        return try {
            val apkUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Cleans up cached update files.
     */
    fun cleanupUpdateFiles(context: Context) {
        runCatching {
            val updateDir = context.getExternalFilesDir("updates") ?: File(context.cacheDir, "updates")
            updateDir.deleteRecursively()
        }
    }
}
