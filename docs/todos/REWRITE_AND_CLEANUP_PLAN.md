# Mobile Harness — Deferred Rewrite, Cleanup & Modularization Plan

This document captures the verified architectural invariants, completed performance/cleanup baseline, remaining bug/dead-code backlog, and phased modular rewrite plan for Mobile Harness (`com.jarves.mh`). It is designed to be adaptable to whatever features (such as MCP, Skills, or new `DevStack` toolchains) exist when the rewrite is resumed.

---

## 1. Completed Baseline (Already Applied in Steps 1–7c)

1. **Library Modernization:**
   - Replaced custom regex Markdown parser in `MarkdownText.kt` with `com.mikepenz:multiplatform-markdown-renderer-m3:0.32.0`.
   - Replaced manual `HttpURLConnection` code in `ProviderApiClient.kt`, `AppUpdater.kt`, and `RuntimeInstaller.kt` with a shared, connection-pooled `OkHttpClient` (`com.squareup.okhttp3:okhttp:4.12.0`).
   - Replaced manual `org.json` persistence loops in `AppPreferences.kt`, `RuntimeSetupService.kt`, `RuntimeInstaller.kt` (`devStacksFile`), and `MainViewModel.kt` (`ProjectTerminalSnapshot`) with `org.jetbrains.kotlinx:kotlinx-serialization-json`.
2. **Dead Code & Duplication Removed:**
   - Deleted `GitHubClient.kt` (superseded by `gh` CLI inside PRoot) and moved `GitHubRepository` to `Models.kt`.
   - Deleted `WorkspaceCheckpoints.kt`, `ChangesTab`, `DiffLineRow`, `ChangeItem`, `DiffLine`, `DiffLineType`, and `RuntimeEvent.FilesChanged` (Option A), eliminating full-project file copying and SHA-256 hashing on every prompt.
   - Removed ~685 lines of unreferenced UI composables (`AntigravityConnectionSettings`, `ConnectionSettings`, `WorkspaceLaunchExperience`, `LaunchStatusRow`, `DeviceCheckStep`, `CheckRow`, `ApiStatusChip`, `completedProcessSummary`, duplicate `FilesTab`) and 18 unused callback parameters across `SettingsScreenModern.kt` and `PocketDevApp.kt`.
   - Removed dead ZIP/tar helpers (`installZipAsset`, `extractZipArchive`, `extractRootfs`) and 12 unused legacy URL/SHA constants in `RuntimeInstaller.kt`; unified tar extraction into `extractTarStream`.
   - Extracted `BaseRuntimeBridge` in `RuntimeBridge.kt` across `ClaudeRuntimeBridge`, `DshRuntimeBridge`, `AntigravityRuntimeBridge`, and `OpencodeRuntimeBridge` (~720 lines deduplicated).
3. **Performance Fixes (`PERF-1` through `PERF-8`):**
   - **Unified Process Streaming (`PERF-1`):** Added `NativeSpawnProcess.forEachOutputLine` (with `onIdleTick` and `onOversizedLine` support) and migrated all 6 agent/installer loops (`RuntimeInstaller`, `ClaudeRuntimeBridge`, `AntigravityRuntimeBridge` x2, `OpencodeRuntimeBridge`, `DshRuntimeBridge`). Refactored both interactive terminal loops in `MainViewModel.kt` to sanitize per-chunk with ANSI boundary carry (`consumeCompleteAnsiChunk`) and throttle UI updates to 20 Hz.
   - **Chat Delta Coalescing (`PERF-2`):** Added 20 Hz (50 ms) `enqueueAssistantDelta` / `flushPendingAssistantDelta` coalescing in `MainViewModel.kt` with immediate flushing before non-delta events.
   - **Cold Launch Optimization (`PERF-3`):** Skipped heavy PRoot CLI boots in `MainViewModel.autoPingApi()` on cold start for OpenCode and Antigravity.
   - **State Split (`PERF-4`):** Moved `projectTerminalLiveOutput`, `androidBuildLog`, `androidLogcat`, and `diagnostics` out of `AppUiState` into dedicated `StateFlow`s on `MainViewModel`, collected only inside the specific UI branches that render them. Isolated Quick Terminal collectors inside `if (showQuickTerminal)`.
   - **Setup Disk Write Throttling (Bug 5):** Throttled `RuntimeSetupController.persist()` to 1 Hz during active download/output ticks.
   - **Single-Phase Thinking Dots (`PERF-6`):** Replaced 6 infinite float animations in `AnimatedThinkingDots` with 1 phase animation.
   - **`isInstalled()` TTL Cache (`PERF-8`):** Added a 2-second TTL cache to `RuntimeInstaller.isInstalled()`.

---

## 2. Hard Architectural Invariants (DO NOT BREAK During Rewrite)

