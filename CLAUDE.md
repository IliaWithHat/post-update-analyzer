# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A desktop app (Kotlin, Spring Boot 4.1, Java 25) that snapshots the state of every Android app on a phone over ADB
before and after a vendor firmware update, shows the differences (with real app names and icons), and lets the
user uninstall / disable / restore apps. Everything goes through the user's own `adb` binary. UI is server-rendered
Thymeleaf + htmx, served on `127.0.0.1` and opened in the browser. Storage is SQLite. ADB command rules are ported
from UAD-ng (github.com/Universal-Debloater-Alliance/universal-android-debloater-next-generation, `crates/uad-core/src/sync.rs`).

## Commands

Gradle needs JDK 25. If `java` on PATH is older (it is 21 on the author's machine), set `JAVA_HOME` to a JDK 25
(locally `~/.java-jdks/jdk-25.0.3`); otherwise the foojay plugin downloads one.

```shell
./gradlew test                      # fast unit + Spring integration tests with a simulated phone (excludes tags ui, emulator)
./gradlew uiTest                    # Playwright/Chromium against the app + simulated phone (tag "ui")
./gradlew emulatorTest              # real Android 13 emulator in Docker via Testcontainers (tag "emulator"); needs Docker, /dev/kvm, adb
./gradlew test --tests 'com.ilia.postupdateanalyzer.action.service.PackageActionServiceTest'
./gradlew uiTest --tests '*UiFlowTest*'     # tagged tests only run in their own task, not via `test --tests`
./gradlew bootJar                   # build/libs/post-update-analyzer.jar, then (finalizedBy packageApp) build/dist/ bundles
./gradlew bootJar -PskipBundles     # jar only
java -jar build/libs/post-update-analyzer.jar --no-gui --app.data-dir=/tmp/pua --server.port=18799
```

There is no linter configured. `check` runs `test` + `uiTest`. UI test screenshots land in `build/ui-screenshots/`.

## Code layout

One file = one class, enum, interface or object, named after it. Only two kinds of grouping are allowed: related data
classes share a `*Models.kt` file (e.g. `device/model/DeviceModels.kt`), and related exceptions share a `*Exceptions.kt`
file (e.g. `adb/exception/AdbExceptions.kt`). A helper used by only one class becomes a private nested class instead of
a second top-level class in the file.

Functions always have a block body with an explicit return type and `return`, never an expression body:
`fun list(id: Long): List<Snapshot> { return snapshots.findByDevice(id) }` (on separate lines), not
`fun list(id: Long): List<Snapshot> = snapshots.findByDevice(id)`. Property getters (`get() = …`) stay as they are.

Packages: feature first, then role. A feature package (`action`, `adb`, `compare`, `device`, `metadata`, `settings`,
`snapshot`, `web`, `desktop`) holds no files of its own, only role sub-packages:
- common roles: `service`, `repository`, `model` (data classes + enums), `exception`, `parser`;
- `adb`: `client` (`AdbClient`), `executor`, `environment` (finding adb), `command` (shell commands, package names);
- `web`: `controller`, `error` (error handler/controller), `filter`, `view` (view builders, template helpers),
  `model` (view models), `htmx`;
- `desktop`: `launcher` (before Spring starts), `window` (app window), `browser`, `lifecycle` (Spring-side start/quit).
`config` holds Spring configuration; `platform.OperatingSystem` is shared by `adb` and `desktop`. Tests mirror the main
packages; test-only code lives in `support` (`fake`, `process`) and `emulator.support`.

## Architecture

**Request path:** controller (`web/controller/`) → service (`<feature>/service/`) → `AdbClient` →
`AdbExecutor`. `ProcessAdbExecutor` gets the adb path from `AdbEnvironment` (`LocalAdbEnvironment`: settings table →
`app.adb-path` → `AdbLocator` search of PATH/ANDROID_HOME/SDK dirs). Tests swap `AdbExecutor`, `AdbEnvironment` and
`Clock` for fakes via `@Primary` beans (`support/FakeAdbTestConfiguration.kt`).

**Package state model** (used everywhere): `PackageState` = ENABLED / DISABLED / UNINSTALLED (uninstalled for this
Android user, APK still on the phone); `null` means the package is not on the phone at all.
- `PackageStateReader` derives state from four `pm list packages` calls (`-u`, plain, `-d`, `-s -u`), filtered per `--user`.
- `ChangeType.of(before, after)` is the diff matrix; version-only changes count as UNCHANGED.
- `PackageCommands.step()` returns the commands for ONE state transition per SDK level (UAD-ng port, without `pm clear`).
  `PackageActionService` loops steps (max 3), re-reads the state after each and fails if it did not change; e.g.
  Restore of an app disabled before it was uninstalled = `install-existing` then `pm enable`.

**Failure detection** (`AdbErrors`): `failure()` is for mutating commands (old phones exit 0 on error, so output text
counts; the package name is stripped first). `queryFailure()` is for read-only queries and only looks at whole error
lines/exit code — package names like `com.foo.ErrorReporter` must not break snapshots. `isDeviceGone()` distinguishes
adb-side errors (`error: device offline`, `adb: device '…' not found`) from on-phone errors.

**App names/icons — the on-phone helper:** `android-helper/` is a separate Gradle subproject: plain Java compiled
`--release 8` against the `com.google.android:android:4.1.1.4` stub, converted to DEX by D8 (`dexJar` task) and copied
into resources as `helper/pua-helper.jar`. `AppMetadataCollector` pushes it to `/data/local/tmp`, runs it with
`CLASSPATH=… app_process /system/bin com.ilia.postupdateanalyzer.helper.Main --user N --icon-size 96`, and always deletes
it. It uses hidden APIs via reflection (`IPackageManager.getInstalledPackages` long/int flags overloads, `AssetManager.addAssetPath`
incl. split APKs). Output protocol, parsed by `HelperOutputParser` — keep both sides in sync:
`PUA-BEGIN|<n>`, one `PUA|<base64 UTF-8 JSON>` per package, `PUA-END`, or `PUA-FATAL|<base64 text>`.
Snapshots are still saved when the helper fails (`metadata_status` = PARTIAL/UNAVAILABLE).

**Device identity:** a phone is stored by `ro.serialno` (falls back to adb serial), so history survives USB ↔ Wi-Fi.
`DeviceService` caches getprop per `adbSerial|transport_id`; `DeviceLocks` allows one snapshot/action per phone at a time.

**Web layer:**
- Pages: `home`, `device`, `comparison`, `history`, `settings`, `error`. htmx responses are fragments that live in
  templates never rendered as pages: `comparison-row.html` (table row + `row-with-toast`) and `responses.html`.
  Do not put fragments after `</html>` in a page template — Thymeleaf renders them as part of the page.
- Toasts are out-of-band swaps (`fragments :: toast-oob`). Errors for htmx requests return a toast with
  `HX-Reswap: none` and a real 4xx/5xx status (`WebErrorHandler`, `AppErrorController`); `htmx-config` in
  `fragments :: head` makes htmx swap error responses.
- Thymeleaf runs unknown `th:hx-*` attributes in restricted mode: no `@bean` references there — compute via `th:with`.
- Comparison filtering/search is client-side (`static/js/app.js`); the server renders all rows, unchanged ones `hidden`.
- `LocalhostGuardFilter`: Host must be loopback; non-GET requests need `HX-Request: true` and a loopback Origin.
  MockMvc/curl POSTs must send `HX-Request: true`.

**Desktop launcher:** `main` → `DesktopLauncher` picks the data dir (`%LOCALAPPDATA%\PostUpdateAnalyzer`,
`~/.local/share/post-update-analyzer`), holds a single-instance file lock, picks port 18765 (or random) and sets
`app.data-dir`, `server.port`, `app.desktop.enabled`, `logging.file.name` as system properties before Spring starts.
`application.yaml` has no default for `app.data-dir` (tests set it with `@DynamicPropertySource`).
`DesktopIntegration` only exists when `app.desktop.enabled=true`. It opens an app window (`AppWindowLauncher` → `AppWindow`): a
Chromium-based browser found by `ChromiumLocator`, started with `--app=http://127.0.0.1:<port>/?window=app` and its own
`--user-data-dir` (`<dataDir>/window`, or `~/snap/<name>/common/…` for snap browsers), so that browser process ends
exactly when the user closes the window → the app quits. An exit within 5 s means a running copy took over (or it
failed) → Swing control window. No Chromium browser → default browser tab + Swing control window.
On Quit the app SIGTERMs the browser's main process — found via `/proc/<pid>/cmdline`, because Brave's launch script
stays as parent and Chromium rewrites its process title so `ProcessHandle.Info.arguments()` is empty.
`app.js` remembers `?window=app` in sessionStorage and sends external `https:` links to `POST /open-link`
(`ExternalBrowser` → default browser). `AppShutdown.quit()` calls `exitProcess` — tests must `@MockitoBean` it, and
tests that click external links must `@MockitoBean ExternalBrowser`.

**Database:** SQLite via `JdbcClient`, Flyway migrations in `src/main/resources/db/migration`. Never edit an applied
migration (Flyway checksums fail on existing user databases) — add `V2__…`. Timestamps are ISO-8601 TEXT; icons are
deduplicated in `app_icon` by SHA-256. Spring 7's `JdbcClient.params(vararg)` rejects nulls: pass `params(listOf(...))`.
Every `action_log` row belongs to the comparison it was run from (`before_snapshot_id`/`after_snapshot_id` NOT NULL,
`ON DELETE RESTRICT`); `SnapshotService.delete` refuses such snapshots with `SnapshotInUseException` (409) first.
`ActionController` takes the Android user from the comparison, not from the request.

## Packaging (`build.gradle.kts`)

- `jpackageLinux` + `packageLinux` (Linux host only): app-image with an explicit `runtimeModules` list (from `jdeps`).
  If a new library needs another JDK module, add it there — a missing module only fails at runtime in the bundle.
- `windowsExe` runs Launch4j 3.50 from Maven artifacts (core jar placed inside the unpacked linux64 toolchain, whose
  `bin/` needs the exec bit restored); `packageWindows` adds a pinned, SHA-256-checked Temurin JRE 25 as `runtime/`
  and asserts the zip layout the exe depends on (`app/post-update-analyzer.jar`, `runtime/bin/javaw.exe`, …).
- Kotlin DSL gotchas: `java.` resolves to the `java {}` extension, so import JDK classes; use `tasks.register<T>()` /
  `configurations.create()` (the `by registering` delegates are deprecated in Gradle 9.6).

## Tests

- `support/fake/FakeDevice.kt` mimics real `pm`/`cmd package` behavior (verified on the emulator), e.g.
  `pm disable-user` on an uninstalled app is remembered, uninstalling a user app for its only user deletes it.
  `FakeAdbExecutor` routes adb calls to the connected fake devices.
  Failure injection: `failures`, `ignored`, `helperWorks`, `helperOutputOverride`, `state` (on `FakeDevice`),
  `interceptors` (on `FakeAdbExecutor`).
- `IntegrationTest` clears all tables and the fake before each test; `TickingClock` advances 1 s per call.
- Playwright: `installPlaywrightChromium` installs only Chromium; with a dialog listener registered the listener must
  accept or dismiss every dialog or the page freezes.
- Emulator tests: `SharedEmulator` starts one `halimqarroum/docker-android:api-33` container per JVM (ready log line
  `ANDROID_READY`), `HostAdb` checks results with plain adb. They only touch `com.android.egg`,
  `com.android.wallpaper.livepicker`, `com.android.dreams.basic` and the test APK `com.ilia.pua.testtrash`
  (`src/test/resources/apks`, rebuilt by `scripts/build-test-apks.sh`), and pick the emulator by serial because the
  user's real phone may be connected too.
- Kotlin trap: `java.nio.file.Path` is `Iterable<Path>`, so `pathList += path` misresolves — use `add()`.

## Product rules (decided by the user)

- One click = one action on one app. No bulk actions, no "revert to before" button, no undo in History.
  App actions never ask for confirmation.
- The comparison hides unchanged apps by default; a "Show unchanged apps" checkbox reveals them.
- History groups actions by the comparison they were run from (`HistoryViews`). A snapshot that has actions cannot be
  deleted; its Delete button is disabled, with a tooltip.
- No APK backups. Restore works only while the APK is on the phone (system apps, disabled apps); a removed
  user-installed app is shown with its package name and "reinstall from the app store".
- End users must not need Java: `bootJar` must keep producing the Linux and Windows bundles.
