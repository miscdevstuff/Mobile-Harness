package com.jarves.mh.runtime.installer

import android.content.Context
import android.system.Os
import com.jarves.mh.BuildConfig
import com.jarves.mh.R
import com.jarves.mh.network.sharedHttpClient
import com.jarves.mh.runtime.RuntimeInstallEvent
import com.jarves.mh.runtime.RuntimeInstallProgress
import com.jarves.mh.ui.AppStrings
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream

internal data class RuntimeBundle(
    val label: String,
    val fileName: String,
    val sha256: String,
    val compressedBytes: Long,
)

internal class RuntimeArchiveExtractor(
    private val context: Context,
    val downloads: File = File(context.cacheDir, "runtime-downloads"),
) {
    private fun str(@androidx.annotation.StringRes id: Int, vararg args: Any): String =
        AppStrings.get(context, id, *args)

    suspend fun installRuntimeOverlay(
        bundle: RuntimeBundle,
        destinationRootfs: File,
        message: String,
        from: Float,
        to: Float,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
        forceEmbedded: Boolean = false,
    ) {
        val archive = obtainRuntimeBundle(
            bundle = bundle,
            preferEmbedded = forceEmbedded || BuildConfig.OFFLINE_RUNTIME_BUNDLES,
            from = from,
            to = to * 0.8f + from * 0.2f,
            onProgress = onProgress,
        )
        onProgress(RuntimeInstallProgress(message, to * 0.8f + from * 0.2f, indeterminate = true))
        extractZstdTar(archive, destinationRootfs)
        stripMacosMetadataArtifacts(destinationRootfs)
        if (archive.parentFile == downloads) archive.delete()
        onProgress(RuntimeInstallProgress(str(R.string.rt_install_bundle_tools_installed, bundle.label), to))
    }

    suspend fun obtainRuntimeBundle(
        bundle: RuntimeBundle,
        preferEmbedded: Boolean,
        from: Float,
        to: Float,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ): File {
        downloads.mkdirs()
        val destination = File(downloads, bundle.fileName)
        val useEmbedded = preferEmbedded || BuildConfig.OFFLINE_RUNTIME_BUNDLES
        if (useEmbedded) {
            val loadingLabel = str(R.string.rt_install_loading_bundle, bundle.label)
            onProgress(RuntimeInstallProgress(loadingLabel, from, 0, bundle.compressedBytes))
            val temporary = File(downloads, "${bundle.fileName}.part")
            context.assets.open("runtime/${bundle.fileName}").use { input ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(256 * 1024)
                    var copied = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        copied += count
                        val ratio = (copied.toFloat() / bundle.compressedBytes).coerceIn(0f, 1f)
                        onProgress(
                            RuntimeInstallProgress(
                                loadingLabel,
                                from + ratio * (to - from),
                                copied,
                                bundle.compressedBytes,
                            ),
                        )
                    }
                }
            }
            require(digest(temporary, "SHA-256").equals(bundle.sha256, ignoreCase = true)) {
                "${bundle.label} bundle checksum mismatch"
            }
            if (destination.exists()) destination.delete()
            check(temporary.renameTo(destination)) { "Could not stage the ${bundle.label} bundle" }
            return destination
        }

        val url = "${BuildConfig.RUNTIME_RELEASE_BASE_URL}/${bundle.fileName}"
        val downloadingLabel = str(R.string.rt_install_downloading_bundle, bundle.label)
        downloadVerified(url, destination, bundle.sha256) { downloaded, total ->
            val ratio = if (total > 0) downloaded.toFloat() / total else 0f
            onProgress(
                RuntimeInstallProgress(
                    downloadingLabel,
                    from + ratio * (to - from),
                    downloaded,
                    total.takeIf { it > 0 },
                    event = RuntimeInstallEvent.DOWNLOAD,
                ),
            )
        }
        return destination
    }

    fun extractZstdTar(
        archive: File,
        destination: File,
        stripLeadingDirectory: Boolean = false,
        convertAbsoluteSymlinks: Boolean = true,
    ) {
        TarArchiveInputStream(
            ZstdCompressorInputStream(BufferedInputStream(archive.inputStream())),
        ).use { tar ->
            extractTarStream(tar, destination, stripLeadingDirectory, convertAbsoluteSymlinks)
        }
    }

    fun extractGzipTar(
        archive: File,
        destination: File,
        stripLeadingDirectory: Boolean = false,
        convertAbsoluteSymlinks: Boolean = true,
    ) {
        TarArchiveInputStream(
            GzipCompressorInputStream(BufferedInputStream(archive.inputStream())),
        ).use { tar ->
            extractTarStream(tar, destination, stripLeadingDirectory, convertAbsoluteSymlinks)
        }
    }

    fun extractTarStream(
        tar: TarArchiveInputStream,
        destination: File,
        stripLeadingDirectory: Boolean = false,
        convertAbsoluteSymlinks: Boolean = true,
    ) {
        destination.mkdirs()
        val deferredLinks = mutableListOf<Pair<File, File>>()
        var entry: TarArchiveEntry? = tar.nextEntry
        while (entry != null) {
            val rawName = entry.name.removePrefix("./")
            val cleanName = if (stripLeadingDirectory) rawName.substringAfter('/', "") else rawName
            if (cleanName.isNotBlank() && cleanName != "." && !cleanName.startsWith("dev/")) {
                val target = safeChild(destination, cleanName)
                when {
                    entry.isDirectory -> target.mkdirs()
                    entry.isSymbolicLink -> {
                        target.parentFile?.mkdirs()
                        if (target.exists() || Files.isSymbolicLink(target.toPath())) target.delete()
                        val rawLinkTarget = entry.linkName
                        val effectiveLinkTarget = if (convertAbsoluteSymlinks && rawLinkTarget.startsWith("/")) {
                            val trimmed = cleanName.trimEnd('/')
                            val depth = trimmed.count { it == '/' }
                            val prefix = "../".repeat(depth)
                            (prefix + rawLinkTarget.trimStart('/')).ifEmpty { "." }
                        } else {
                            rawLinkTarget
                        }
                        Os.symlink(effectiveLinkTarget, target.absolutePath)
                    }
                    entry.isLink -> {
                        val rawLink = entry.linkName.removePrefix("./")
                        val cleanLink = if (stripLeadingDirectory) rawLink.substringAfter('/', "") else rawLink
                        val linkTarget = safeChild(destination, cleanLink)
                        target.parentFile?.mkdirs()
                        if (linkTarget.exists()) {
                            materializeHardLink(target, linkTarget)
                        } else {
                            deferredLinks += target to linkTarget
                        }
                    }
                    entry.isFile -> {
                        target.parentFile?.mkdirs()
                        if (Files.isSymbolicLink(target.toPath())) target.delete()
                        FileOutputStream(target).use { output -> tar.copyTo(output) }
                        val mode = (entry.mode and 0b111111111).let { m -> if (m == 0) 0b110100100 else m }
                        runCatching { Os.chmod(target.absolutePath, mode) }
                    }
                }
            }
            entry = tar.nextEntry
        }
        deferredLinks.forEach { (target, linkTarget) ->
            require(linkTarget.isFile) { "Archive hard-link target is missing: ${linkTarget.path}" }
            materializeHardLink(target, linkTarget)
        }
    }

    private fun materializeHardLink(target: File, linkTarget: File) {
        target.parentFile?.mkdirs()
        if (target.exists() || Files.isSymbolicLink(target.toPath())) {
            target.delete()
        }
        if (linkTarget.length() > 65_536L) {
            val relPath = target.parentFile.canonicalFile.toPath()
                .relativize(linkTarget.canonicalFile.toPath())
                .toString()
            Os.symlink(relPath, target.absolutePath)
            return
        }
        linkTarget.inputStream().use { input -> FileOutputStream(target).use { input.copyTo(it) } }
        runCatching {
            val mode = Os.stat(linkTarget.absolutePath).st_mode and 0b111111111
            Os.chmod(target.absolutePath, mode)
        }
    }

    private fun safeChild(root: File, relative: String): File {
        require(relative.isNotBlank() && !relative.startsWith('/')) { "Unsafe archive path" }
        val file = File(root, relative)
        val rootPath = root.canonicalFile.toPath()
        val parentPath = (file.parentFile ?: root).canonicalFile.toPath()
        require(parentPath.startsWith(rootPath)) { "Archive path escapes runtime" }
        return file
    }

    fun stripMacosMetadataArtifacts(root: File) {
        if (!root.isDirectory) return
        val queue = ArrayDeque<File>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            val children = current.listFiles() ?: continue
            for (child in children) {
                if (child.name.startsWith("._")) {
                    if (child.isDirectory) child.deleteRecursively() else child.delete()
                } else if (child.isDirectory && !Files.isSymbolicLink(child.toPath())) {
                    queue.add(child)
                }
            }
        }
    }

    suspend fun downloadVerified(
        url: String,
        destination: File,
        expectedChecksum: String,
        algorithm: String = "SHA-256",
        onBytes: suspend (downloaded: Long, total: Long) -> Unit,
    ) {
        destination.parentFile?.mkdirs()
        if (destination.isFile && digest(destination, algorithm).equals(expectedChecksum, ignoreCase = true)) {
            onBytes(destination.length(), destination.length())
            return
        }
        val temporary = File(destination.parentFile, "${destination.name}.part")
        var existing = temporary.takeIf(File::isFile)?.length() ?: 0L
        val requestBuilder = Request.Builder().url(url).get()
        if (existing > 0L) requestBuilder.header("Range", "bytes=$existing-")
        downloadHttpClient.newCall(requestBuilder.build()).execute().use { response ->
            check(response.isSuccessful) { str(R.string.rt_install_download_failed_http, response.code) }
            val resumed = response.code == 206 && existing > 0L
            if (!resumed) {
                temporary.delete()
                existing = 0L
            }
            val body = checkNotNull(response.body) { str(R.string.rt_install_download_failed_http, response.code) }
            val total = body.contentLength().takeIf { it >= 0L }?.plus(existing) ?: -1L
            body.byteStream().use { input ->
                FileOutputStream(temporary, resumed).use { output ->
                    val buffer = ByteArray(128 * 1024)
                    var downloaded = existing
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        downloaded += count
                        onBytes(downloaded, total)
                    }
                }
            }
        }
        val actual = digest(temporary, algorithm)
        if (!actual.equals(expectedChecksum, ignoreCase = true)) {
            temporary.delete()
            error("Downloaded file checksum did not match")
        }
        destination.delete()
        check(temporary.renameTo(destination)) { "Could not finish download" }
    }

    fun fetchText(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .get()
            .build()
        metadataHttpClient.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Request failed with HTTP ${response.code}" }
            return response.body?.string().orEmpty()
        }
    }

    fun digest(file: File, algorithm: String): String {
        val digest = MessageDigest.getInstance(algorithm)
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private val downloadHttpClient = sharedHttpClient.newBuilder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
        private val metadataHttpClient = sharedHttpClient.newBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
