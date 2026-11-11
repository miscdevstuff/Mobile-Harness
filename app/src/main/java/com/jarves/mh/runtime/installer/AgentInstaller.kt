package com.jarves.mh.runtime.installer

import android.content.Context
import android.system.Os
import com.jarves.mh.R
import com.jarves.mh.model.AgentKind
import com.jarves.mh.runtime.AgentUpdateInfo
import com.jarves.mh.runtime.NativeSpawnProcess
import com.jarves.mh.runtime.RuntimeInstallEvent
import com.jarves.mh.runtime.RuntimeInstallProgress
import com.jarves.mh.runtime.RuntimeInstaller
import com.jarves.mh.runtime.isSupportedDshVersion
import com.jarves.mh.ui.AppStrings
import com.jarves.mh.ui.localizedTitle
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.json.JSONArray
import org.json.JSONObject

internal class AgentInstaller(
    private val context: Context,
    private val hostInstaller: HostRuntimeInstaller,
    private val archiveExtractor: RuntimeArchiveExtractor,
    private val stackInstaller: StackInstaller,
) {
    private val rootfs: File get() = hostInstaller.rootfs
    private val coreReadyMarker: File get() = File(rootfs, ".pocket-runtime-ready")
    private val claudeMarker: File get() = File(rootfs, ".pocket-claude-version")
    private val bundledClaudeMarker: File get() = File(rootfs, ".pocket-bundled-claude-version")
    private val dshMarker: File get() = File(rootfs, ".pocket-dsh-version")
    private val agyMarker: File get() = File(rootfs, ".pocket-agy-version")
    private val opencodeMarker: File get() = File(rootfs, ".pocket-opencode-version")
    private val githubCliMarker: File get() = File(rootfs, ".pocket-github-cli-version")
    private val dshAndroidCompatibilityMarker: File get() = File(rootfs, ".pocket-dsh-android-compat-version")

    private fun str(@androidx.annotation.StringRes id: Int, vararg args: Any): String =
        AppStrings.get(context, id, *args)

    val dshVersion: String get() = dshMarker.readTextOrNull().orEmpty()
    val claudeVersion: String get() {
        migrateLegacyClaudeMarker()
        return claudeMarker.readTextOrNull().orEmpty()
    }
    val agyVersion: String get() = agyMarker.readTextOrNull().orEmpty()
    val opencodeVersion: String get() = opencodeMarker.readTextOrNull().orEmpty()
    val githubCliVersion: String get() = githubCliMarker.readTextOrNull().orEmpty()

    fun isAgentInstalled(agent: AgentKind, runtimeInstalled: Boolean): Boolean {
        if (!runtimeInstalled) return false
        return when (agent) {
            AgentKind.CLAUDE_CODE -> {
                migrateLegacyClaudeMarker()
                File(rootfs, CLAUDE_GUEST_PATH.removePrefix("/")).canExecute() &&
                    !claudeMarker.readTextOrNull().isNullOrBlank()
            }
            AgentKind.DEEPSEEK_HARNESS ->
                File(rootfs, "usr/local/lib/dsh/node_modules/.bin/dsh").isFile &&
                    isSupportedDshVersion(dshMarker.readTextOrNull().orEmpty())
            AgentKind.ANTIGRAVITY ->
                File(rootfs, RuntimeInstaller.AGY_GUEST_PATH.removePrefix("/")).canExecute() &&
                    !agyMarker.readTextOrNull().isNullOrBlank()
            AgentKind.OPENCODE ->
                File(rootfs, OPENCODE_HOME_BIN_REL_PATH).canExecute() &&
                    File(rootfs, RuntimeInstaller.OPENCODE_GUEST_PATH.removePrefix("/")).canExecute() &&
                    !opencodeMarker.readTextOrNull().isNullOrBlank()
        }
    }

    fun installedAgentVersions(runtimeInstalled: Boolean): Map<AgentKind, String> = buildMap {
        migrateLegacyClaudeMarker()
        claudeMarker.readTextOrNull()
            ?.trim()
            ?.takeIf { isAgentInstalled(AgentKind.CLAUDE_CODE, runtimeInstalled) && it.matches(CLAUDE_VERSION_PATTERN) }
            ?.let { put(AgentKind.CLAUDE_CODE, it) }

        dshMarker.readTextOrNull()
            ?.trim()
            ?.takeIf { isAgentInstalled(AgentKind.DEEPSEEK_HARNESS, runtimeInstalled) }
            ?.let { put(AgentKind.DEEPSEEK_HARNESS, it) }

        agyMarker.readTextOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() && File(rootfs, RuntimeInstaller.AGY_GUEST_PATH.removePrefix("/")).canExecute() }
            ?.let { put(AgentKind.ANTIGRAVITY, it) }

        opencodeMarker.readTextOrNull()
            ?.trim()
            ?.takeIf { isAgentInstalled(AgentKind.OPENCODE, runtimeInstalled) }
            ?.let { put(AgentKind.OPENCODE, it) }
    }

    suspend fun ensureAgentInstalled(
        agent: AgentKind,
        fraction: Float,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        when (agent) {
            AgentKind.CLAUDE_CODE -> ensureClaudeInstalled(fraction, onProgress)
            AgentKind.DEEPSEEK_HARNESS -> ensureDshInstalled(fraction, onProgress)
            AgentKind.ANTIGRAVITY -> ensureAgyInstalled(fraction, onProgress)
            AgentKind.OPENCODE -> ensureOpencodeInstalled(fraction, onProgress)
        }
    }

    suspend fun ensureClaudeInstalled(
        fraction: Float,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        migrateLegacyClaudeMarker()
        if (isAgentInstalled(AgentKind.CLAUDE_CODE, runtimeInstalled = true)) return
        hostInstaller.aptInstall(
            packages = listOf("ca-certificates", "curl", "git", "ripgrep"),
            message = str(R.string.rt_install_git_base_tools),
            fraction = fraction,
            onProgress = onProgress,
        )
        archiveExtractor.installRuntimeOverlay(
            bundle = CLAUDE_BUNDLE,
            destinationRootfs = rootfs,
            message = str(R.string.rt_install_installing_version, "Claude Code", CLAUDE_BUNDLED_VERSION),
            from = fraction,
            to = 0.995f,
            onProgress = onProgress,
        )
        hostInstaller.verifyGuest("$CLAUDE_GUEST_PATH --version", str(R.string.rt_install_verify_failed, "Claude Code"))
        require(claudeMarker.readTextOrNull() == CLAUDE_BUNDLED_VERSION) {
            "The Claude Code runtime bundle is incomplete"
        }
    }

    suspend fun ensureAgyInstalled(
        fraction: Float,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        if (isAgentInstalled(AgentKind.ANTIGRAVITY, runtimeInstalled = true)) return
        hostInstaller.aptInstall(
            packages = listOf("ca-certificates", "curl", "git"),
            message = str(R.string.rt_install_git_base_tools),
            fraction = fraction,
            onProgress = onProgress,
        )
        archiveExtractor.installRuntimeOverlay(
            bundle = AGY_BUNDLE,
            destinationRootfs = rootfs,
            message = str(R.string.rt_install_installing_version, "Antigravity CLI", AGY_VERSION),
            from = fraction,
            to = 0.995f,
            onProgress = onProgress,
            forceEmbedded = true,
        )
        hostInstaller.verifyGuest("${RuntimeInstaller.AGY_GUEST_PATH} --version", str(R.string.rt_install_verify_failed, "Antigravity CLI"))
        agyMarker.writeText(AGY_VERSION)
        require(isAgentInstalled(AgentKind.ANTIGRAVITY, runtimeInstalled = true)) {
            "Antigravity CLI installation is incomplete"
        }
    }

    suspend fun ensureDshInstalled(
        fraction: Float,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        stackInstaller.ensureNodeAndNpmViaNvm(fraction, onProgress)
        if (isAgentInstalled(AgentKind.DEEPSEEK_HARNESS, runtimeInstalled = true)) {
            ensureDshAndroidCompatibility()
            return
        }
        File(rootfs, "usr/local/lib/dsh").deleteRecursively()
        File(rootfs, "usr/local/lib/dsh.updating").deleteRecursively()
        File(rootfs, "usr/local/lib/dsh.previous").deleteRecursively()
        dshMarker.delete()
        dshAndroidCompatibilityMarker.delete()
        archiveExtractor.installRuntimeOverlay(
            bundle = DSH_BUNDLE,
            destinationRootfs = rootfs,
            message = str(R.string.rt_install_installing_version, "DeepSeek Harness", RuntimeInstaller.DSH_VERSION),
            from = fraction,
            to = 0.995f,
            onProgress = onProgress,
        )
        ensureDshAndroidCompatibility()
        hostInstaller.verifyGuest(
            "/usr/local/bin/dsh --profile headless --help",
            str(R.string.rt_install_verify_failed, "DeepSeek Harness"),
        )
        require(isAgentInstalled(AgentKind.DEEPSEEK_HARNESS, runtimeInstalled = true)) {
            "The DeepSeek Harness runtime bundle is incomplete"
        }
    }

    suspend fun ensureOpencodeInstalled(
        fraction: Float,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        if (isAgentInstalled(AgentKind.OPENCODE, runtimeInstalled = true)) return
        hostInstaller.aptInstall(
            packages = listOf("ca-certificates", "curl", "git", "unzip", "ripgrep"),
            message = str(R.string.rt_install_git_base_tools),
            fraction = fraction,
            onProgress = onProgress,
        )
        onProgress(RuntimeInstallProgress(str(R.string.rt_install_installing_named, "OpenCode"), fraction, indeterminate = true))
        hostInstaller.runGuestCommand(
            command = "set -e; " +
                "curl -fsSL https://opencode.ai/v2/install | bash; " +
                "mkdir -p /usr/local/bin; " +
                "if [ -x /root/.opencode/bin/opencode ]; then ln -sf ../../../root/.opencode/bin/opencode ${RuntimeInstaller.OPENCODE_GUEST_PATH}; fi; " +
                "test -x ${RuntimeInstaller.OPENCODE_GUEST_PATH}; " +
                "${RuntimeInstaller.OPENCODE_GUEST_PATH} service set disabled true >/dev/null 2>&1 || true",
            displayCommand = "curl -fsSL https://opencode.ai/v2/install | bash",
            fraction = (fraction + 0.99f) / 2f,
            timeoutMs = 15 * 60 * 1_000L,
            onProgress = onProgress,
            failureMessage = str(R.string.rt_install_verify_failed, "OpenCode"),
        )
        val version = probeOpencodeVersion()
        check(version.isNotBlank()) { str(R.string.rt_install_verify_failed, "OpenCode") }
        opencodeMarker.writeText(version)
        require(isAgentInstalled(AgentKind.OPENCODE, runtimeInstalled = true)) {
            "OpenCode CLI installation is incomplete"
        }
    }

    fun isGitInstalled(): Boolean =
        File(rootfs, "usr/bin/git").canExecute()

    suspend fun ensureGitInstalled(onProgress: suspend (RuntimeInstallProgress) -> Unit) {
        if (isGitInstalled()) return
        hostInstaller.aptInstall(
            packages = listOf("ca-certificates", "curl", "git"),
            message = str(R.string.rt_install_git_base_tools),
            fraction = 0.35f,
            onProgress = onProgress,
        )
        hostInstaller.verifyGuest("git --version", str(R.string.rt_install_verify_failed, "Git"))
    }

    fun isGitHubCliInstalled(runtimeInstalled: Boolean): Boolean = runtimeInstalled &&
        (File(rootfs, "usr/bin/gh").canExecute() || File(rootfs, RuntimeInstaller.GITHUB_CLI_GUEST_PATH.removePrefix("/")).canExecute())

    suspend fun ensureGitHubCliInstalled(
        runtimeInstalled: Boolean,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        if (isGitHubCliInstalled(runtimeInstalled)) {
            ensureGitHubCliSymlink()
            return
        }
        hostInstaller.aptInstall(
            packages = listOf("ca-certificates", "curl", "git", "gh"),
            message = str(R.string.rt_install_downloading_github_cli),
            fraction = 0.45f,
            onProgress = onProgress,
        )
        ensureGitHubCliSymlink()
        hostInstaller.verifyGuest("gh --version", str(R.string.rt_install_verify_failed, "GitHub CLI"))
        githubCliMarker.writeText("apt")
        check(isGitHubCliInstalled(runtimeInstalled = true)) { "GitHub CLI installation is incomplete" }
        onProgress(RuntimeInstallProgress(str(R.string.rt_install_ready, "GitHub CLI"), 1f))
    }

    private fun ensureGitHubCliSymlink() {
        val aptGh = File(rootfs, "usr/bin/gh")
        val legacyDest = File(rootfs, RuntimeInstaller.GITHUB_CLI_GUEST_PATH.removePrefix("/"))
        if (aptGh.exists()) {
            legacyDest.parentFile?.mkdirs()
            if (legacyDest.exists() || Files.isSymbolicLink(legacyDest.toPath())) {
                legacyDest.delete()
            }
            runCatching { Os.symlink("../../../usr/bin/gh", legacyDest.absolutePath) }
        }
    }

    fun checkAgentUpdates(runtimeInstalled: Boolean): Map<AgentKind, AgentUpdateInfo> {
        val installed = installedAgentVersions(runtimeInstalled)
        return buildMap {
            installed[AgentKind.CLAUDE_CODE]?.let { current ->
                runCatching {
                    JSONObject(archiveExtractor.fetchText("https://registry.npmjs.org/@anthropic-ai/claude-code/latest")).getString("version")
                }.getOrNull()?.takeIf { isVersionNewer(it, current) }?.let { latest ->
                    put(AgentKind.CLAUDE_CODE, AgentUpdateInfo(current, latest))
                }
            }
            installed[AgentKind.DEEPSEEK_HARNESS]?.let { current ->
                RuntimeInstaller.DSH_VERSION.takeIf { isVersionNewer(it, current) }?.let { compatible ->
                    put(AgentKind.DEEPSEEK_HARNESS, AgentUpdateInfo(current, compatible))
                }
            }
            installed[AgentKind.ANTIGRAVITY]?.let { current ->
                runCatching { fetchAgyManifest().getString("version") }.getOrNull()
                    ?.takeIf { isVersionNewer(it, current) }?.let { latest ->
                        put(AgentKind.ANTIGRAVITY, AgentUpdateInfo(current, latest))
                    }
            }
            installed[AgentKind.OPENCODE]?.let { current ->
                runCatching { fetchLatestOpencodeV2Version() }.getOrNull()
                    ?.takeIf { isVersionNewer(it, current) }?.let { latest ->
                        put(AgentKind.OPENCODE, AgentUpdateInfo(current, latest))
                    }
            }
        }
    }

    suspend fun updateAgent(
        agent: AgentKind,
        expectedVersion: String,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        when (agent) {
            AgentKind.CLAUDE_CODE -> updateClaude(expectedVersion, onProgress)
            AgentKind.DEEPSEEK_HARNESS -> updateDsh(expectedVersion, onProgress)
            AgentKind.ANTIGRAVITY -> updateAgy(expectedVersion, onProgress)
            AgentKind.OPENCODE -> updateOpencode(expectedVersion, onProgress)
        }
    }

    private suspend fun updateClaude(
        expectedVersion: String,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        val latest = JSONObject(archiveExtractor.fetchText("https://registry.npmjs.org/@anthropic-ai/claude-code/latest")).getString("version")
        check(latest == expectedVersion) { str(R.string.rt_install_newer_release, "Claude Code") }
        val base = "https://downloads.claude.ai/claude-code-releases/$latest"
        val manifest = JSONObject(archiveExtractor.fetchText("$base/manifest.json"))
        val checksum = manifest.getJSONObject("platforms").getJSONObject("linux-arm64").getString("checksum")
        val downloaded = File(archiveExtractor.downloads, "claude-$latest")
        val claudeDownloadLabel = str(R.string.rt_install_downloading_version, "Claude Code", latest)
        archiveExtractor.downloadVerified("$base/linux-arm64/claude", downloaded, checksum) { bytes, total ->
            val ratio = if (total > 0L) bytes.toFloat() / total else 0f
            onProgress(RuntimeInstallProgress(claudeDownloadLabel, ratio * 0.9f, bytes, total.takeIf { it > 0L }, event = RuntimeInstallEvent.DOWNLOAD))
        }
        val claude = File(rootfs, CLAUDE_GUEST_PATH.removePrefix("/"))
        claude.parentFile?.mkdirs()
        val staged = File(claude.parentFile, ".claude-$latest.installing")
        downloaded.copyTo(staged, overwrite = true)
        Os.chmod(staged.absolutePath, 0b111101101)
        Os.rename(staged.absolutePath, claude.absolutePath)
        downloaded.delete()
        hostInstaller.verifyGuest("$CLAUDE_GUEST_PATH --version", str(R.string.rt_install_update_verify_failed, "Claude Code"))
        claudeMarker.writeText(latest)
    }

    private suspend fun updateAgy(
        expectedVersion: String,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        val manifest = fetchAgyManifest()
        val latest = manifest.getString("version")
        check(latest == expectedVersion) { str(R.string.rt_install_newer_release, "Antigravity") }
        val downloaded = File(archiveExtractor.downloads, "antigravity-$latest-linux-arm64.tar.gz")
        val agyDownloadLabel = str(R.string.rt_install_downloading_version, "Antigravity CLI", latest)
        archiveExtractor.downloadVerified(manifest.getString("url"), downloaded, manifest.getString("sha512"), algorithm = "SHA-512") { bytes, total ->
            val ratio = if (total > 0L) bytes.toFloat() / total else 0f
            onProgress(RuntimeInstallProgress(agyDownloadLabel, ratio * 0.9f, bytes, total.takeIf { it > 0L }, event = RuntimeInstallEvent.DOWNLOAD))
        }
        val destination = File(rootfs, RuntimeInstaller.AGY_GUEST_PATH.removePrefix("/"))
        var found = false
        TarArchiveInputStream(GzipCompressorInputStream(BufferedInputStream(downloaded.inputStream()))).use { archive ->
            var entry = archive.nextEntry
            while (entry != null) {
                if (entry.isFile && entry.name.removePrefix("./") == "antigravity") {
                    val staged = File(destination.parentFile, ".agy-$latest.installing")
                    FileOutputStream(staged).use { archive.copyTo(it) }
                    Os.chmod(staged.absolutePath, 0b111101101)
                    Os.rename(staged.absolutePath, destination.absolutePath)
                    found = true
                    break
                }
                entry = archive.nextEntry
            }
        }
        downloaded.delete()
        check(found) { "Antigravity update archive is incomplete" }
        hostInstaller.verifyGuest("${RuntimeInstaller.AGY_GUEST_PATH} --version", str(R.string.rt_install_update_verify_failed, "Antigravity CLI"))
        agyMarker.writeText(latest)
    }

    private suspend fun updateDsh(
        expectedVersion: String,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        check(isSupportedDshVersion(expectedVersion)) {
            "DeepSeek Harness $expectedVersion is not compatible with this PocketDev build"
        }
        val quotedVersion = expectedVersion.replace(Regex("[^0-9A-Za-z.+-]"), "")
        check(quotedVersion == expectedVersion) { "Invalid DeepSeek Harness version" }
        stackInstaller.ensureNodeAndNpmViaNvm(0.25f, onProgress)
        hostInstaller.runGuestCommand(
            command = "set -e; next=/usr/local/lib/dsh.updating; " +
                "rm -rf \"${'$'}next\"; mkdir -p \"${'$'}next\"; " +
                "cd \"${'$'}next\"; npm init -y >/dev/null; " +
                "npm install --omit=dev --no-audit --no-fund @deepseek-ai/dsh@$quotedVersion",
            displayCommand = "npm install @deepseek-ai/dsh@$quotedVersion",
            fraction = 0.55f,
            timeoutMs = 20 * 60 * 1_000L,
            onProgress = onProgress,
            failureMessage = str(R.string.rt_install_dsh_update_failed),
        )
        val staged = File(rootfs, "usr/local/lib/dsh.updating")
        applyDshAndroidCompatibility(staged, expectedVersion)
        hostInstaller.runGuestCommand(
            command = "set -e; old=/usr/local/lib/dsh.previous; rm -rf \"${'$'}old\"; " +
                "mv /usr/local/lib/dsh \"${'$'}old\"; " +
                "if mv /usr/local/lib/dsh.updating /usr/local/lib/dsh; then :; " +
                "else mv \"${'$'}old\" /usr/local/lib/dsh; exit 1; fi",
            displayCommand = "Activate DeepSeek Harness $quotedVersion",
            fraction = 0.9f,
            timeoutMs = 60_000L,
            onProgress = onProgress,
            failureMessage = str(R.string.rt_install_dsh_update_failed),
        )
        val verified = runCatching {
            hostInstaller.verifyGuest("/usr/local/bin/dsh --profile headless --help", str(R.string.rt_install_update_verify_failed, "DeepSeek Harness"))
        }
        if (verified.isFailure) {
            runCatching {
                hostInstaller.runGuestCommand(
                    command = "set -e; rm -rf /usr/local/lib/dsh; mv /usr/local/lib/dsh.previous /usr/local/lib/dsh",
                    displayCommand = "Restore previous DeepSeek Harness",
                    fraction = 0.95f,
                    timeoutMs = 60_000L,
                    onProgress = onProgress,
                    failureMessage = str(R.string.rt_install_dsh_update_failed),
                )
            }
            verified.getOrThrow()
        }
        File(rootfs, "usr/local/lib/dsh.previous").deleteRecursively()
        dshMarker.writeText(expectedVersion)
        dshAndroidCompatibilityMarker.writeText(DSH_ANDROID_COMPATIBILITY_VERSION)
    }

    private suspend fun updateOpencode(
        expectedVersion: String,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        onProgress(RuntimeInstallProgress(str(R.string.rt_install_installing_version, "OpenCode", expectedVersion), 0.25f, indeterminate = true))
        hostInstaller.runGuestCommand(
            command = "set -e; " +
                "(${RuntimeInstaller.OPENCODE_GUEST_PATH} upgrade || curl -fsSL https://opencode.ai/v2/install | bash); " +
                "mkdir -p /usr/local/bin; " +
                "if [ -x /root/.opencode/bin/opencode ]; then ln -sf ../../../root/.opencode/bin/opencode ${RuntimeInstaller.OPENCODE_GUEST_PATH}; fi; " +
                "test -x ${RuntimeInstaller.OPENCODE_GUEST_PATH}; " +
                "${RuntimeInstaller.OPENCODE_GUEST_PATH} service set disabled true >/dev/null 2>&1 || true",
            displayCommand = "opencode upgrade",
            fraction = 0.75f,
            timeoutMs = 15 * 60 * 1_000L,
            onProgress = onProgress,
            failureMessage = str(R.string.rt_install_update_verify_failed, "OpenCode"),
        )
        val installedVer = probeOpencodeVersion().ifBlank { expectedVersion }
        opencodeMarker.writeText(installedVer)
    }

    private suspend fun probeOpencodeVersion(): String {
        val verify = hostInstaller.process(
            workspace = File(rootfs, "root"),
            environment = emptyMap(),
            guestCommand = listOf("/usr/bin/env", "bash", "-lc", "${RuntimeInstaller.OPENCODE_GUEST_PATH} --version"),
        )
        withTimeout(60_000L) {
            while (verify.isAlive) delay(50)
        }
        val exit = verify.waitFor()
        val outputFile = (verify as? NativeSpawnProcess)?.outputFile
        val output = outputFile?.let(hostInstaller::readProcessOutputSafely).orEmpty().trim()
        outputFile?.delete()
        check(exit == 0) { hostInstaller.actionableProcessError(output, str(R.string.rt_install_verify_failed, "OpenCode")) }
        return SEMVER_PATTERN.findAll(output).map { it.value }.lastOrNull().orEmpty()
    }

    fun ensureDshAndroidCompatibility() {
        if (!isAgentInstalled(AgentKind.DEEPSEEK_HARNESS, runtimeInstalled = true)) return
        applyDshAndroidCompatibility(File(rootfs, "usr/local/lib/dsh"), dshMarker.readTextOrNull().orEmpty())
        dshAndroidCompatibilityMarker.writeText(DSH_ANDROID_COMPATIBILITY_VERSION)
    }

    private fun applyDshAndroidCompatibility(dshRoot: File, version: String) {
        check(isSupportedDshVersion(version)) {
            "DeepSeek Harness $version is not compatible with this PocketDev build"
        }
        val persistence = File(dshRoot, "node_modules/@deepseek-ai/dsh-session-persistence-jsonl/lib/index.js")
        val localFs = File(dshRoot, "node_modules/@deepseek-ai/dsh-fs-local/lib/index.js")
        patchDshHardLinkPublication(
            file = persistence,
            importBefore = "import { link, mkdir, mkdtemp, open, readFile, readdir, realpath, rm, stat, truncate } from \"node:fs/promises\";",
            importAfter = "import { copyFile, link, mkdir, mkdtemp, open, readFile, readdir, realpath, rm, stat, truncate } from \"node:fs/promises\";",
            callBefore = "await link(tmp, finalPath);",
            callAfter = "await copyFile(tmp, finalPath, 1);",
        )
        patchDshHardLinkPublication(
            file = localFs,
            importBefore = "import { chmod, link, lstat, mkdir, open, readFile, readdir, realpath, rename, rm, stat } from \"node:fs/promises\";",
            importAfter = "import { chmod, copyFile, link, lstat, mkdir, open, readFile, readdir, realpath, rename, rm, stat } from \"node:fs/promises\";",
            callBefore = "await linkFile(tempPath, absolutePath);",
            callAfter = "await copyFile(tempPath, absolutePath, 1);",
        )
    }

    private fun patchDshHardLinkPublication(
        file: File,
        importBefore: String,
        importAfter: String,
        callBefore: String,
        callAfter: String,
    ) {
        check(file.isFile) { "DeepSeek Harness compatibility file is missing: ${file.name}" }
        var source = file.readText()
        if (callAfter in source && importAfter in source) return
        check(callBefore in source && importBefore in source) {
            "Installed DeepSeek Harness source is not compatible with this PocketDev build"
        }
        source = source.replace(importBefore, importAfter).replace(callBefore, callAfter)
        file.writeText(source)
    }

    fun migrateLegacyClaudeMarker() {
        if (claudeMarker.isFile) return
        val claude = File(rootfs, CLAUDE_GUEST_PATH.removePrefix("/"))
        if (!claude.isFile) return
        val legacyVersion = sequenceOf(
            coreReadyMarker.readTextOrNull(),
            bundledClaudeMarker.readTextOrNull(),
        ).mapNotNull { it?.trim() }.firstOrNull { it.matches(CLAUDE_VERSION_PATTERN) } ?: return
        claudeMarker.writeText(legacyVersion)
    }

    fun ensureSettingsAndHooks() {
        if (!rootfs.isDirectory) return
        val hook = File(rootfs, "opt/pocket/permission-hook.sh")
        hook.parentFile?.mkdirs()
        hook.writeText(
            """#!/bin/sh
cat > /dev/null
printf '%s\n' '{"hookSpecificOutput":{"hookEventName":"PermissionRequest","decision":{"behavior":"allow"}}}'
""",
        )
        Os.chmod(hook.absolutePath, 0b111101101)

        val allowedTools = JSONArray().apply {
            listOf("Bash", "Edit", "Write", "NotebookEdit", "Read", "Glob", "Grep").forEach(::put)
        }
        val settingsContent = JSONObject()
            .put("disableAllHooks", false)
            .put("permissions", JSONObject().put("allow", allowedTools).put("defaultMode", "acceptEdits"))
            .put(
                "hooks",
                JSONObject().put(
                    "PermissionRequest",
                    JSONArray().put(
                        JSONObject()
                            .put("matcher", "Bash|Edit|Write|NotebookEdit")
                            .put("hooks", JSONArray().put(JSONObject().put("type", "command").put("command", "/opt/pocket/permission-hook.sh"))),
                    ),
                ),
            )
            .toString()

        listOf(
            File(rootfs, "root/.claude/pocket-settings.json"),
            File(rootfs, "root/.claude/settings.json"),
            File(rootfs, "etc/claude/settings.json"),
        ).forEach { target ->
            target.parentFile?.mkdirs()
            target.writeText(settingsContent)
        }
        ensureWorkspaceTrust("/workspace")
    }

    fun ensureWorkspaceTrust(workspacePath: String) {
        if (!rootfs.isDirectory) return
        val stateFile = File(rootfs, "root/.claude.json")
        val state = runCatching { JSONObject(stateFile.readText()) }.getOrElse { JSONObject() }
        listOf("disableAllHooks", "permissions", "hooks", "allowedTools", "autoApprove").forEach(state::remove)
        val projects = state.optJSONObject("projects") ?: JSONObject()
        val workspace = projects.optJSONObject(workspacePath) ?: JSONObject()
        workspace.put("hasTrustDialogAccepted", true)
        projects.put(workspacePath, workspace)
        state.put("projects", projects)
        stateFile.parentFile?.mkdirs()
        stateFile.writeText(state.toString())
    }

    private fun fetchLatestOpencodeV2Version(): String? {
        val latest = runCatching {
            JSONObject(archiveExtractor.fetchText("https://registry.npmjs.org/@opencode/cli/latest")).optString("version")
        }.getOrNull()
        if (!latest.isNullOrBlank() && latest.startsWith("2.")) return latest
        val root = JSONObject(archiveExtractor.fetchText("https://registry.npmjs.org/@opencode/cli"))
        val tags = root.optJSONObject("dist-tags")
        if (tags != null) {
            for (key in listOf("latest", "v2", "next")) {
                val candidate = tags.optString(key)
                if (candidate.startsWith("2.")) return candidate
            }
        }
        return null
    }

    private fun fetchAgyManifest(): JSONObject = JSONObject(
        archiveExtractor.fetchText("https://antigravity-cli-auto-updater-974169037036.us-central1.run.app/manifests/linux_arm64.json"),
    )

    private fun isVersionNewer(candidate: String, current: String): Boolean {
        fun parts(value: String) = Regex("\\d+").findAll(value).map { it.value.toIntOrNull() ?: 0 }.toList()
        val left = parts(candidate)
        val right = parts(current)
        repeat(maxOf(left.size, right.size)) { index ->
            val comparison = (left.getOrElse(index) { 0 }).compareTo(right.getOrElse(index) { 0 })
            if (comparison != 0) return comparison > 0
        }
        return candidate != current && !candidate.contains("alpha", true) && !candidate.contains("rc", true)
    }

    private fun File.readTextOrNull(): String? = runCatching { readText().trim() }.getOrNull()

    companion object {
        private const val AGY_VERSION = "1.1.27"
        private const val OPENCODE_HOME_BIN_REL_PATH = "root/.opencode/bin/opencode"
        private const val CLAUDE_BUNDLED_VERSION = "2.1.316"
        private const val CLAUDE_GUEST_PATH = "/usr/local/bin/claude"
        private const val DSH_ANDROID_COMPATIBILITY_VERSION = "copyfile-excl-v1"
        private val CLAUDE_VERSION_PATTERN = Regex("[0-9]+\\.[0-9]+\\.[0-9]+")
        private val SEMVER_PATTERN = Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9A-Za-z.]+)?")

        private val CLAUDE_BUNDLE = RuntimeBundle(
            label = "Claude Code",
            fileName = "pocketdev-claude-arm64-2026.11.1.tar.zst",
            sha256 = "26316a6481983e5f42d362b4b6fdfa0441a1c27e9ddc74eb33fd4fcbba4fe3eb",
            compressedBytes = 116_997_995L,
        )
        private val DSH_BUNDLE = RuntimeBundle(
            label = "DeepSeek Harness",
            fileName = "pocketdev-dsh-arm64-2026.09.1.tar.zst",
            sha256 = "88e6a23ba74e1cd74a2c923b7e0d6bd78ba4b7e5f8a9b649f12bf4ffe158cce5",
            compressedBytes = 27_752_194L,
        )
        private val AGY_BUNDLE = RuntimeBundle(
            label = "Antigravity CLI",
            fileName = "pocketdev-agy-arm64-2026.09.1.tar.zst",
            sha256 = "a659ab9188956fc4721ca86fb21b5118e0e489f47a5e02ae6b4f2fb423659d78",
            compressedBytes = 41_870_025L,
        )
    }
}
