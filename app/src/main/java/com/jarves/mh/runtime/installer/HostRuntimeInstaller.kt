package com.jarves.mh.runtime.installer

import android.content.Context
import android.system.Os
import com.jarves.mh.BuildConfig
import com.jarves.mh.R
import com.jarves.mh.runtime.NativeSpawnProcess
import com.jarves.mh.runtime.RuntimeInstallEvent
import com.jarves.mh.runtime.RuntimeInstallProgress
import com.jarves.mh.runtime.forEachOutputLine
import com.jarves.mh.runtime.readTailText
import com.jarves.mh.ui.AppStrings
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

data class HostRuntimeManifest(
    val version: String,
    val pythonVersion: String,
    val prootDistroVersion: String,
    val prootVersion: String,
    val fileName: String,
    val url: String,
    val sha256: String,
    val sizeBytes: Long,
)

data class HostRuntimeUpdateInfo(
    val installedVersion: String,
    val latestManifest: HostRuntimeManifest,
)

/**
 * Wipes all top-level entries inside `host-runtime/usr` except `var`, preserving
 * `var/lib/proot-distro/containers` across host runtime updates and repairs.
 */
internal fun wipeHostRuntimePreservingVar(usrDir: File) {
    if (!usrDir.isDirectory) return
    usrDir.listFiles()?.forEach { child ->
        if (child.name == "var") return@forEach
        if (child.isDirectory && !Files.isSymbolicLink(child.toPath())) {
            check(child.deleteRecursively()) { "Could not remove host runtime directory ${child.name}" }
        } else {
            check(child.delete()) { "Could not remove host runtime entry ${child.name}" }
        }
    }
}

