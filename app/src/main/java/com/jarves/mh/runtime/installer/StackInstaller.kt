package com.jarves.mh.runtime.installer

import android.content.Context
import android.system.Os
import com.jarves.mh.R
import com.jarves.mh.data.appJson
import com.jarves.mh.model.DevStack
import com.jarves.mh.runtime.RuntimeInstallProgress
import com.jarves.mh.ui.AppStrings
import com.jarves.mh.ui.localizedLabel
import java.io.File
import java.nio.file.Files
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

internal class StackInstaller(
    private val context: Context,
    private val hostInstaller: HostRuntimeInstaller,
    private val archiveExtractor: RuntimeArchiveExtractor,
) {
    private val rootfs: File get() = hostInstaller.rootfs
    private val devStacksFile: File get() = File(rootfs, ".pocket-dev-stacks.json")
    private val androidToolsMarker: File get() = File(rootfs, "root/.pocket-android-tools-version")

    private fun str(@androidx.annotation.StringRes id: Int, vararg args: Any): String =
        AppStrings.get(context, id, *args)

    fun isAndroidToolsReady(): Boolean =
        androidToolsMarker.readTextOrNull() == ANDROID_TOOLS_VERSION

    fun installedStacks(): Set<DevStack> = readDevStackState()
        .filterValues { it }
        .keys
        .mapNotNull { name -> runCatching { DevStack.valueOf(name) }.getOrNull() }
        .filter(::isStackInstalled)
        .toSet()

    fun isStackInstalled(stack: DevStack): Boolean {
        if (readDevStackState()[stack.name] != true) return false
        return when (stack) {
            DevStack.WEB -> isNodeInstalledInRootfs() && isNpmInstalledInRootfs()
            DevStack.PYTHON -> File(rootfs, "usr/bin/python3").exists()
            DevStack.CPP -> File(rootfs, "usr/bin/gcc").exists() && File(rootfs, "usr/bin/g++").exists()
            DevStack.PHP -> File(rootfs, "usr/bin/php").exists()
            DevStack.GODOT -> File(rootfs, "usr/local/bin/godot").canExecute()
            DevStack.ANDROID -> isAndroidToolsReady() &&
                File(rootfs, "root/android-sdk/platforms/android-36/android.jar").isFile &&
                File(rootfs, "root/android-sdk/build-tools/35.0.0/aapt2").isFile &&
                File(rootfs, "opt/gradle/gradle-8.14.3/bin/gradle").isFile &&
                File(rootfs, "root/maven/localMvnRepository").let { it.isDirectory && !it.list().isNullOrEmpty() } &&
                File(rootfs, "root/.gradle/init.d/pocketdev-android.gradle").isFile
        }
    }

    fun isNodeInstalledInRootfs(): Boolean =
        File(rootfs, "usr/local/bin/node").canExecute() ||
            File(rootfs, "usr/bin/node").canExecute()

    fun isNpmInstalledInRootfs(): Boolean =
        File(rootfs, "usr/local/bin/npm").exists() ||
            File(rootfs, "usr/bin/npm").exists()

    suspend fun ensureNodeAndNpmViaNvm(
        fraction: Float,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        if (isNodeInstalledInRootfs() && isNpmInstalledInRootfs()) {
            val verified = runCatching {
                hostInstaller.verifyGuest(
                    "node -v && npm -v && nvm --version",
                    str(R.string.rt_install_verify_failed, "Node.js"),
                )
            }
            if (verified.isSuccess) return
        }

        hostInstaller.aptInstall(
            packages = listOf("ca-certificates", "curl", "git", "libatomic1", "xz-utils"),
            message = str(R.string.rt_install_node),
            fraction = fraction,
            onProgress = onProgress,
        )

        val nvmSetupScript = """
            set -e
            export NVM_DIR="/root/.nvm"
            export PROFILE="/dev/null"
            mkdir -p "${'$'}NVM_DIR" /etc/profile.d /usr/local/bin
            curl -fsSL https://raw.githubusercontent.com/nvm-sh/nvm/$NVM_VERSION/install.sh | bash
            cat > /etc/profile.d/nvm.sh << 'EOF_NVM_SH'
export NVM_DIR="/root/.nvm"
if [ -s "${'$'}NVM_DIR/nvm.sh" ]; then
  \. "${'$'}NVM_DIR/nvm.sh"
fi
if [ "${'$'}(type -t nvm 2>/dev/null)" = "function" ] && [ "${'$'}(type -t __real_nvm 2>/dev/null)" != "function" ]; then
  eval "__real_${'$'}(declare -f nvm)"
  nvm() {
    __real_nvm "${'$'}@"
    local rc=${'$'}?
    if [ ${'$'}rc -eq 0 ]; then
      case "${'$'}{1:-}" in
        use|install|alias)
          local cur
          cur="${'$'}(__real_nvm current 2>/dev/null || true)"
          if [ -n "${'$'}cur" ] && [ "${'$'}cur" != "none" ] && [ "${'$'}cur" != "system" ]; then
            if [ "${'$'}{1:-}" = "use" ]; then
              __real_nvm alias default "${'$'}cur" >/dev/null 2>&1 || true
            fi
            for cmd in node npm npx corepack; do
              if [ -e "${'$'}NVM_DIR/versions/node/${'$'}cur/bin/${'$'}cmd" ]; then
                ln -sf "../../../root/.nvm/versions/node/${'$'}cur/bin/${'$'}cmd" "/usr/local/bin/${'$'}cmd"
              fi
            done
          fi
          ;;
      esac
    fi
    return ${'$'}rc
  }
fi
EOF_NVM_SH
            chmod 644 /etc/profile.d/nvm.sh
            set +e
            \. /etc/profile.d/nvm.sh
            nvm install --lts
            nvm_rc=${'$'}?
            if [ ${'$'}nvm_rc -ne 0 ]; then
              exit ${'$'}nvm_rc
            fi
            nvm alias default 'lts/*'
            nvm use default
            cur_ver="${'$'}(nvm current)"
            set -e
            for cmd in node npm npx corepack; do
              if [ -e "${'$'}NVM_DIR/versions/node/${'$'}cur_ver/bin/${'$'}cmd" ]; then
                ln -sf "../../../root/.nvm/versions/node/${'$'}cur_ver/bin/${'$'}cmd" "/usr/local/bin/${'$'}cmd"
              fi
            done
            node -v
            npm -v
            nvm --version
        """.trimIndent()

        hostInstaller.runGuestCommand(
            command = nvmSetupScript,
            displayCommand = "Install NVM ($NVM_VERSION) & Node.js LTS",
            fraction = (fraction + 0.95f) * 0.5f,
            timeoutMs = 20 * 60 * 1_000L,
            onProgress = onProgress,
            failureMessage = str(R.string.rt_install_verify_failed, "Node.js"),
        )
        hostInstaller.verifyGuest(
            "nvm --version && node -v && npm -v",
            str(R.string.rt_install_verify_failed, "Node.js"),
        )
    }

    suspend fun ensureStackInstalled(
        stack: DevStack,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        if (isStackInstalled(stack) && stack != DevStack.ANDROID) return
        applyStack(stack, 0.05f, 0.95f, onProgress)
        onProgress(RuntimeInstallProgress(str(R.string.rt_install_stack_ready, stack.localizedLabel(context)), 1f))
    }

    suspend fun applyStack(
        stack: DevStack,
        from: Float,
        to: Float,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        var verified = true
        when (stack) {
            DevStack.WEB -> ensureNodeAndNpmViaNvm(from, onProgress)
            DevStack.PYTHON -> {
                hostInstaller.aptInstall(
                    packages = listOf("ca-certificates", "curl", "python3", "python3-pip", "python3-venv"),
                    message = str(R.string.rt_install_python),
                    fraction = from,
                    onProgress = onProgress,
                )
                runCatching {
                    hostInstaller.verifyGuest(
                        "python3 --version && pip3 --version",
                        str(R.string.rt_install_verify_failed, "Python"),
                    )
                }.onFailure { error ->
                    verified = false
                    onProgress(
                        RuntimeInstallProgress(
                            str(R.string.rt_install_python_verify_failed, error.message?.take(120).orEmpty()),
                            to,
                        ),
                    )
                }
            }
            DevStack.ANDROID -> installAndroidToolchain(from, to, onProgress)
            DevStack.CPP -> {
                hostInstaller.aptInstall(
                    packages = listOf("build-essential", "gcc", "g++", "make", "cmake", "gdb", "pkg-config"),
                    message = str(R.string.rt_install_cpp),
                    fraction = from,
                    onProgress = onProgress,
                )
                hostInstaller.verifyGuest(
                    "gcc --version && g++ --version && make --version && cmake --version",
                    str(R.string.rt_install_verify_failed, "C/C++"),
                )
            }
            DevStack.PHP -> {
                hostInstaller.aptInstall(
                    packages = listOf("ca-certificates", "curl", "unzip", "php-cli", "php-mbstring", "php-xml", "php-curl", "php-zip", "composer"),
                    message = str(R.string.rt_install_php),
                    fraction = from,
                    onProgress = onProgress,
                )
                val aptComposer = File(rootfs, "usr/bin/composer")
                val localComposer = File(rootfs, "usr/local/bin/composer")
                if (aptComposer.exists()) {
                    localComposer.parentFile?.mkdirs()
                    if (localComposer.exists() || Files.isSymbolicLink(localComposer.toPath())) {
                        localComposer.delete()
                    }
                    runCatching { Os.symlink("../../bin/composer", localComposer.absolutePath) }
                }
                hostInstaller.verifyGuest(
                    "php --version && composer --version",
                    str(R.string.rt_install_verify_failed, "PHP"),
                )
            }
            DevStack.GODOT -> installGodotToolchain(from, onProgress)
        }
        if (!verified) return
        writeDevStackState(readDevStackState().apply { put(stack.name, true) })
        onProgress(RuntimeInstallProgress(str(R.string.rt_install_stack_installed, stack.localizedLabel(context)), to))
    }

    suspend fun removeStack(
        stack: DevStack,
        isDshInstalled: Boolean,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        if (!isStackInstalled(stack)) return
        onProgress(RuntimeInstallProgress(str(R.string.rt_install_removing_stack, stack.localizedLabel(context)), 0.1f, indeterminate = true))
        when (stack) {
            DevStack.WEB -> {
                val neededByOther = isStackInstalled(DevStack.GODOT) || isDshInstalled
                if (!neededByOther) {
                    listOf("node", "npm", "npx", "corepack").forEach { cmd ->
                        removePath(File(rootfs, "usr/local/bin/$cmd"))
                    }
                    removePath(File(rootfs, "root/.nvm"))
                    removePath(File(rootfs, "etc/profile.d/nvm.sh"))
                    hostInstaller.configureUbuntuContainerFilesystem()
                }
            }
            DevStack.PYTHON -> {
                hostInstaller.aptRemove(listOf("python3-pip", "python3-venv"), 0.45f, onProgress)
                removePath(File(rootfs, "root/.cache/pip"))
            }
            DevStack.ANDROID -> removeAndroidStack()
            DevStack.CPP -> hostInstaller.aptRemove(
                listOf("build-essential", "gcc", "g++", "make", "cmake", "gdb", "pkg-config"),
                0.45f,
                onProgress,
            )
            DevStack.PHP -> {
                hostInstaller.aptRemove(
                    listOf("php-cli", "php-mbstring", "php-xml", "php-curl", "php-zip", "composer"),
                    0.45f,
                    onProgress,
                )
                removePath(File(rootfs, "usr/local/bin/composer"))
                removePath(File(rootfs, "root/.cache/composer"))
                removePath(File(rootfs, "root/.composer"))
            }
            DevStack.GODOT -> {
                removePath(File(rootfs, "usr/local/bin/godot"))
                removePath(File(rootfs, "usr/local/lib/godot-mcp"))
            }
        }
        writeDevStackState(readDevStackState().apply { put(stack.name, false) })
        onProgress(RuntimeInstallProgress(str(R.string.rt_install_stack_removed, stack.localizedLabel(context)), 1f))
    }

    private suspend fun installAndroidToolchain(
        from: Float,
        to: Float,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        hostInstaller.aptInstall(
            packages = listOf("ca-certificates", "curl", "git", "unzip", "openjdk-17-jdk-headless"),
            message = str(R.string.rt_install_android_tools),
            fraction = from,
            onProgress = onProgress,
        )
        val androidHome = File(rootfs, "root/android-sdk")
        val gradleHome = File(rootfs, "opt/gradle")
        val localMaven = File(rootfs, "root/maven/localMvnRepository")
        if (androidToolsMarker.readTextOrNull() != ANDROID_TOOLS_VERSION ||
            !File(androidHome, "platforms/android-36/android.jar").isFile ||
            !File(androidHome, "build-tools/35.0.0/aapt2").isFile ||
            !File(gradleHome, "gradle-8.14.3/bin/gradle").isFile ||
            !localMaven.isDirectory
        ) {
            archiveExtractor.installRuntimeOverlay(
                bundle = ANDROID_BUNDLE,
                destinationRootfs = rootfs,
                message = str(R.string.rt_install_android_tools),
                from = from,
                to = to,
                onProgress = onProgress,
            )
            makeAndroidToolsExecutable(androidHome, gradleHome)
            androidToolsMarker.parentFile?.mkdirs()
            androidToolsMarker.writeText(ANDROID_TOOLS_VERSION)
        }
        writeAndroidGradleConfiguration()
        hostInstaller.verifyGuest(
            command = "java -version 2>&1 | grep -E '\"17\\.|version 17' && " +
                "gradle --version && aapt2 version && test -f \"${'$'}ANDROID_HOME/platforms/android-36/android.jar\"",
            failureMessage = str(R.string.rt_install_verify_failed, "Android SDK, Gradle, Java"),
            androidToolsReady = true,
        )
    }

    private suspend fun installGodotToolchain(
        from: Float,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        ensureNodeAndNpmViaNvm(from, onProgress)
        hostInstaller.aptInstall(
            packages = listOf(
                "ca-certificates", "curl", "unzip", "coreutils",
                "libfontconfig1", "libfreetype6", "libgl1", "libx11-6",
                "libxcursor1", "libxinerama1", "libxi6", "libxrandr2", "libxrender1",
            ),
            message = "Installing Godot 4 headless dependencies…",
            fraction = (from + 0.5f) * 0.5f,
            onProgress = onProgress,
        )
        val installScript = """
            set -e
            mkdir -p /tmp/godot-install /usr/local/bin /usr/local/lib/godot-mcp/build
            curl -fL --retry 3 -o /tmp/godot-install/SHA512-SUMS.txt https://github.com/godotengine/godot/releases/download/4.7.2-stable/SHA512-SUMS.txt
            curl -fL --retry 3 -o /tmp/godot-install/Godot_v4.7.2-stable_linux.arm64.zip https://github.com/godotengine/godot/releases/download/4.7.2-stable/Godot_v4.7.2-stable_linux.arm64.zip
            (cd /tmp/godot-install && grep -F "  Godot_v4.7.2-stable_linux.arm64.zip" SHA512-SUMS.txt | sha512sum -c -)
            unzip -o /tmp/godot-install/Godot_v4.7.2-stable_linux.arm64.zip -d /tmp/godot-install
            mv /tmp/godot-install/Godot_v4.7.2-stable_linux.arm64 /usr/local/bin/godot
            chmod 755 /usr/local/bin/godot
            rm -rf /tmp/godot-install
            npm install --prefix /usr/local/lib/godot-mcp @coding-solo/godot-mcp
            cat > /usr/local/lib/godot-mcp/build/index.js << 'EOF_MCP'
#!/usr/bin/env node
import('node:fs').then(async ({ readFileSync }) => {
  const pkgDir = '/usr/local/lib/godot-mcp/node_modules/@coding-solo/godot-mcp';
  const pkg = JSON.parse(readFileSync(pkgDir + '/package.json', 'utf8'));
  const binEntry = typeof pkg.bin === 'string' ? pkg.bin : (pkg.bin && Object.values(pkg.bin)[0]);
  const entry = binEntry || pkg.main || 'build/index.js';
  await import(pkgDir + '/' + entry.replace(/^\.\//, ''));
}).catch((err) => {
  console.error(err);
  process.exit(1);
});
EOF_MCP
            chmod 755 /usr/local/lib/godot-mcp/build/index.js
        """.trimIndent()
        hostInstaller.runGuestCommand(
            command = installScript,
            displayCommand = "Install Godot 4 ARM64 & Godot MCP",
            fraction = 0.65f,
            timeoutMs = 20 * 60_000L,
            onProgress = onProgress,
            failureMessage = "Godot 4 toolchain installation failed",
        )
    }

    private fun makeAndroidToolsExecutable(androidHome: File, gradleHome: File) {
        val buildTools = File(androidHome, "build-tools/35.0.0")
        listOf("aapt", "aapt2", "aidl", "apksigner", "d8", "dexdump", "split-select", "zipalign")
            .map { File(buildTools, it) }
            .plus(File(gradleHome, "gradle-8.14.3/bin/gradle"))
            .filter(File::isFile)
            .forEach { Os.chmod(it.absolutePath, 0b111101101) }
    }

    @Synchronized
    fun writeAndroidGradleConfiguration() {
        val aapt2 = File(rootfs, ANDROID_AAPT2_HOST_PATH)
        if (!aapt2.isFile) return

        val script = File(rootfs, "root/.gradle/init.d/pocketdev-android.gradle")
        script.parentFile?.mkdirs()
        script.writeText(
            """
            def pocketMaven = uri('/root/maven/localMvnRepository')
            beforeSettings { settings ->
                settings.pluginManagement.repositories {
                    maven { url = pocketMaven }
                    google()
                    mavenCentral()
                    gradlePluginPortal()
                }
            }
            settingsEvaluated { settings ->
                settings.dependencyResolutionManagement.repositories {
                    maven { url = pocketMaven }
                }
            }
            gradle.beforeProject { project ->
                project.extensions.extraProperties.set(
                    'android.aapt2FromMavenOverride',
                    '/root/android-sdk/build-tools/35.0.0/aapt2'
                )
                project.buildscript.repositories {
                    maven { url = pocketMaven }
                }
            }
            """.trimIndent() + "\n",
        )

        val gradleDir = File(rootfs, "root/.gradle").apply { mkdirs() }
        val properties = File(gradleDir, "gradle.properties")
        val existingLines = properties.readTextOrNull()?.lineSequence()?.toList().orEmpty()
        val managedLines = listOf(
            "$ANDROID_AAPT2_PROPERTY=$ANDROID_AAPT2_GUEST_PATH",
            "$GRADLE_JVMARGS_PROPERTY=-Xmx512m -XX:MaxMetaspaceSize=256m -Dfile.encoding=UTF-8",
            "$GRADLE_WORKERS_PROPERTY=2",
            "$GRADLE_PARALLEL_PROPERTY=false",
            "$KOTLIN_EXECUTION_PROPERTY=in-process",
        )
        val updatedLines = existingLines.filterNot(::isManagedAndroidGradleProperty) + managedLines
        if (existingLines == updatedLines) return

        val temporary = File(gradleDir, "gradle.properties.pocketdev.tmp")
        temporary.writeText(updatedLines.joinToString("\n").trimEnd() + "\n")
        Os.rename(temporary.absolutePath, properties.absolutePath)
    }

    private fun removeAndroidStack() {
        listOf(
            "root/android-sdk",
            "root/maven",
            "root/.pocket-android-tools-version",
            "root/.gradle/caches",
            "root/.gradle/daemon",
            "root/.gradle/native",
            "root/.gradle/notifications",
            "root/.gradle/wrapper/dists",
            "root/.gradle/init.d/pocketdev-android.gradle",
            "opt/gradle",
        ).forEach { removePath(File(rootfs, it)) }
        val properties = File(rootfs, "root/.gradle/gradle.properties")
        if (properties.isFile) {
            val remaining = properties.readLines().filterNot(::isManagedAndroidGradleProperty)
            if (remaining.isEmpty()) properties.delete()
            else properties.writeText(remaining.joinToString("\n").trimEnd() + "\n")
        }
    }

    private fun isManagedAndroidGradleProperty(line: String): Boolean =
        MANAGED_ANDROID_GRADLE_PROPERTIES.any { key ->
            Regex("^\\s*${Regex.escape(key)}\\s*[:=].*$").matches(line)
        }

    fun migrateLegacyToolMarkers() {
        if (!File(rootfs, "usr/bin/bash").isFile) return
        val languageToolsMarker = File(rootfs, ".pocket-language-tools-version")
        if (languageToolsMarker.readTextOrNull() != LANGUAGE_TOOLS_VERSION) return
        val state = readDevStackState()
        DevStack.entries.forEach { stack -> if (!state.containsKey(stack.name)) state[stack.name] = true }
        writeDevStackState(state)
    }

    private fun readDevStackState(): MutableMap<String, Boolean> {
        if (!devStacksFile.isFile) return mutableMapOf()
        return runCatching {
            val decoded = appJson.decodeFromString<Map<String, Boolean>>(devStacksFile.readText())
            mutableMapOf<String, Boolean>().apply {
                DevStack.entries.forEach { stack ->
                    decoded[stack.name]?.let { put(stack.name, it) }
                }
            }
        }.getOrDefault(mutableMapOf())
    }

    private fun writeDevStackState(state: Map<String, Boolean>) {
        devStacksFile.parentFile?.mkdirs()
        devStacksFile.writeText(appJson.encodeToString(state))
    }

    private fun removePath(file: File) {
        if (file.isDirectory && !Files.isSymbolicLink(file.toPath())) {
            check(file.deleteRecursively()) { "Could not remove ${file.name}" }
        } else if (file.exists() || Files.isSymbolicLink(file.toPath())) {
            check(file.delete()) { "Could not remove ${file.name}" }
        }
    }

    private fun File.readTextOrNull(): String? = runCatching { readText().trim() }.getOrNull()

    companion object {
        private const val NVM_VERSION = "v0.40.3"
        private const val LANGUAGE_TOOLS_VERSION = "node-v24.19.0-python3-v1"
        const val ANDROID_TOOLS_VERSION = "sdk36-build-tools35-gradle8.14.3-maven-2026.09"
        private const val ANDROID_AAPT2_PROPERTY = "android.aapt2FromMavenOverride"
        private const val GRADLE_JVMARGS_PROPERTY = "org.gradle.jvmargs"
        private const val GRADLE_WORKERS_PROPERTY = "org.gradle.workers.max"
        private const val GRADLE_PARALLEL_PROPERTY = "org.gradle.parallel"
        private const val KOTLIN_EXECUTION_PROPERTY = "kotlin.compiler.execution.strategy"
        private val MANAGED_ANDROID_GRADLE_PROPERTIES = setOf(
            ANDROID_AAPT2_PROPERTY,
            GRADLE_JVMARGS_PROPERTY,
            GRADLE_WORKERS_PROPERTY,
            GRADLE_PARALLEL_PROPERTY,
            KOTLIN_EXECUTION_PROPERTY,
        )
        private const val ANDROID_AAPT2_GUEST_PATH = "/root/android-sdk/build-tools/35.0.0/aapt2"
        private const val ANDROID_AAPT2_HOST_PATH = "root/android-sdk/build-tools/35.0.0/aapt2"
        private val ANDROID_BUNDLE = RuntimeBundle(
            label = "Android",
            fileName = "pocketdev-android-arm64-2026.09.1.tar.zst",
            sha256 = "01bea058ebcb17416d1eb08c0211b3782da3228eb3c8348eb3719f2d61dd3ec6",
            compressedBytes = 569_652_007L,
        )
    }
}