1. **Keep `targetSdk = if (playBuild) 36 else 28` in `app/build.gradle.kts`:**
   - Do **not** set `targetSdk = 36` unconditionally for sideloaded builds.
   - Android 10+ (API 29+) places apps with `targetSdk >= 29` into the modern `untrusted_app` SELinux domain, which enforces strict `W^X` and blocks `execve` / `mmap(PROT_EXEC)` on guest ELF binaries unpacked inside `filesDir/runtime/ubuntu` (`app_data_file`).
   - Keeping `targetSdk = 28` while compiling against `compileSdk = 36` and `ndkVersion = "28.2.13676358"` retains the `untrusted_app_27` SELinux domain so the Linux kernel maps PRoot guest binaries directly from the page cache with zero `ptrace` anonymous-memory copy overhead.
2. **Keep `packaging.jniLibs.useLegacyPackaging = true`:**
   - Required because `libproot.so` and `libprootloader.so` are compiled via CMake carrier targets and executed directly from `nativeLibraryDir` (`apk_lib_file` SELinux label).
3. **Keep `.github/workflows/android.yml` Action Versions:**
   - `actions/checkout@v7`, `actions/setup-java@v6`, and `actions/upload-artifact@v7` are valid in the current CI environment. Do not downgrade them.
4. **Use Domain Controllers Under a Single Coordinator (`MainViewModel`), Not 10 Separate ViewModels:**
   - Cross-cutting mutex flags (`isRunning`, `projectTerminalRunning`, `androidBuildRunning`, `projectExportRunning`) guard project switching, agent switching, terminal execution, and Android builds.
   - Keep `MainViewModel` as the thin state/mutex coordinator and extract plain Kotlin domain controllers (`StartupController`, `ProjectsController`, `WorkspaceController`, `AndroidBuildController`, `AgentController`, `SettingsController`) that operate on shared state.
5. **Keep Atomic `kotlinx.serialization` JSON Files Instead of Room SQLite:**
   - `AppPreferences` and debounced per-chat atomic JSON files are fast, schema-flexible, and require zero KSP/migration boilerplate.

---

## 3. Remaining Bugs & Dead Code Backlog (To Fix When Resuming)

### P0 Bugs & Dead Code
1. **Dead Interactive Approval Subsystem (~200 lines + 50 ms Disk Poll):**
   - `RuntimeInstaller.ensureSettingsAndHooks()` writes `/opt/pocket/permission-hook.sh` to auto-allow all requests. Nothing ever writes `.request` files to `filesDir/runtime-bridge`.
   - Yet `ClaudeRuntimeBridge.watchPermissionRequests()` polls `bridge.listFiles()` every 50 ms during every Claude turn.
   - **Action:** Remove `watchPermissionRequests`, `pending`, `PendingPermission`, `respondToApproval`, `RuntimeEvent.{ToolRequested, ToolApproved, ToolRejected}`, `ToolRequest`, `RiskLevel`, `MainViewModel.answerApproval`, `AppUiState.pendingApproval`, `ApprovalCard`, and `AgentCapability.INTERACTIVE_APPROVALS`.
2. **MIUI APK Install State Hang (`AndroidAppInstaller.kt` & `MainViewModel.kt`):**
   - When `isMiuiDevice()` takes the `ACTION_INSTALL_PACKAGE` intent path, `PackageInstaller` session callbacks never fire, leaving `pendingAndroidInstallId` non-null and `androidBuildRunning = true` indefinitely.
   - **Action:** Use the standard `PackageInstaller.Session` path on modern MIUI/HyperOS, or emit a completion event / watchdog timeout when launching the fallback intent.
3. **CRLF Double Newlines (`NativeSpawnProcess.kt`):**
   - In `NativeSpawnProcess.forEachOutputLine`, change `.replace('\r', '\n')` to `.replace("\r\n", "\n").replace('\r', '\n')` so Windows `\r\n` sequences do not produce double blank lines.
4. **Dead C & UI Leftovers:**
   - Delete `app/src/main/cpp/pocket_launcher.c` (unreferenced in `CMakeLists.txt`).
   - Clean up the empty `if (!viewedAgentInstalled || viewedAgent != state.agentKind)` branch in `AgentScreen.kt`.

### P1 Security & Hygiene
5. **DNS-Rebinding Guard in `LocalNetwork.isPlainHttpAllowed`:**
   - Reject `0.0.0.0` and wildcard DNS-rebinding domains (`nip.io`, `sslip.io`, `xip.io`) when validating plain `http://` local URLs.
6. **Unbounded Response Reads:**
   - Cap `RuntimeInstaller.fetchText` at 1 MB and `AppUpdater.download` at a safe maximum APK byte size.
7. **Raw `Thread { ... }` Spawns:**
   - Replace `Thread { ... }.start()` in `BaseRuntimeBridge.installDefaultStopAction` and `ClaudeRuntimeBridge.terminateActiveProcessGracefully` with `CoroutineScope(Dispatchers.IO).launch`.

---

## 4. Phased Rewrite & Modularization Roadmap

