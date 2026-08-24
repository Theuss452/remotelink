package app.remotelink.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import app.remotelink.BuildConfig
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors

class UpdateManager(private val context: Context) {
    sealed class CheckResult {
        data object Disabled : CheckResult()
        data object UpToDate : CheckResult()
        data class Available(val info: UpdateInfo) : CheckResult()
        data class Error(val message: String) : CheckResult()
    }

    data class UpdateInfo(
        val versionCode: Long,
        val versionName: String,
        val apkUrl: String,
        val sha256: String,
        val notes: String
    )

    sealed class DownloadResult {
        data class Ready(val file: File, val info: UpdateInfo) : DownloadResult()
        data class Error(val message: String) : DownloadResult()
    }

    private val executor = Executors.newSingleThreadExecutor()

    fun check(callback: (CheckResult) -> Unit) {
        if (!BuildConfig.DIRECT_UPDATES || BuildConfig.UPDATE_MANIFEST_URL.isBlank()) {
            callback(CheckResult.Disabled)
            return
        }
        executor.execute {
            val result = try {
                val manifestUrl = requireHttps(BuildConfig.UPDATE_MANIFEST_URL, "manifest_url")
                val text = readText(manifestUrl, MAX_MANIFEST_BYTES)
                val obj = JSONObject(text)
                val packageName = obj.optString("packageName")
                if (packageName != context.packageName) throw IllegalStateException("package_mismatch")
                val code = obj.optLong("versionCode", -1L)
                val name = obj.optString("versionName").take(80)
                val apkUrl = requireHttps(obj.optString("apkUrl"), "apk_url").toString()
                val sha = obj.optString("sha256").trim().lowercase()
                val notes = obj.optString("notes").take(2000)
                if (code <= 0L || name.isBlank() || !SHA256_REGEX.matches(sha)) {
                    throw IllegalStateException("invalid_manifest")
                }
                if (code <= currentVersionCode()) CheckResult.UpToDate
                else CheckResult.Available(UpdateInfo(code, name, apkUrl, sha, notes))
            } catch (e: Exception) {
                CheckResult.Error(e.message ?: e.javaClass.simpleName)
            }
            post(callback, result)
        }
    }

    fun download(info: UpdateInfo, callback: (DownloadResult) -> Unit) {
        executor.execute {
            val result = try {
                val url = requireHttps(info.apkUrl, "apk_url")
                val dir = File(context.cacheDir, "updates").apply { mkdirs() }
                dir.listFiles()?.forEach { if (it.isFile) it.delete() }
                val out = File(dir, "RemoteLink-${info.versionName}.apk")
                downloadFile(url, out)
                val actualSha = sha256(out)
                if (!actualSha.equals(info.sha256, ignoreCase = true)) {
                    out.delete()
                    throw IllegalStateException("sha256_mismatch")
                }
                verifyArchive(out, info.versionCode)
                DownloadResult.Ready(out, info)
            } catch (e: Exception) {
                DownloadResult.Error(e.message ?: e.javaClass.simpleName)
            }
            post(callback, result)
        }
    }

    fun canRequestInstallPackages(): Boolean {
        if (!BuildConfig.DIRECT_UPDATES) return false
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()
    }

    fun openUnknownSourcesSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun install(file: File) {
        require(BuildConfig.DIRECT_UPDATES) { "direct_updates_disabled" }
        require(file.exists() && file.isFile) { "missing_apk" }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun close() {
        executor.shutdownNow()
    }

    private fun verifyArchive(file: File, expectedVersionCode: Long) {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        @Suppress("DEPRECATION")
        val archive = pm.getPackageArchiveInfo(file.absolutePath, flags)
            ?: throw IllegalStateException("invalid_apk")
        if (archive.packageName != context.packageName) throw IllegalStateException("package_mismatch")
        if (versionCode(archive) != expectedVersionCode || expectedVersionCode <= currentVersionCode()) {
            throw IllegalStateException("version_mismatch")
        }

        val installed = installedPackageInfo(pm, flags)
        val installedSigners = signerDigests(installed)
        val archiveSigners = signerDigests(archive)
        if (installedSigners.isEmpty() || archiveSigners.isEmpty() || installedSigners != archiveSigners) {
            throw IllegalStateException("signer_mismatch")
        }
    }

    private fun installedPackageInfo(pm: PackageManager, flags: Int): PackageInfo {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, flags)
        }
    }

    private fun signerDigests(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signing = info.signingInfo ?: return emptySet()
            if (signing.hasMultipleSigners()) signing.apkContentsSigners
            else signing.signingCertificateHistory
        } else {
            @Suppress("DEPRECATION")
            info.signatures
        }
        return signatures.orEmpty().map { signature ->
            MessageDigest.getInstance("SHA-256")
                .digest(signature.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }.toSet()
    }

    private fun currentVersionCode(): Long {
        val info = installedPackageInfo(
            context.packageManager,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else 0
        )
        return versionCode(info)
    }

    private fun versionCode(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode
        else @Suppress("DEPRECATION") info.versionCode.toLong()

    private fun readText(url: URL, maxBytes: Int): String {
        val connection = open(url)
        try {
            if (connection.responseCode !in 200..299) throw IllegalStateException("http_${connection.responseCode}")
            val length = connection.contentLengthLong
            if (length > maxBytes) throw IllegalStateException("manifest_too_large")
            return connection.inputStream.buffered().use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > maxBytes) throw IllegalStateException("manifest_too_large")
                    out.write(buffer, 0, n)
                }
                out.toString(Charsets.UTF_8.name())
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun downloadFile(url: URL, target: File) {
        val connection = open(url)
        try {
            if (connection.responseCode !in 200..299) throw IllegalStateException("http_${connection.responseCode}")
            val length = connection.contentLengthLong
            if (length > MAX_APK_BYTES) throw IllegalStateException("apk_too_large")
            connection.inputStream.buffered().use { input ->
                FileOutputStream(target).buffered().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > MAX_APK_BYTES) throw IllegalStateException("apk_too_large")
                        output.write(buffer, 0, n)
                    }
                }
            }
            if (target.length() <= 0L) throw IllegalStateException("empty_apk")
        } catch (e: Exception) {
            target.delete()
            throw e
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: URL): HttpURLConnection {
        return (url.openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = 10_000
            readTimeout = 20_000
            useCaches = false
            setRequestProperty("Accept", "application/json, application/vnd.android.package-archive;q=0.9, */*;q=0.1")
            setRequestProperty("User-Agent", "RemoteLink/${BuildConfig.VERSION_NAME}")
        }
    }

    private fun requireHttps(raw: String, field: String): URL {
        if (raw.length !in 8..2048) throw IllegalArgumentException("invalid_$field")
        val url = URL(raw)
        if (!url.protocol.equals("https", ignoreCase = true)) throw IllegalArgumentException("https_required")
        if (url.userInfo != null || url.host.isBlank()) throw IllegalArgumentException("invalid_$field")
        return url
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun <T> post(callback: (T) -> Unit, value: T) {
        android.os.Handler(android.os.Looper.getMainLooper()).post { callback(value) }
    }

    companion object {
        private const val MAX_MANIFEST_BYTES = 64 * 1024
        private const val MAX_APK_BYTES = 250L * 1024L * 1024L
        private val SHA256_REGEX = Regex("[a-f0-9]{64}")
    }
}
