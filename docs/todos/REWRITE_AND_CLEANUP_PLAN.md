# Mobile Harness — Rewrite, Cleanup & Modularization Plan (v2.x)

This document tracks the active architectural invariants, remaining bug and dead-code backlog, and phased modularization roadmap for Mobile Harness (`com.jarves.mh`).

---

## 1. Architectural Invariants (Must Preserve)

1. **Keep `targetSdk = if (playBuild) 36 else 28` in `app/build.gradle.kts`:**
   - Do **not** set `targetSdk = 36` unconditionally for sideloaded builds.
   - Android 10+ (API 29+) places apps with `targetSdk >= 29` into the modern `untrusted_app` SELinux domain, which enforces strict `W^X` and blocks `execve` / `mmap(PROT_EXEC)` on guest ELF binaries unpacked inside `filesDir/runtime/ubuntu` (`app_data_file`).
   - Keeping `targetSdk = 28` while compiling against `compileSdk = 36` and `ndkVersion = "28.2.13676358"` retains the `untrusted_app_27` SELinux domain so the Linux kernel maps PRoot guest binaries directly from the page cache with zero `ptrace` anonymous-memory copy overhead.
2. **Keep `packaging.jniLibs.useLegacyPackaging = true` and `jniLibs.pickFirsts += "**/libtermux.so"`:**
   - `useLegacyPackaging = true` is required because `libproot.so` and `libprootloader.so` are compiled via CMake carrier targets and executed directly from `nativeLibraryDir` (`apk_lib_file` SELinux label).
   - `jniLibs.pickFirsts += "**/libtermux.so"` ensures AGP packages our CMake 16 KB-aligned `libtermux.so` (`app/src/main/cpp/termux.c`, which enables `PR_SET_DUMPABLE` for PRoot `ptrace`) instead of the 4 KB prebuilt `libtermux.so` inside the Termux AAR.
3. **Keep `.github/workflows/android.yml` Action Versions (and Mirror in `release.yml`):**
   - `actions/checkout@v7`, `actions/setup-java@v6`, and `actions/upload-artifact@v7` are required in this CI environment. Do not downgrade them.
4. **Use Domain Controllers Under a Single Coordinator (`MainViewModel`), Not 10 Separate ViewModels:**
   - Cross-cutting mutex flags (`isRunning`, `androidBuildRunning`, `projectExportRunning`, plus active terminal session checks during host runtime updates) guard project switching, agent switching, host runtime updates, and Android builds.
   - Keep `MainViewModel` as the thin state/mutex coordinator and extract plain Kotlin domain controllers that operate on shared state.
5. **Keep Atomic `kotlinx.serialization` JSON Files Instead of Room SQLite:**
   - `AppPreferences` and debounced per-chat atomic JSON files remain fast, schema-flexible, and free of KSP/migration boilerplate.

---

## 2. Remaining Bugs & Dead Code Backlog

### P0 Bugs & Dead Code
1. **Dead Interactive Approval Subsystem (~200 lines + 50 ms Disk Poll):**
   - `RuntimeInstaller.ensureSettingsAndHooks()` writes `/opt/pocket/permission-hook.sh` to auto-allow all requests, and nothing writes `.request` files to `filesDir/runtime-bridge`, yet `ClaudeRuntimeBridge.watchPermissionRequests()` still polls `bridge.listFiles()` every 50 ms during every Claude turn.
   - **Action:** Remove `watchPermissionRequests`, `pending`, `PendingPermission`, `respondToApproval`, `RuntimeEvent.{ToolRequested, ToolApproved, ToolRejected}`, `ToolRequest`, `RiskLevel`, `MainViewModel.answerApproval`, `AppUiState.pendingApproval`, `ApprovalCard`, and `AgentCapability.INTERACTIVE_APPROVALS`.
2. **MIUI APK Install State Hang (`AndroidAppInstaller.kt` & `MainViewModel.kt`):**
   - When `isMiuiDevice()` takes the `ACTION_INSTALL_PACKAGE` intent path, `PackageInstaller` session callbacks never fire, leaving `pendingAndroidInstallId` non-null and `androidBuildRunning = true` indefinitely.
   - **Action:** Use the standard `PackageInstaller.Session` path on modern MIUI/HyperOS, or emit a completion event / watchdog timeout when launching the fallback intent.
