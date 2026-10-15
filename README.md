# Post-Update Analyzer

Android vendor updates often reinstall apps you removed, re-enable apps you disabled, or add new ones.
Post-Update Analyzer takes a snapshot of every app on the phone before the update and another one after it,
shows what changed (with real app names and icons), and lets you uninstall, disable or restore each app
with one click. Everything happens over ADB; every action is saved in a local history.

## For users

1. Install ADB ([Android SDK Platform-Tools](https://developer.android.com/tools/releases/platform-tools),
   or `sudo apt install adb`). Enable *USB debugging* on the phone.
2. Unpack the bundle for your system and double-click the app. Java is included.
   - Linux: `PostUpdateAnalyzer-linux-x64.tar.gz` → `PostUpdateAnalyzer/PostUpdateAnalyzer`
   - Windows: `PostUpdateAnalyzer-windows-x64.zip` → `PostUpdateAnalyzer\PostUpdateAnalyzer.exe`
     (unsigned: SmartScreen asks once, choose *More info → Run anyway*)
3. The app opens in its own window. Take a *Before update* snapshot, install the update, take an *After update*
   snapshot, compare. Closing the window quits the app.
   - The window is drawn by Chrome, Edge, Brave, Chromium or Vivaldi (Edge is part of Windows 10/11). Without one of
     them the app opens in your default browser and shows a small window with a *Quit* button.

Data is kept in `~/.local/share/post-update-analyzer` (Linux) or `%LOCALAPPDATA%\PostUpdateAnalyzer` (Windows).

**What the actions do** (same commands as [UAD-ng](https://github.com/Universal-Debloater-Alliance/universal-android-debloater-next-generation)):

| Action    | Command (Android 6+)                                   | Undo                                   |
|-----------|--------------------------------------------------------|----------------------------------------|
| Uninstall | `pm uninstall --user 0 <pkg>`                          | Restore (system apps keep their APK)   |
| Disable   | `pm disable-user --user 0 <pkg>` + `am force-stop`     | Restore                                |
| Restore   | `cmd package install-existing` and/or `pm enable`      | —                                      |

Uninstalling a *user-installed* app removes it completely; reinstall it from the app store if needed.

## How it works

- **Kotlin + Spring Boot 4**, server-rendered **Thymeleaf + htmx** pages, **SQLite** (Flyway migrations).
- App states come from `pm list packages` (`-u`, `-d`, `-s`) like UAD-ng.
- App names, icons and versions come from a tiny helper (`android-helper/`, compiled to DEX with D8) that is
  pushed to `/data/local/tmp`, run with `app_process` (like scrcpy) and deleted again. Nothing is installed on the phone.
  If a ROM blocks it, the app still works with package names.
- The web server listens on `127.0.0.1` only; changing requests must come from the app's own page.

## For developers

Requires JDK 25 (Gradle downloads one if missing).

```shell
./gradlew bootJar        # build/libs/post-update-analyzer.jar + build/dist/ bundles (skip bundles: -PskipBundles)
./gradlew test           # unit + integration tests with a simulated phone (fast, no tools needed)
./gradlew uiTest         # Playwright/Chromium clicks through the pages (simulated phone)
./gradlew emulatorTest   # real Android 13 emulator in Docker (needs Docker, /dev/kvm, adb)
```

- Run from source: `java -jar build/libs/post-update-analyzer.jar` (`--no-gui` for no browser/window,
  `--app.data-dir=…`, `--server.port=…`).
- The Linux bundle is made with `jpackage` (Linux hosts only). The Windows bundle is cross-built from Linux with
  Launch4j plus a pinned, checksum-verified Temurin JRE 25.
- `emulatorTest` starts `halimqarroum/docker-android:api-33` with Testcontainers (first run downloads ~2.7 GB).
  The test APKs in `src/test/resources/apks` are built by `scripts/build-test-apks.sh`.
