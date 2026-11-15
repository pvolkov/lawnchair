package app.lawnchair.ui.preferences.about

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import app.lawnchair.util.getApkVersionComparison
import com.android.launcher3.BuildConfig
import com.android.launcher3.Utilities
import java.io.File
import java.io.IOException
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.outputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class NightlyBuildsRepository(
    val applicationContext: Context,
    val api: GitHubService,
) {
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val updateState: StateFlow<UpdateState>
        field = MutableStateFlow<UpdateState>(UpdateState.UpToDate)

    private var currentBuildNumber: Int = 0
    private var latestBuildNumber: Int = 0

    fun checkForUpdate() {
        coroutineScope.launch(Dispatchers.Default) {
            updateState.update { UpdateState.Checking }
            try {
                if (isLocalOrForkBuild()) {
                    checkForUpstreamCommits()
                } else {
                    checkForOfficialNightly()
                }
            } catch (e: Exception) {
                when (e) {
                    is IOException -> {
                        Log.e(TAG, "Network error during update check", e)
                    }

                    else -> {
                        Log.e(TAG, "Failed to check for update", e)
                    }
                }
                updateState.update { UpdateState.Failed }
            }
        }
    }

    /**
     * Local / fork APKs use a commit hash in the version name (no CI `#run`).
     * They must not download the official nightly (it would wipe local PRs).
     */
    private fun isLocalOrForkBuild(): Boolean {
        return !BuildConfig.VERSION_DISPLAY_NAME.contains("#")
    }

    private suspend fun checkForUpstreamCommits() {
        val majorVersion = applicationContext.getApkVersionComparison().first[0]
        val branch = "$majorVersion-dev"
        val baseHash = BuildConfig.UPSTREAM_BASE_HASH
        if (baseHash.isBlank()) {
            Log.w(TAG, "UPSTREAM_BASE_HASH is empty; treating as up-to-date")
            updateState.update { UpdateState.UpToDate }
            return
        }

        val compare = api.compareCommits(
            owner = REPO_OWNER,
            repo = REPO_NAME,
            base = baseHash,
            head = branch,
        )
        if (compare.aheadBy <= 0 || compare.commits.isEmpty()) {
            updateState.update { UpdateState.UpToDate }
            return
        }

        // GitHub compare returns oldest-first; UI expects newest-first.
        val commits = compare.commits.asReversed()
        val latestSha = commits.first().sha
        updateState.update {
            UpdateState.Available(
                changelogState = ChangelogState(
                    commits = commits,
                    currentLabel = baseHash.take(7),
                    latestLabel = latestSha.take(7),
                ),
                downloadEnabled = false,
            )
        }
    }

    private suspend fun checkForOfficialNightly() {
        val releases = api.getReleases()
        val nightly = releases.firstOrNull { it.tagName == "nightly" }
        val asset = nightly?.assets?.firstOrNull()

        val majorVersion = applicationContext.getApkVersionComparison().first[0]
        val expectedBranch = "$majorVersion-dev"

        if (nightly != null && nightly.targetCommitish != expectedBranch) {
            Log.d(TAG, "Skipping update from branch ${nightly.targetCommitish}, expected $expectedBranch")
            updateState.update { UpdateState.Disabled(UpdateDisabledReason.MAJOR_IS_NEWER) }
            return
        }

        // As of now the version string looks like this (CI builds only):
        // <major>.<branch>.(#<CI build number>)
        // This is done inside build.gradle in the source root. Reflect
        // changes from there if needed.
        currentBuildNumber = BuildConfig.VERSION_DISPLAY_NAME
            .substringAfterLast("#")
            .removeSuffix(")")
            .toIntOrNull() ?: 0
        latestBuildNumber =
            asset?.name?.substringAfter("_")?.substringBefore("-")?.toIntOrNull() ?: 0

        if (asset != null && latestBuildNumber > currentBuildNumber) {
            val commitList = getCommitsSinceCurrentVersion()

            updateState.update {
                UpdateState.Available(
                    name = asset.name,
                    url = asset.browserDownloadUrl,
                    changelogState = if (commitList != null) {
                        ChangelogState(
                            commits = commitList,
                            currentBuildNumber = currentBuildNumber,
                            latestBuildNumber = latestBuildNumber,
                        )
                    } else {
                        null
                    },
                    expectedSha256 = asset.sha256Hash,
                    downloadEnabled = true,
                )
            }
        } else {
            updateState.update { UpdateState.UpToDate }
        }
    }

    fun downloadUpdate() {
        val currentState = updateState.value
        if (currentState !is UpdateState.Available || !currentState.downloadEnabled) return

        coroutineScope.launch(Dispatchers.IO) {
            updateState.update { UpdateState.Downloading(0f) }
            try {
                val file = downloadApk(currentState.url, currentState.expectedSha256) { progress ->
                    updateState.update { UpdateState.Downloading(progress) }
                }
                if (file != null) {
                    updateState.update { UpdateState.Downloaded(file) }
                } else {
                    Log.e(TAG, "Downloaded file is null")
                    updateState.update { UpdateState.Failed }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Download failed", e)
                updateState.update { UpdateState.Failed }
            }
        }
    }

    fun installUpdate(file: File, forceInstall: Boolean = false) {
        if (!forceInstall && applicationContext.isApkMajorVersionNewer(file)) {
            updateState.update { UpdateState.MajorUpdate(file) }
            return
        }
        if (!applicationContext.hasInstallPermission()) {
            // todo expose proper permission UI instead of requesting immediately on click
            applicationContext.requestInstallPermission()
            return
        }
        val uri = FileProvider.getUriForFile(
            applicationContext,
            "${BuildConfig.APPLICATION_ID}.fileprovider",
            file,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        applicationContext.startActivity(intent)
    }

    fun resetToDownloaded(file: File) {
        updateState.update { UpdateState.Downloaded(file) }
    }

    private suspend fun getCommitsSinceCurrentVersion(): List<GitHubCommit>? {
        return try {
            val majorVersion = applicationContext.getApkVersionComparison().first[0]
            val branch = "$majorVersion-dev"
            val currentCommitHash = BuildConfig.COMMIT_HASH

            // Get the latest commits (last 100)
            val commits = api.getRepositoryCommits(REPO_OWNER, REPO_NAME, branch)

            // Find the index of current commit
            val currentIndex = commits.indexOfFirst { it.sha.startsWith(currentCommitHash) }

            if (currentIndex > 0) {
                // Return all commits newer than current version
                commits.take(currentIndex)
            } else if (currentIndex == 0) {
                emptyList()
            } else {
                // If current commit not found, show last N commits
                commits.take(MAX_FALLBACK_COMMITS)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get commits", e)
            null
        }
    }

    private suspend fun downloadApk(url: String, expectedSha256: String?, onProgress: (Float) -> Unit): File? {
        return try {
            val cacheDir = applicationContext.cacheDir
            val apkDirPath = cacheDir.toPath().resolve("updates").createDirectories()
            val apkFilePath = apkDirPath.resolve("Lawnchair-update.apk").apply { deleteIfExists() }

            val responseBody = api.downloadFile(url)
            val totalBytes = responseBody.contentLength().toFloat()
            if (totalBytes <= 0) {
                Log.w(TAG, "Content length is invalid: $totalBytes")
                return null
            }

            val messageDigest = java.security.MessageDigest.getInstance("SHA-256")

            responseBody.byteStream().use { input ->
                apkFilePath.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var bytesDownloaded = 0L
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        messageDigest.update(buffer, 0, bytesRead)
                        bytesDownloaded += bytesRead
                        onProgress(bytesDownloaded / totalBytes)
                    }
                }
            }
            if (expectedSha256 != null) {
                val computedHash = messageDigest.digest().joinToString("") { "%02x".format(it) }
                if (!computedHash.equals(expectedSha256, ignoreCase = true)) {
                    Log.e(TAG, "SHA256 verification failed. Expected: $expectedSha256, Got: $computedHash")
                    apkFilePath.deleteIfExists()
                    return null
                }
                Log.d(TAG, "SHA256 verification passed: $computedHash")
            }

            apkFilePath.toFile()
        } catch (e: Exception) {
            Log.e(TAG, "APK download failed", e)
            null
        }
    }

    companion object {
        private const val TAG = "NightlyBuildsRepository"
        private const val REPO_OWNER = "LawnchairLauncher"
        private const val REPO_NAME = "lawnchair"
    }
}

private fun Context.hasInstallPermission(): Boolean {
    return if (Utilities.ATLEAST_O) {
        packageManager.canRequestPackageInstalls()
    } else {
        true
    }
}

private fun Context.requestInstallPermission() {
    if (Utilities.ATLEAST_O) {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            "package:$packageName".toUri(),
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }
}

/**
 * Checks if the downloaded APK file has a higher Major (AA) version than the currently
 * installed build.
 */
private fun Context.isApkMajorVersionNewer(apkFile: File): Boolean {
    val (currentParsed, apkParsed) = getApkVersionComparison(apkFile) ?: return false

    val apkMajor = apkParsed[0]
    val currentMajor = currentParsed[0]

    Log.d("UpdateCheck", "Current Major: $currentMajor, APK Major: $apkMajor")

    return apkMajor > currentMajor
}

private const val MAX_FALLBACK_COMMITS = 30