internal class HostRuntimeInstaller(
    private val context: Context,
    private val archiveExtractor: RuntimeArchiveExtractor,
) {
    val hostRuntimeDir: File by lazy {
        val raw = File(context.filesDir, "host-runtime").apply { mkdirs() }
        runCatching { raw.canonicalFile }.getOrDefault(raw)
    }
    val hostUsrDir: File get() = File(hostRuntimeDir, "usr")
    val prootBin: File get() = File(hostUsrDir, "bin/proot")
    val prootLoaderBin: File get() = File(hostUsrDir, "libexec/proot/loader")
    val pythonBin: File get() = File(hostUsrDir, "bin/python3.14")
    val prootDistroBin: File get() = File(hostUsrDir, "bin/proot-distro")
    val hostTmpDir: File get() = File(hostUsrDir, "tmp")
    val hostVersionMarker: File get() = File(hostUsrDir, ".mhr-version")
    val hostManifestFile: File get() = File(hostUsrDir, ".mhr-manifest.json")

    val containersDir: File get() = File(hostUsrDir, "var/lib/proot-distro/containers")
    val ubuntuContainerDir: File get() = File(containersDir, UBUNTU_CONTAINER_NAME)
    val rootfs: File get() = File(ubuntuContainerDir, "rootfs")

    private val legacyRuntimeDir: File get() = File(context.filesDir, "runtime")
    private val legacyRootfsLink: File get() = File(legacyRuntimeDir, "ubuntu")

    private fun str(@androidx.annotation.StringRes id: Int, vararg args: Any): String =
        AppStrings.get(context, id, *args)

    fun isHostRuntimeReady(): Boolean =
        prootBin.canExecute() &&
            prootLoaderBin.canExecute() &&
            pythonBin.canExecute() &&
            prootDistroBin.canExecute() &&
            !installedHostRuntimeVersion().isBlank()

    fun isUbuntuContainerReady(): Boolean =
        File(ubuntuContainerDir, "manifest.json").isFile &&
            File(rootfs, "usr/bin/bash").canExecute() &&
            File(rootfs, "usr/bin/env").canExecute()

    fun installedHostRuntimeVersion(): String =
        hostVersionMarker.readTextOrNull().orEmpty()

    fun installedHostRuntimeManifest(): HostRuntimeManifest? = runCatching {
        if (!hostManifestFile.isFile) return null
        parseManifestJson(JSONObject(hostManifestFile.readText()))
    }.getOrNull()

    fun fetchLatestHostRuntimeManifest(): HostRuntimeManifest {
        val raw = archiveExtractor.fetchText(BuildConfig.HOST_RUNTIME_MANIFEST_URL)
        return parseManifestJson(JSONObject(raw))
    }

    private fun parseManifestJson(json: JSONObject): HostRuntimeManifest = HostRuntimeManifest(
        version = json.getString("version"),
        pythonVersion = json.optString("pythonVersion", ""),
        prootDistroVersion = json.optString("prootDistroVersion", ""),
        prootVersion = json.optString("prootVersion", ""),
        fileName = json.optString("fileName", "mh-host-runtime-arm64.tar.zst"),
        url = json.getString("url"),
        sha256 = json.getString("sha256"),
        sizeBytes = json.optLong("sizeBytes", -1L),
    )

    fun checkHostRuntimeUpdate(): HostRuntimeUpdateInfo {
        val latest = fetchLatestHostRuntimeManifest()
        val current = installedHostRuntimeVersion()
        return HostRuntimeUpdateInfo(installedVersion = current, latestManifest = latest)
    }

    suspend fun ensureHostRuntimeInstalled(
        force: Boolean = false,
        from: Float = 0.02f,
        to: Float = 0.30f,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ): HostRuntimeManifest {
        if (!force && isHostRuntimeReady()) {
            installedHostRuntimeManifest()?.let { return it }
        }
        onProgress(RuntimeInstallProgress("Checking host runtime release…", from, indeterminate = true))
        val manifest = fetchLatestHostRuntimeManifest()
        if (!force && isHostRuntimeReady() && installedHostRuntimeVersion() == manifest.version) {
            return manifest
        }

        val archive = File(archiveExtractor.downloads, manifest.fileName)
        val downloadLabel = "Downloading Host Runtime (${manifest.version})"
        archiveExtractor.downloadVerified(manifest.url, archive, manifest.sha256) { bytes, total ->
            val effectiveTotal = if (total > 0L) total else manifest.sizeBytes
            val ratio = if (effectiveTotal > 0L) (bytes.toFloat() / effectiveTotal).coerceIn(0f, 1f) else 0f
            onProgress(
                RuntimeInstallProgress(
                    message = downloadLabel,
                    fraction = from + ratio * (to - from) * 0.85f,
                    downloadedBytes = bytes,
                    totalBytes = effectiveTotal.takeIf { it > 0L },
                    event = RuntimeInstallEvent.DOWNLOAD,
                ),
            )
        }

        val extractFraction = from + (to - from) * 0.90f
        onProgress(RuntimeInstallProgress("Extracting isolated host runtime…", extractFraction, indeterminate = true))
        hostUsrDir.mkdirs()
        wipeHostRuntimePreservingVar(hostUsrDir)
        archiveExtractor.extractZstdTar(
            archive = archive,
            destination = hostRuntimeDir,
            stripLeadingDirectory = false,
            convertAbsoluteSymlinks = false,
        )
        archive.delete()

        ensureHostRuntimePermissionsAndDirs()
        hostVersionMarker.writeText(manifest.version + "\n")
        hostManifestFile.writeText(
            JSONObject()
                .put("version", manifest.version)
                .put("pythonVersion", manifest.pythonVersion)
                .put("prootDistroVersion", manifest.prootDistroVersion)
                .put("prootVersion", manifest.prootVersion)
                .put("fileName", manifest.fileName)
                .put("url", manifest.url)
                .put("sha256", manifest.sha256)
                .put("sizeBytes", manifest.sizeBytes)
                .toString(2),
        )
        check(isHostRuntimeReady()) { "Host runtime installation is incomplete" }
        onProgress(RuntimeInstallProgress("Host runtime ready (${manifest.version})", to))
        return manifest
    }

    private fun ensureHostRuntimePermissionsAndDirs() {
        listOf(
            hostTmpDir,
            containersDir,
            File(hostUsrDir, "var/lib/proot-distro/cache"),
        ).forEach { it.mkdirs() }
        runCatching { Os.chmod(hostTmpDir.absolutePath, 0b111111111) }

        listOf(
            prootBin,
            prootLoaderBin,
            pythonBin,
            prootDistroBin,
            File(hostUsrDir, "lib/libpython3.14.so"),
        ).filter(File::isFile).forEach { file ->
            runCatching { Os.chmod(file.absolutePath, 0b111101101) }
        }
    }

    suspend fun ensureUbuntuContainerInstalled(
        from: Float = 0.30f,
        to: Float = 0.68f,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        ensureHostRuntimePermissionsAndDirs()
        if (!isUbuntuContainerReady()) {
            if (ubuntuContainerDir.exists()) {
                runCatching {
                    runHostProotDistroCommand(
                        args = listOf("remove", UBUNTU_CONTAINER_NAME),
                        displayCommand = "proot-distro remove $UBUNTU_CONTAINER_NAME",
                        fraction = from,
                        timeoutMs = 120_000L,
                        onProgress = onProgress,
                        failureMessage = "Could not clean incomplete Ubuntu container",
                    )
                }.onFailure { ubuntuContainerDir.deleteRecursively() }
            }
            onProgress(RuntimeInstallProgress("Installing Ubuntu LTS via proot-distro…", from, indeterminate = true))
            runHostProotDistroCommand(
                args = listOf("install", UBUNTU_CONTAINER_NAME),
                displayCommand = "proot-distro install $UBUNTU_CONTAINER_NAME",
                fraction = from + (to - from) * 0.45f,
                timeoutMs = 25 * 60 * 1_000L,
                onProgress = onProgress,
                failureMessage = "Could not install Ubuntu LTS container via proot-distro",
            )
            runCatching {
                runHostProotDistroCommand(
                    args = listOf("clear-cache"),
                    displayCommand = "proot-distro clear-cache",
                    fraction = from + (to - from) * 0.55f,
                    timeoutMs = 60_000L,
                    onProgress = onProgress,
                    failureMessage = "Could not clear proot-distro image cache",
                )
            }
        }
        configureUbuntuContainerFilesystem()
        ensureLegacyRootfsSymlink()
        check(isUbuntuContainerReady()) { "Ubuntu LTS container is not ready" }

        val coreToolsMarker = File(rootfs, ".pocket-core-tools-version")
        val coreNeeded = !File(rootfs, "usr/bin/git").canExecute() ||
            !File(rootfs, "usr/bin/curl").canExecute() ||
            coreToolsMarker.readTextOrNull() != CORE_TOOLS_VERSION
        if (coreNeeded) {
            aptInstall(
                packages = listOf("ca-certificates", "curl", "git", "unzip", "whiptail"),
                message = str(R.string.rt_install_git_base_tools),
                fraction = from + (to - from) * 0.75f,
                onProgress = onProgress,
            )
            verifyGuest("git --version && curl --version", str(R.string.rt_install_verify_failed, "Git"))
            coreToolsMarker.writeText(CORE_TOOLS_VERSION)
        }
        onProgress(RuntimeInstallProgress("Ubuntu LTS container ready", to))
    }

    fun configureUbuntuContainerFilesystem() {
        if (!rootfs.isDirectory) return
        listOf(
            "root",
            "tmp",
            "workspace",
            "pocket-bridge",
            "etc/apt/apt.conf.d",
            "etc/profile.d",
            "usr/local/bin",
        ).forEach { rel ->
            File(rootfs, rel).mkdirs()
        }
        runCatching { Os.chmod(File(rootfs, "tmp").absolutePath, 0b111111111) }

        val noRecommendsConf = File(rootfs, "etc/apt/apt.conf.d/99-no-recommends-suggests")
        noRecommendsConf.parentFile?.mkdirs()
        noRecommendsConf.writeText(
            """
            APT::Install-Recommends "false";
            APT::Install-Suggests "false";
            """.trimIndent() + "\n",
        )

        val aptProotConf = File(rootfs, "etc/apt/apt.conf.d/99pocket-proot")
        aptProotConf.parentFile?.mkdirs()
        aptProotConf.writeText(
            """
            APT::Sandbox::User "root";
            Acquire::Languages "none";
            Dpkg::Use-Pty "0";
            Dpkg::Options {
               "--force-confdef";
               "--force-confold";
            };
            """.trimIndent() + "\n",
        )

        val nvmProfile = File(rootfs, "etc/profile.d/nvm.sh")
        if (!nvmProfile.isFile) {
            nvmProfile.parentFile?.mkdirs()
            nvmProfile.writeText(
                """
                export NVM_DIR="/root/.nvm"
                if [ -s "${'$'}NVM_DIR/nvm.sh" ]; then
                  \. "${'$'}NVM_DIR/nvm.sh"
                fi
                """.trimIndent() + "\n",
            )
            runCatching { Os.chmod(nvmProfile.absolutePath, 0b110100100) }
        }
    }

    fun ensureLegacyRootfsSymlink(): Boolean = runCatching {
        if (!rootfs.isDirectory) return false
        legacyRuntimeDir.mkdirs()
        val linkPath = legacyRootfsLink.toPath()
        val targetPath = rootfs.canonicalPath
        if (Files.isSymbolicLink(linkPath)) {
            val currentTarget = runCatching { legacyRootfsLink.canonicalPath }.getOrNull()
            if (currentTarget == targetPath) return true
            Files.delete(linkPath)
        } else if (legacyRootfsLink.exists()) {
            legacyRootfsLink.deleteRecursively()
        }
        Os.symlink(targetPath, legacyRootfsLink.absolutePath)
        true
    }.getOrDefault(false)

    private fun hostEnvironment(): Map<String, String> = mapOf(
        "HOME" to hostRuntimeDir.absolutePath,
        "TERMUX__PREFIX" to hostUsrDir.absolutePath,
        "TERMUX__HOME" to File(hostRuntimeDir, "home").apply { mkdirs() }.absolutePath,
        "TERMUX_APP__PACKAGE_NAME" to context.packageName,
        "TERMUX_VERSION" to "0.118.1",
        "PD_PROOT_BIN" to prootBin.absolutePath,
        "PROOT_TMP_DIR" to hostTmpDir.absolutePath,
        "PROOT_LOADER" to prootLoaderBin.absolutePath,
        "PROOT_NO_SECCOMP" to "1",
        "SSL_CERT_FILE" to File(hostUsrDir, "etc/tls/cert.pem").absolutePath,
        "PYTHONHOME" to hostUsrDir.absolutePath,
        "PYTHONUTF8" to "1",
        "LD_LIBRARY_PATH" to File(hostUsrDir, "lib").absolutePath,
        "PATH" to "${File(hostUsrDir, "bin").absolutePath}:/system/bin",
        "LANG" to "C.UTF-8",
        "TERM" to "xterm-256color",
    )

    suspend fun runHostProotDistroCommand(
        args: List<String>,
        displayCommand: String,
        fraction: Float,
        timeoutMs: Long,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
        failureMessage: String,
    ) {
        onProgress(
            RuntimeInstallProgress(
                message = displayCommand,
                fraction = fraction,
                terminalLine = "$ $displayCommand",
                indeterminate = true,
                event = RuntimeInstallEvent.COMMAND,
            ),
        )
        val outputFile = File(context.cacheDir, "pd-host-${System.nanoTime()}.log")
        val argv = listOf(pythonBin.absolutePath, "-m", "proot_distro") + args
        val running = NativeSpawnProcess.start(
            argv = argv,
            environment = hostEnvironment(),
            cwd = hostRuntimeDir.absolutePath,
            outputFile = outputFile,
        )
        val collected = StringBuilder()
        try {
            withTimeout(timeoutMs) {
                running.forEachOutputLine(pollIntervalMs = 80L) { raw ->
                    val line = sanitizeTerminalLine(raw)
                    if (line.isNotBlank()) {
                        collected.appendLine(line)
                        if (collected.length > MAX_COLLECTED_OUTPUT) {
                            collected.delete(0, collected.length - MAX_COLLECTED_OUTPUT)
                        }
                        onProgress(
                            RuntimeInstallProgress(
                                message = line,
                                fraction = fraction,
                                terminalLine = line,
                                indeterminate = true,
                                event = RuntimeInstallEvent.OUTPUT,
                            ),
                        )
                    }
                    false
                }
            }
        } finally {
            if (running.isAlive) running.destroy()
        }
        val exit = running.waitFor()
        outputFile.delete()
        onProgress(
            RuntimeInstallProgress(
                message = if (exit == 0) str(R.string.rt_install_command_completed) else str(R.string.rt_install_command_failed, exit),
                fraction = fraction,
                terminalLine = "[exit $exit] $displayCommand",
                event = RuntimeInstallEvent.COMMAND_COMPLETED,
            ),
        )
        check(exit == 0) { actionableProcessError(collected.toString(), failureMessage) }
    }

    fun process(
        workspace: File,
        environment: Map<String, String>,
        guestCommand: List<String>,
        guestWorkspacePath: String = "/workspace",
        emulateHardLinks: Boolean = true,
        outputFile: File = File(context.cacheDir, "runtime-output-${System.nanoTime()}.log"),
        pseudoTerminal: Boolean = false,
        ptyRows: Int = 40,
        ptyColumns: Int = 120,
        androidToolsReady: Boolean = false,
        onBeforeLaunch: (() -> Unit)? = null,
    ): Process {
        check(isHostRuntimeReady() && isUbuntuContainerReady()) { "Linux runtime is not installed" }
        require(
            guestWorkspacePath == "/workspace" ||
                Regex("^/workspace/[a-z0-9](?:[a-z0-9-]{0,62}[a-z0-9])?$").matches(guestWorkspacePath),
        ) { "Invalid project workspace path" }
        workspace.mkdirs()
        File(rootfs, guestWorkspacePath.removePrefix("/")).mkdirs()
        onBeforeLaunch?.invoke()
        val bridge = File(context.filesDir, "runtime-bridge").apply { mkdirs() }

        val basePath = "/root/.opencode/bin:/root/.local/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
        val guestPath = if (androidToolsReady) {
            "/opt/gradle/gradle-8.14.3/bin:/root/android-sdk/build-tools/35.0.0:/root/android-sdk/cmdline-tools/latest/bin:$basePath"
        } else {
            basePath
        }

        val sessionEnv = linkedMapOf<String, String>()
        sessionEnv["HOME"] = "/root"
        sessionEnv["LANG"] = "C.UTF-8"
        sessionEnv["TERM"] = "xterm-256color"
        if (androidToolsReady) {
            sessionEnv["ANDROID_HOME"] = "/root/android-sdk"
            sessionEnv["ANDROID_SDK_ROOT"] = "/root/android-sdk"
            sessionEnv["GRADLE_HOME"] = "/opt/gradle/gradle-8.14.3"
            sessionEnv["GRADLE_USER_HOME"] = "/root/.gradle"
            sessionEnv["ORG_GRADLE_PROJECT_android.aapt2FromMavenOverride"] = "/root/android-sdk/build-tools/35.0.0/aapt2"
        }
        for ((k, v) in environment) {
            if (k == "PATH" && v.isNotBlank()) {
                sessionEnv["PATH"] = "$v:$guestPath"
            } else {
                sessionEnv[k] = v
            }
        }

        val argv = buildList {
            add(pythonBin.absolutePath)
            add("-m")
            add("proot_distro")
            add("login")
            add(UBUNTU_CONTAINER_NAME)
            add("--isolated")
            if (!emulateHardLinks) add("--no-link2symlink")
            add("--work-dir")
            add(guestWorkspacePath)
            add("--bind")
            add("${workspace.canonicalFile.absolutePath}:$guestWorkspacePath")
            add("--bind")
            add("${bridge.canonicalFile.absolutePath}:/pocket-bridge")
            // Static container-wide settings (safe for proot-distro's profile.d snippet)
            add("-e")
            add("PATH=$guestPath")
            add("-e")
            add("BASH_ENV=/etc/profile.d/nvm.sh")
            add("-e")
            add("UV_USE_IO_URING=0")
            add("-e")
            add("PIP_BREAK_SYSTEM_PACKAGES=1")
            add("-e")
            add("GLIBC_TUNABLES=glibc.pthread.rseq=0")
            add("-e")
            add("AGY_CLI_DISABLE_AUTO_UPDATE=true")
            add("-e")
            add("PROOT_TMP_DIR=${hostTmpDir.absolutePath}")
            add("-e")
            add("PROOT_LOADER=${prootLoaderBin.absolutePath}")
            add("-e")
            add("PROOT_NO_SECCOMP=1")
            add("--")
            add("/usr/bin/env")
            sessionEnv.forEach { (k, v) -> add("$k=$v") }
            addAll(guestCommand)
        }

        return NativeSpawnProcess.start(
            argv = argv,
            environment = hostEnvironment(),
            cwd = hostRuntimeDir.absolutePath,
            outputFile = outputFile,
            pseudoTerminal = pseudoTerminal,
            ptyRows = ptyRows,
            ptyColumns = ptyColumns,
        )
    }

    suspend fun runGuestCommand(
        command: String,
        displayCommand: String,
        fraction: Float,
        timeoutMs: Long,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
        failureMessage: String,
        androidToolsReady: Boolean = false,
    ) {
        onProgress(
            RuntimeInstallProgress(
                message = displayCommand,
                fraction = fraction,
                terminalLine = "root@ubuntu:~# $displayCommand",
                indeterminate = true,
                event = RuntimeInstallEvent.COMMAND,
            ),
        )
        val running = process(
            workspace = File(rootfs, "root"),
            environment = emptyMap(),
            guestCommand = listOf("/usr/bin/env", "bash", "-lc", command),
            androidToolsReady = androidToolsReady,
        )
        val native = running as? NativeSpawnProcess
        val collected = StringBuilder()
        try {
            withTimeout(timeoutMs) {
                if (native != null) {
                    native.forEachOutputLine(pollIntervalMs = 80L) { raw ->
                        val line = sanitizeTerminalLine(raw)
                        if (line.isNotBlank() && !isDpkgDatabaseProgressNoise(line)) {
                            collected.appendLine(line)
                            if (collected.length > MAX_COLLECTED_OUTPUT) {
                                collected.delete(0, collected.length - MAX_COLLECTED_OUTPUT)
                            }
                            onProgress(
                                RuntimeInstallProgress(
                                    message = line,
                                    fraction = fraction,
                                    terminalLine = line,
                                    indeterminate = true,
                                    event = RuntimeInstallEvent.OUTPUT,
                                ),
                            )
                        }
                        false
                    }
                } else {
                    while (running.isAlive) delay(80)
                }
            }
        } finally {
            if (running.isAlive) running.destroy()
        }
        val exit = running.waitFor()
        native?.outputFile?.delete()
        onProgress(
            RuntimeInstallProgress(
                message = if (exit == 0) str(R.string.rt_install_command_completed) else str(R.string.rt_install_command_failed, exit),
                fraction = fraction,
                terminalLine = "[exit $exit] $displayCommand",
                event = RuntimeInstallEvent.COMMAND_COMPLETED,
            ),
        )
        check(exit == 0) { actionableProcessError(collected.toString(), failureMessage) }
    }

    suspend fun verifyGuest(
        command: String,
        failureMessage: String,
        androidToolsReady: Boolean = false,
    ) {
        val verify = process(
            workspace = File(rootfs, "root"),
            environment = emptyMap(),
            guestCommand = listOf("/usr/bin/env", "bash", "-lc", command),
            androidToolsReady = androidToolsReady,
        )
        withTimeout(60_000L) {
            while (verify.isAlive) delay(50)
        }
        val exit = verify.waitFor()
        val outputFile = (verify as? NativeSpawnProcess)?.outputFile
        val output = outputFile?.let(::readProcessOutputSafely).orEmpty().trim()
        outputFile?.delete()
        check(exit == 0) { actionableProcessError(output, failureMessage) }
    }

    suspend fun aptInstall(
        packages: List<String>,
        message: String,
        fraction: Float,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        require(packages.isNotEmpty()) { "No packages selected" }
        onProgress(RuntimeInstallProgress(message, fraction))
        configureUbuntuContainerFilesystem()
        val effectivePackages = (listOf("whiptail") + packages).distinct()
        val packageNames = effectivePackages.joinToString(" ")
        val command = "export DEBIAN_FRONTEND=noninteractive; " +
            "dpkg --configure -a && " +
            "apt-get -o DPkg::Lock::Timeout=120 -f install -y && " +
            "apt-get -o DPkg::Lock::Timeout=120 update && " +
            "apt-get -o DPkg::Lock::Timeout=120 install -y $packageNames && " +
            "apt-get clean && rm -rf /var/lib/apt/lists/*"
        runGuestCommand(
            command = command,
            displayCommand = "apt-get install -y $packageNames",
            fraction = fraction,
            timeoutMs = 30 * 60 * 1_000L,
            onProgress = onProgress,
            failureMessage = str(R.string.rt_install_could_not_install, packageNames),
        )
    }

    suspend fun aptRemove(
        packages: List<String>,
        fraction: Float,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        require(packages.isNotEmpty()) { "No packages selected" }
        val packageNames = packages.joinToString(" ")
        val command = "export DEBIAN_FRONTEND=noninteractive; " +
            "apt-get -o DPkg::Lock::Timeout=120 purge -y $packageNames && " +
            "apt-get clean && rm -rf /var/lib/apt/lists/*"
        runGuestCommand(
            command = command,
            displayCommand = "apt-get purge -y $packageNames",
            fraction = fraction,
            timeoutMs = 20 * 60 * 1_000L,
            onProgress = onProgress,
            failureMessage = str(R.string.rt_install_could_not_remove, packageNames),
        )
    }

    fun actionableProcessError(output: String, fallback: String): String {
        val lines = output.lineSequence().map(String::trim).filter(String::isNotBlank).toList()
        val primaryProotError = lines.firstOrNull {
            it.startsWith("proot error:") && !it.contains("can't chmod")
        } ?: lines.firstOrNull { it.startsWith("proot error:") || it.startsWith("proot-distro:") }
        return primaryProotError ?: output.trim().takeLast(1_000).ifBlank { fallback }
    }

    fun readProcessOutputSafely(file: File): String = runCatching {
        file.readTailText(MAX_PROCESS_OUTPUT_BYTES)
    }.getOrDefault("")

    private fun sanitizeTerminalLine(raw: String): String = raw
        .replace(ANSI_ESCAPE, "")
        .filter { it == '\t' || it.code >= 32 }
        .take(MAX_TERMINAL_LINE)

    private fun isDpkgDatabaseProgressNoise(line: String): Boolean {
        val trimmed = line.trim()
        return trimmed == "(Reading database ..." ||
            (trimmed.startsWith("(Reading database ... ") && trimmed.endsWith("%"))
    }

    private fun File.readTextOrNull(): String? = runCatching { readText().trim() }.getOrNull()

    companion object {
        const val UBUNTU_CONTAINER_NAME = "ubuntu"
        const val CORE_TOOLS_VERSION = "pd-ubuntu-core-tools-v1"
        private const val MAX_TERMINAL_LINE = 500
        private const val MAX_COLLECTED_OUTPUT = 24_000
        private const val MAX_PROCESS_OUTPUT_BYTES = 256 * 1024
        private val ANSI_ESCAPE = Regex("\\u001B(?:\\[[0-?]*[ -/]*[@-~]|\\][^\\u0007]*(?:\\u0007|\\u001B\\\\))")
    }
}
