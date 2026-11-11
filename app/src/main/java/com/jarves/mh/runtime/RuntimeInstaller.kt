package com.jarves.mh.runtime

import android.content.Context
import android.os.Build
import android.os.SystemClock
import com.jarves.mh.R
import com.jarves.mh.model.AgentKind
import com.jarves.mh.model.DevStack
import com.jarves.mh.runtime.installer.AgentInstaller
import com.jarves.mh.runtime.installer.HostRuntimeInstaller
import com.jarves.mh.runtime.installer.HostRuntimeManifest
import com.jarves.mh.runtime.installer.HostRuntimeUpdateInfo
import com.jarves.mh.runtime.installer.RuntimeArchiveExtractor
import com.jarves.mh.runtime.installer.StackInstaller
import com.jarves.mh.ui.AppStrings
import com.jarves.mh.ui.localizedTitle
import java.io.File

data class InstalledRuntime(
    val proot: File,
    val rootfs: File,
)

data class RuntimeInstallProgress(
    val message: String,
    val fraction: Float,
    val downloadedBytes: Long? = null,
    val totalBytes: Long? = null,
    val terminalLine: String? = null,
    val indeterminate: Boolean = false,
    val event: RuntimeInstallEvent = RuntimeInstallEvent.STAGE,
)

data class AgentUpdateInfo(
    val installedVersion: String,
    val latestVersion: String,
)

internal fun isSupportedDshVersion(version: String): Boolean =
    version == RuntimeInstaller.DSH_VERSION

enum class RuntimeInstallEvent { STAGE, COMMAND, OUTPUT, DOWNLOAD, COMMAND_COMPLETED, COMPLETED }

class RuntimeInstaller(private val context: Context) {
    private val archiveExtractor = RuntimeArchiveExtractor(context)
    private val hostInstaller = HostRuntimeInstaller(context, archiveExtractor)
    private val stackInstaller = StackInstaller(context, hostInstaller, archiveExtractor)
    private val agentInstaller = AgentInstaller(context, hostInstaller, archiveExtractor, stackInstaller)

    private val rootfs: File get() = hostInstaller.rootfs
    private val coreReadyMarker: File get() = File(rootfs, ".pocket-runtime-ready")
    private val macosMetadataRepairMarker: File get() = File(rootfs, ".pocket-macos-metadata-repair")

    private fun str(@androidx.annotation.StringRes id: Int, vararg args: Any): String =
        AppStrings.get(context, id, *args)