3. **CRLF Double Newlines (`NativeSpawnProcess.kt`):**
   - In `NativeSpawnProcess.forEachOutputLine`, change `.replace('\r', '\n')` to `.replace("\r\n", "\n").replace('\r', '\n')` so Windows `\r\n` sequences do not produce double blank lines in agent and build logs.
4. **Dead C, UI & String Leftovers:**
   - Delete `app/src/main/cpp/pocket_launcher.c` (unreferenced in `CMakeLists.txt`).
   - Clean up the empty `if (!viewedAgentInstalled || viewedAgent != state.agentKind)` branch in `AgentScreen.kt`.
   - Remove the unused `ws_terminal_new_session` string from `strings_workspace.xml`.
   - Replace the static `logs: List<String>` rendering in `StartupErrorScreen` (`PocketDevApp.kt`) with `ReadOnlyMiniTerminal`.

### P1 Security & Hygiene
5. **DNS-Rebinding Guard in `LocalNetwork.isPlainHttpAllowed`:**
   - Reject `0.0.0.0` and wildcard DNS-rebinding domains (`nip.io`, `sslip.io`, `xip.io`) when validating plain `http://` local URLs.
6. **Unbounded Response Reads:**
   - Cap `RuntimeInstaller.fetchText` at 1 MB and `AppUpdater.download` at a safe maximum APK byte size.
7. **Raw `Thread { ... }` Spawns:**
   - Replace `Thread { ... }.start()` in `BaseRuntimeBridge.installDefaultStopAction`, `ClaudeRuntimeBridge.terminateActiveProcessGracefully`, `ManagedTerminalSession.terminate`, and `ReadOnlyTerminalSession.dispose` with `CoroutineScope(Dispatchers.IO).launch`.

---

## 3. Phased Rewrite & Modularization Roadmap (v2.x)

### Phase 0 — v2.x Build Simplification, Fork Updater & Release CI
- **Single Unified APK (Remove `online` / `offline` Flavors):** Remove the `online` and `offline` product flavors in `app/build.gradle.kts` and all `BuildConfig.OFFLINE_MODE` code paths to ship a single unified APK that provisions runtimes on demand (preserving `targetSdk = if (playBuild) 36 else 28`).
- **English-Only App (Remove Translations & Language Onboarding):** Delete non-English resource directories (`values-ar`, `values-es`, `values-hi`, `values-ru`, `values-zh`), remove the Settings language picker, and remove the first-launch `InitialLanguageSetupScreen` onboarding step.
- **Fork Updater & `v2.0.0` Version Bump:** Point `AppUpdater` and release/manifest endpoints to `Sohil876/Mobile-Harness`, and bump `versionCode` and `versionName` to `2.0.0`.
- **GitHub Actions Release Workflow:** Add `.github/workflows/release.yml` (manual `workflow_dispatch` and tag trigger) to build signed release APKs, auto-generate changelogs from commits, and upload artifacts to GitHub Releases.
- **Compiler DSL & Dependency Bumps:**
  - Migrate `kotlinOptions.jvmTarget = "17"` in `app/build.gradle.kts` to `kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17); freeCompilerArgs.add("-Xannotation-default-target=param-property") } }`.
  - Bump dependencies to latest `minSdk 28` + `compileSdk 36` compatible stable versions (`compose-bom:2025.12.00`, `core-ktx:1.18.0`, `activity-compose:1.12.2`, `lifecycle-*:2.10.0`, `kotlinx-coroutines-android:1.10.2`, `kotlinx-serialization-json:1.9.0`, `commons-compress:1.28.0`, `zstd-jni:1.5.7-24@aar`).
  - Replace deprecated `Icons.Filled.{Chat, Undo, Redo, NoteAdd, ArrowBack, ArrowForward}` with `Icons.AutoMirrored.Filled.*`.

### Phase 1 — Dead Code Removal & P0/P1 Bug Fixes
- Apply items #1–#7 from Section 2.

