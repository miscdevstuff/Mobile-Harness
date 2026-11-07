package com.jarves.mh.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.jarves.mh.BuildConfig
import com.jarves.mh.R
import com.jarves.mh.network.sharedHttpClient
import com.jarves.mh.ui.AppStrings
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import okhttp3.Request
import org.json.JSONObject

data class AppUpdateInfo(
    val versionCode: Long,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val sizeBytes: Long,
    val notes: String,
)

class AppUpdater(
    private val context: Context,
    /**
     * Optional manifest URL override. When non-empty, used in place of
     * [BuildConfig.APP_UPDATE_MANIFEST_URL]. Debug builds populate this
     * from Settings → Update channel so the update flow can be exercised
     * end-to-end against a Cloudflare Tunnel or ngrok HTTPS URL without
     * publishing a release to GitHub.
     */
    private val manifestUrlOverride: String = "",
) {
    private fun str(@androidx.annotation.StringRes id: Int, vararg args: Any): String =
        AppStrings.get(context, id, *args)

    fun check(): AppUpdateInfo? {
        val manifestUrl = manifestUrlOverride.ifBlank { BuildConfig.APP_UPDATE_MANIFEST_URL }
        if (!manifestUrl.startsWith("https://")) return null
        val request = Request.Builder()
            .url(manifestUrl)
            .header("Accept", "application/json")
            .get()
            .build()
        checkHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string().orEmpty()
            val root = JSONObject(body)
            val versionCode = root.optLong("versionCode")
            if (versionCode <= BuildConfig.VERSION_CODE) return null
            val artifact = root.optJSONObject("artifacts")?.optJSONObject(BuildConfig.APP_VARIANT)
                ?: root.optJSONObject(BuildConfig.APP_VARIANT)
                ?: root
            val url = artifact.optString("url").ifBlank { artifact.optString("apkUrl") }
            if (!url.startsWith("https://")) return null
            return AppUpdateInfo(
                versionCode = versionCode,
                versionName = root.optString("versionName", versionCode.toString()),
                apkUrl = url,
                sha256 = artifact.optString("sha256").lowercase(),
                sizeBytes = artifact.optLong("sizeBytes", -1L),
                notes = root.optString("notes"),
            )
        }
    }

    fun download(info: AppUpdateInfo, progress: (Long, Long) -> Unit): File {
        val directory = File(context.filesDir, "updates").also { it.mkdirs() }
        val partial = File(directory, "mobile-harness-${BuildConfig.APP_VARIANT}.apk.part")
        val target = File(directory, "mobile-harness-${BuildConfig.APP_VARIANT}.apk")
        val request = Request.Builder().url(info.apkUrl).get().build()
        downloadHttpClient.newCall(request).execute().use { response ->
            check(response.isSuccessful) { str(R.string.rt_update_download_failed, response.code) }
            val responseBody = checkNotNull(response.body) { str(R.string.rt_update_download_failed, response.code) }
            val total = responseBody.contentLength().takeIf { it > 0 } ?: info.sizeBytes
            responseBody.byteStream().use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(128 * 1024)
                    var downloaded = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        downloaded += count
                        progress(downloaded, total)
                    }
                }
            }
        }
        if (info.sha256.isNotBlank()) {
            val actual = sha256(partial)
            check(actual.equals(info.sha256, ignoreCase = true)) { str(R.string.rt_update_checksum_failed) }
        }
        verifyApk(partial, info.versionCode)
        if (target.exists()) target.delete()
        check(partial.renameTo(target)) { str(R.string.rt_update_prepare_failed) }
        return target
    }

    @Suppress("DEPRECATION")
    private fun verifyApk(apk: File, expectedVersionCode: Long) {
        // Android 9/10 archive parsing can leave signingInfo null, so also request the legacy
        // signatures and fall back to them rather than reporting a false key mismatch.
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES
        } else {
            PackageManager.GET_SIGNATURES
        }
        val archive = context.packageManager.getPackageArchiveInfo(apk.absolutePath, flags)
            ?: error(str(R.string.rt_update_invalid_apk))
        check(archive.packageName == context.packageName) { str(R.string.rt_update_package_mismatch) }
        val archiveVersion = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) archive.longVersionCode else archive.versionCode.toLong()
        check(archiveVersion == expectedVersionCode && archiveVersion > BuildConfig.VERSION_CODE) { str(R.string.rt_update_version_mismatch) }
        val installed = context.packageManager.getPackageInfo(context.packageName, flags)
        val archiveSigners = signerDigests(archive)
        val installedSigners = signerDigests(installed)
        check(archiveSigners.isNotEmpty() && archiveSigners == installedSigners) { str(R.string.rt_update_signature_mismatch) }
    }

    @Suppress("DEPRECATION")
    private fun signerDigests(info: PackageInfo): Set<String> {
        val signers = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners?.takeIf { it.isNotEmpty() } ?: info.signatures
        } else {
            info.signatures
        }
        return signers.orEmpty().map { sha256(it.toByteArray()) }.toSet()
    }

    private fun sha256(file: File): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(128 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        val checkHttpClient = sharedHttpClient.newBuilder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
        val downloadHttpClient = sharedHttpClient.newBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