### Phase 0 — Build System & Dependency Bumps (`minSdk = 28`, `compileSdk = 36`, `arm64-v8a`)
- Migrate `kotlinOptions.jvmTarget = "17"` in `app/build.gradle.kts` to the `kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17); freeCompilerArgs.add("-Xannotation-default-target=param-property") } }` DSL.
- Bump dependencies to latest `minSdk 28` + `compileSdk 36` compatible stable versions:
  - `androidx.compose:compose-bom:2025.12.00`
  - `androidx.core:core-ktx:1.18.0`
  - `androidx.activity:activity-compose:1.12.2`
  - `androidx.lifecycle:lifecycle-*:2.10.0`
  - `org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2`
  - `org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0`
  - `org.apache.commons:commons-compress:1.28.0`
  - `com.github.luben:zstd-jni:1.5.7-24@aar`
- Replace deprecated `Icons.Filled.{Chat, Undo, Redo, NoteAdd, ArrowBack, ArrowForward}` with `Icons.AutoMirrored.Filled.*`.

### Phase 1 — Dead Code Removal & P0 Bug Fixes
- Apply items #1–#7 from Section 3 above.

### Phase 2 — Consolidate Shared Utilities (`core/util/`)
Replace scattered duplicate helpers across the codebase with a single `com.jarves.mh.core.util` package:
- `Hashing.kt`: Single `File.sha256()`, `File.sha512()`, and `ByteArray.sha256()` (replaces duplicates in `RuntimeInstaller`, `AppUpdater`, `MainViewModel`, `AndroidAppInstaller`).
- `Ansi.kt`: Single `sanitizeTerminalOutput` and `consumeCompleteAnsiChunk` (replaces duplicates in `MainViewModel`, `RuntimeInstaller`, `AntigravityAuthController`).
- `Formatting.kt`: Single `formatBytes`, `formatDuration`, `formatTransferSpeed`, and `formatTransferEta` (replaces 8 duplicates across UI/runtime files).
- `SafePaths.kt`: Canonical path resolution and symlink rejection (`safeChild`, `safeWorkspaceFile`, `safeWorkspaceDirectory`).
- `FileIo.kt`: `File.atomicWrite(text)` and `File.readUtf8OrNull()`.

### Phase 3 — Split `Models.kt` into Domain Model Files
Split `model/Models.kt` into focused, `@Immutable` / `@Serializable` files:
- `model/ChatModels.kt` (`ChatMessage`, `ChatAttachment`, `ProjectChat`, `ActivityItem`)
- `model/ProjectModels.kt` (`Project`, `ProjectKind`, `ProjectType`, `AndroidTemplate`, `WorkspaceEntry`, `QuickChatIdentity`)
- `model/ProviderModels.kt` (`ProviderKind`, `ProviderProtocol`, `ProviderProfile`)
- `model/AgentModels.kt` (`AgentKind`, `DevStack`, `RuntimeEvent`, `GitHubRepository`)

### Phase 4 — Split `PocketDevApp.kt` & `MainViewModel.kt`
1. **UI Screen Split (`ui/screens/`):**
   - `onboarding/OnboardingScreens.kt` (`InitialLanguageSetupScreen`, `BackgroundTaskSetupScreen`, `RuntimeSetupPromptScreen`, `RuntimeInstallationScreen`, `StartupLoadingScreen`, `StartupErrorScreen`, `AntigravityOnboardingScreen`, `ProviderSetupScreen`)
   - `projects/ProjectsScreen.kt` (`ProjectsScreen`, `ProjectCard`, `ImportSourceButton`)
   - `workspace/WorkspaceScreen.kt` (`WorkspaceScreen`, `ReadOnlyProjectScreen`, `ChatSwitcherDialog`, `QuickTerminalSheet`)
   - `workspace/ChatTab.kt` (`ChatTab`, `MessageBubble`, `WorkBlockCard`, `ClaudeActivityDisclosure`, `QueuedFollowUpCard`, `AttachmentChip`)
   - `workspace/FilesTab.kt` (`FilesTab`, `FileViewerScreen`)
   - `workspace/PreviewTab.kt` (`PreviewTab`)
   - `android/AndroidDashboardScreen.kt` (`AndroidDashboardScreen`, `ProfessionalAndroidOverview`, `AndroidHealthCheckCard`)
2. **ViewModel Controller Extraction (`ui/controllers/`):**
   - Extract `AndroidBuildController`, `GitHubCliController`, `WorkspaceFileController`, `TerminalController`, and `AgentSetupController` under `MainViewModel`.

### Phase 5 — Optional APK Size & Git Diff Enhancements
- **Optional ~10 MB APK Reduction:** Replace `androidx.compose.material:material-icons-extended` with a generated `ui/AppIcons.kt` using `materialIcon` / `materialPath` from `material-icons-core` for the ~40 non-core icons used by the app.
- **Optional On-Demand Git Diff Viewer:** If a visual diff tab is desired in the future, run `git status --porcelain` / `git diff` on demand when the user opens the tab rather than copying and SHA-256 hashing the workspace on every prompt.
