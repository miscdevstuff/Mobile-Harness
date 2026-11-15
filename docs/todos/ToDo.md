### ToDos

#### Terminal

- [ ] Multi-project terminal session pool: keep up to 3 project `ManagedTerminalSession` instances alive in an LRU map when switching between projects instead of terminating on `closeProject()`.

#### Release, Fork & Build Simplification (v2.x)

- [ ] **GitHub Actions Release Workflow:** Add `.github/workflows/release.yml` (manual `workflow_dispatch` and tag trigger) to build signed release APKs, auto-generate release notes/changelogs from commits, and upload artifacts to GitHub Releases.
- [ ] **Point App Updater to Fork & Bump to `2.x`:** Update `AppUpdater` (and any remaining upstream `techjarves/Mobile-Harness` release/manifest endpoints) to point to `Sohil876/Mobile-Harness`, and bump `versionCode` and `versionName` to `2.0.0`.
- [ ] **English-Only App (Remove Translations & Language Onboarding):** Delete non-English resource directories (`values-ar`, `values-es`, `values-hi`, `values-ru`, `values-zh`), remove the language picker in Settings, and remove the first-launch `InitialLanguageSetupScreen` onboarding step.
- [ ] **Remove `online` / `offline` APK Flavors:** Remove the `online` and `offline` product flavors in `app/build.gradle.kts` (and `BuildConfig.OFFLINE_MODE` code paths) to ship a single unified APK that downloads runtimes on demand.