### Phase 2 — Consolidate Shared Utilities (`core/util/`)
Replace scattered duplicate helpers across the codebase with a single `com.jarves.mh.core.util` package:
- `Hashing.kt`: Single `File.sha256()`, `File.sha512()`, and `ByteArray.sha256()` (replaces duplicates in `RuntimeInstaller`, `AppUpdater`, `MainViewModel`, `AndroidAppInstaller`).
- `Ansi.kt`: Relocate `AnsiTextSanitizer.kt` into `core/util/` and migrate any remaining inline ANSI regex in `AntigravityAuthController.kt` to use it.
- `Formatting.kt`: Single `formatBytes`, `formatDuration`, `formatTransferSpeed`, and `formatTransferEta` (replaces duplicates across UI/runtime files).
- `SafePaths.kt`: Canonical path resolution and symlink rejection (`safeChild`, `safeWorkspaceFile`, `safeWorkspaceDirectory`).
- `FileIo.kt`: `File.atomicWrite(text)` and `File.readUtf8OrNull()`.

### Phase 3 — Split `Models.kt` into Domain Model Files
Split `model/Models.kt` into focused, `@Immutable` / `@Serializable` files:
- `model/ChatModels.kt` (`ChatMessage`, `ChatAttachment`, `ProjectChat`, `ActivityItem`)
- `model/ProjectModels.kt` (`Project`, `ProjectKind`, `ProjectType`, `AndroidTemplate`, `WorkspaceEntry`, `QuickChatIdentity`)
- `model/ProviderModels.kt` (`ProviderKind`, `ProviderProtocol`, `ProviderProfile`)
- `model/AgentModels.kt` (`AgentKind`, `DevStack`, `RuntimeEvent`, `GitHubRepository`)

### Phase 4 — Split `PocketDevApp.kt` & `MainViewModel.kt` + Multi-Project Terminal Pool
1. **Multi-Project Terminal Session Pool (`runtime/terminal/`):**
   - Replace the single `projectTerminalSession` in `MainViewModel` with an LRU session pool keeping up to 3 active `ManagedTerminalSession` instances alive across project switches instead of terminating on `closeProject()`.
2. **UI Screen Split (`ui/screens/`):**
   - `onboarding/OnboardingScreens.kt` (`BackgroundTaskSetupScreen`, `RuntimeSetupPromptScreen`, `RuntimeInstallationScreen`, `StartupLoadingScreen`, `StartupErrorScreen`, `AntigravityOnboardingScreen`, `ProviderSetupScreen`)
   - `projects/ProjectsScreen.kt` (`ProjectsScreen`, `ProjectCard`, `ImportSourceButton`)
   - `workspace/WorkspaceScreen.kt` (`WorkspaceScreen`, `ReadOnlyProjectScreen`, `ChatSwitcherDialog`, `QuickTerminalSheet`)
   - `workspace/ChatTab.kt` (`ChatTab`, `MessageBubble`, `WorkBlockCard`, `ClaudeActivityDisclosure`, `QueuedFollowUpCard`, `AttachmentChip`)
   - `workspace/FilesTab.kt` (`FilesTab`, `FileViewerScreen`)
   - `workspace/PreviewTab.kt` (`PreviewTab`)
   - `android/AndroidDashboardScreen.kt` (`AndroidDashboardScreen`, `ProfessionalAndroidOverview`, `AndroidHealthCheckCard`)
3. **ViewModel Controller Extraction (`ui/controllers/`):**
   - Extract `AndroidBuildController`, `GitHubCliController`, `WorkspaceFileController`, and `AgentSetupController` under `MainViewModel`.

### Phase 5 — Optional APK Size & Git Diff Enhancements
- **Optional ~10 MB APK Reduction:** Replace `androidx.compose.material:material-icons-extended` with a generated `ui/AppIcons.kt` using `materialIcon` / `materialPath` from `material-icons-core` for the non-core icons used by the app.
- **Optional On-Demand Git Diff Viewer:** Run `git status --porcelain` / `git diff` on demand when opening a diff view rather than copying and hashing the workspace on every prompt.