    fun isInstalled(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - lastInstalledCheckAtElapsedRealtime < INSTALLED_CACHE_TTL_MS) {
            return cachedInstalledResult
        }
        val ready = hostInstaller.isHostRuntimeReady() &&
            hostInstaller.isUbuntuContainerReady() &&
            File(rootfs, "usr/bin/git").canExecute() &&
            coreReadyMarker.exists()
        if (ready) {
            hostInstaller.ensureLegacyRootfsSymlink()
            repairLegacyMacosMetadata()
        }
        cachedInstalledResult = ready
        lastInstalledCheckAtElapsedRealtime = now
        return ready
    }

    private fun repairLegacyMacosMetadata() {
        if (macosMetadataRepairMarker.isFile) return
        if (!rootfs.isDirectory) return
        archiveExtractor.stripMacosMetadataArtifacts(rootfs)
        macosMetadataRepairMarker.parentFile?.mkdirs()
        macosMetadataRepairMarker.writeText("1")
    }

    fun installedRuntime(): InstalledRuntime {
        check(isInstalled()) { str(R.string.rt_install_incomplete) }
        return InstalledRuntime(
            proot = hostInstaller.prootDistroBin,
            rootfs = rootfs,
        )
    }

    fun installedHostRuntimeVersion(): String =
        hostInstaller.installedHostRuntimeVersion()

    fun installedHostRuntimeManifest(): HostRuntimeManifest? =
        hostInstaller.installedHostRuntimeManifest()

    suspend fun checkHostRuntimeUpdate(): HostRuntimeUpdateInfo =
        hostInstaller.checkHostRuntimeUpdate()

    /**
     * Updates or repairs (when [force] is true) the isolated host runtime in
     * `filesDir/host-runtime/usr` while preserving `var/lib/proot-distro/containers`.
     */
    suspend fun updateOrRepairHostRuntime(
        force: Boolean = false,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ): HostRuntimeManifest {
        val manifest = hostInstaller.ensureHostRuntimeInstalled(
            force = force,
            from = 0.05f,
            to = 0.98f,
            onProgress = onProgress,
        )
        hostInstaller.ensureLegacyRootfsSymlink()
        invalidateInstalledCache()
        onProgress(
            RuntimeInstallProgress(
                message = "Host runtime ready (${manifest.version})",
                fraction = 1f,
                event = RuntimeInstallEvent.COMPLETED,
            ),
        )
        return manifest
    }

    fun cleanupLegacyWorkspaceScaffolding() {
        val workspaces = File(context.filesDir, "workspaces")
        workspaces.listFiles { file -> file.isDirectory }.orEmpty().forEach { workspace ->
            File(workspace, "README.md").deleteIfExact(LEGACY_README)
            File(workspace, "index.html").deleteIfExact(LEGACY_INDEX)

            listOf(
                File(workspace, ".claude/settings.json"),
                File(workspace, ".claude.json"),
            ).forEach { settings ->
                if (settings.isFile && settings.readTextOrNull()?.contains("/opt/pocket/permission-hook.sh") == true) {
                    settings.delete()
                }
            }
            File(workspace, ".claude").takeIf { it.isDirectory && it.list().isNullOrEmpty() }?.delete()
        }
    }

    private fun File.deleteIfExact(expected: String) {
        if (isFile && runCatching { readText() }.getOrNull() == expected) delete()
    }

    suspend fun ensureInstalled(
        selectedStacks: Set<DevStack> = emptySet(),
        agent: AgentKind = AgentKind.CLAUDE_CODE,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ): InstalledRuntime {
        require(
            supportsArm64Runtime(
                Build.SUPPORTED_ABIS,
                System.getProperty("os.arch"),
            ),
        ) { str(R.string.rt_install_unsupported_arch) }

        hostInstaller.ensureHostRuntimeInstalled(
            force = false,
            from = 0.02f,
            to = 0.28f,
            onProgress = onProgress,
        )
        hostInstaller.ensureUbuntuContainerInstalled(
            from = 0.30f,
            to = 0.68f,
            onProgress = onProgress,
        )

        agentInstaller.migrateLegacyClaudeMarker()
        agentInstaller.ensureSettingsAndHooks()
        coreReadyMarker.writeText("1")
        invalidateInstalledCache()

        val missingStacks = selectedStacks.filterNot(::isStackInstalled)
        missingStacks.forEachIndexed { index, stack ->
            val slice = 0.22f / maxOf(1, missingStacks.size)
            val from = 0.70f + index * slice
            stackInstaller.applyStack(stack, from, from + slice, onProgress)
        }

        agentInstaller.ensureAgentInstalled(agent, 0.93f, onProgress)
        invalidateInstalledCache()
        onProgress(RuntimeInstallProgress(str(R.string.rt_install_setup_complete), 1f, event = RuntimeInstallEvent.COMPLETED))
        return InstalledRuntime(hostInstaller.prootDistroBin, rootfs)
    }

    suspend fun ensureAgentInstalled(
        agent: AgentKind,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        check(isInstalled()) { str(R.string.rt_install_incomplete) }
        agentInstaller.ensureAgentInstalled(agent, 0.05f, onProgress)
        invalidateInstalledCache()
        onProgress(RuntimeInstallProgress(str(R.string.rt_install_ready, agent.localizedTitle(context)), 1f))
    }

    fun isAgentInstalled(agent: AgentKind): Boolean =
        agentInstaller.isAgentInstalled(agent, isInstalled())

    val dshVersion: String get() = agentInstaller.dshVersion
    val claudeVersion: String get() = agentInstaller.claudeVersion
    val agyVersion: String get() = agentInstaller.agyVersion
    val opencodeVersion: String get() = agentInstaller.opencodeVersion
    val githubCliVersion: String get() = agentInstaller.githubCliVersion

    fun isGitInstalled(): Boolean =
        isInstalled() && agentInstaller.isGitInstalled()

    suspend fun ensureGitInstalled(onProgress: suspend (RuntimeInstallProgress) -> Unit) {
        check(isInstalled()) { str(R.string.rt_install_incomplete) }
        agentInstaller.ensureGitInstalled(onProgress)
    }

    fun isGitHubCliInstalled(): Boolean =
        agentInstaller.isGitHubCliInstalled(isInstalled())

    suspend fun ensureGitHubCliInstalled(onProgress: suspend (RuntimeInstallProgress) -> Unit) {
        check(isInstalled()) { str(R.string.rt_install_incomplete) }
        agentInstaller.ensureGitHubCliInstalled(isInstalled(), onProgress)
    }

    fun installedAgentVersions(): Map<AgentKind, String> =
        agentInstaller.installedAgentVersions(isInstalled())

    suspend fun checkAgentUpdates(): Map<AgentKind, AgentUpdateInfo> =
        agentInstaller.checkAgentUpdates(isInstalled())

    suspend fun updateAgent(
        agent: AgentKind,
        expectedVersion: String,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        check(isInstalled()) { str(R.string.rt_install_incomplete) }
        agentInstaller.updateAgent(agent, expectedVersion, onProgress)
        invalidateInstalledCache()
        onProgress(
            RuntimeInstallProgress(
                str(R.string.rt_install_version_ready, agent.localizedTitle(context), expectedVersion),
                1f,
                event = RuntimeInstallEvent.COMPLETED,
            ),
        )
    }

    fun ensureDshAndroidCompatibility() {
        agentInstaller.ensureDshAndroidCompatibility()
    }

    suspend fun ensureStackInstalled(
        stack: DevStack,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        check(isInstalled()) { str(R.string.rt_install_incomplete) }
        stackInstaller.ensureStackInstalled(stack, onProgress)
        invalidateInstalledCache()
    }

    suspend fun removeStack(
        stack: DevStack,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        check(isInstalled()) { str(R.string.rt_install_incomplete) }
        stackInstaller.removeStack(
            stack = stack,
            isDshInstalled = isAgentInstalled(AgentKind.DEEPSEEK_HARNESS),
            onProgress = onProgress,
        )
        invalidateInstalledCache()
    }

    fun migrateLegacyToolMarkers() {
        stackInstaller.migrateLegacyToolMarkers()
    }

    fun installedStacks(): Set<DevStack> =
        stackInstaller.installedStacks()

    fun isStackInstalled(stack: DevStack): Boolean =
        stackInstaller.isStackInstalled(stack)

    fun ensureSettingsAndHooks() {
        agentInstaller.ensureSettingsAndHooks()
    }

    suspend fun initializeExisting(onProgress: suspend (RuntimeInstallProgress) -> Unit): InstalledRuntime {
        val installed = installedRuntime()
        onProgress(RuntimeInstallProgress(str(R.string.rt_install_checking_files), 0.15f))
        hostInstaller.configureUbuntuContainerFilesystem()
        hostInstaller.ensureLegacyRootfsSymlink()
        ensureSettingsAndHooks()
        File(context.filesDir, "runtime-bridge").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        onProgress(RuntimeInstallProgress(str(R.string.rt_install_preparing_bridge), 0.55f))
        onProgress(RuntimeInstallProgress(str(R.string.rt_install_runtime_ready), 1f))
        return installed
    }

    fun process(
        proot: File,
        rootfs: File,
        workspace: File,
        environment: Map<String, String>,
        guestCommand: List<String>,
        guestWorkspacePath: String = "/workspace",
        emulateHardLinks: Boolean = true,
        outputFile: File = File(context.cacheDir, "runtime-output-${System.nanoTime()}.log"),
        pseudoTerminal: Boolean = false,
        ptyRows: Int = 40,
        ptyColumns: Int = 120,
    ): Process = hostInstaller.process(
        workspace = workspace,
        environment = environment,
        guestCommand = guestCommand,
        guestWorkspacePath = guestWorkspacePath,
        emulateHardLinks = emulateHardLinks,
        outputFile = outputFile,
        pseudoTerminal = pseudoTerminal,
        ptyRows = ptyRows,
        ptyColumns = ptyColumns,
        androidToolsReady = stackInstaller.isAndroidToolsReady(),
        onBeforeLaunch = {
            stackInstaller.writeAndroidGradleConfiguration()
            agentInstaller.ensureWorkspaceTrust(guestWorkspacePath)
        },
    )

    private fun File.readTextOrNull(): String? = runCatching { readText().trim() }.getOrNull()

    companion object {
        const val AGY_GUEST_PATH = "/root/.local/bin/agy"
        const val OPENCODE_GUEST_PATH = "/usr/local/bin/opencode"
        const val GITHUB_CLI_GUEST_PATH = "/root/.local/bin/gh"
        const val DSH_VERSION = "0.1.2-rc.1"

        private const val LEGACY_README = "# Pocket Dev project\n\nThis project is managed locally on Android.\n"
        private const val LEGACY_INDEX = "<!doctype html><title>Pocket Dev</title><h1>Hello from Android</h1>\n"
        private const val INSTALLED_CACHE_TTL_MS = 2_000L

        @Volatile private var lastInstalledCheckAtElapsedRealtime = 0L
        @Volatile private var cachedInstalledResult = false

        fun invalidateInstalledCache() {
            lastInstalledCheckAtElapsedRealtime = 0L
        }
    }
}
