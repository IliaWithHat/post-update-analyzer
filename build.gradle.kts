import de.undercouch.gradle.tasks.download.Download
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipFile

plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.spring") version "2.3.21"
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"
    id("de.undercouch.download") version "5.7.0"
}

group = "com.ilia"
version = "0.1"
description = "post-update-analyzer"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
    }
}

repositories {
    mavenCentral()
}

val helperDex = configurations.create("helperDex") {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("tools.jackson.module:jackson-module-kotlin")
    implementation("org.webjars:webjars-locator-lite")
    implementation("org.webjars.npm:htmx.org:2.0.11")
    implementation("org.webjars.npm:picocss__pico:2.1.1")
    runtimeOnly("org.xerial:sqlite-jdbc")

    helperDex(project(path = ":android-helper", configuration = "helperDex"))

    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-jdbc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-thymeleaf-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("io.mockk:mockk:1.14.11")
    testImplementation("org.jsoup:jsoup:1.23.2")
    testImplementation("org.testcontainers:testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("com.microsoft.playwright:playwright:1.63.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

springBoot {
    buildInfo()
}

tasks.processResources {
    // The DEX helper that is pushed to the phone to read app labels and icons.
    from(helperDex) {
        into("helper")
        rename { "pua-helper.jar" }
    }
}

tasks.bootJar {
    archiveFileName = "post-update-analyzer.jar"
    doLast {
        archiveFile.get().asFile.setExecutable(true, false)
    }
    // Users should not need Java: bootJar also builds the double-clickable bundles with Java 25 inside.
    if (!project.hasProperty("skipBundles")) {
        finalizedBy("packageApp")
    }
}

tasks.jar {
    enabled = false
}

// ---------------------------------------------------------------------------------------------------------------------
// Tests
//   test          - fast unit/integration tests, no external tools needed
//   uiTest        - real browser (Playwright) against the app with a simulated phone
//   emulatorTest  - real Android emulator in Docker (needs Docker, /dev/kvm and adb)
// ---------------------------------------------------------------------------------------------------------------------

tasks.test {
    useJUnitPlatform {
        excludeTags("ui", "emulator")
    }
}

// Playwright would download Chromium, Firefox and WebKit; the tests only need Chromium.
val installPlaywrightChromium = tasks.register<JavaExec>("installPlaywrightChromium") {
    description = "Downloads the Chromium build used by the browser tests (cached in ~/.cache/ms-playwright)."
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "com.microsoft.playwright.CLI"
    args("install", "chromium")
}

tasks.withType<Test>().configureEach {
    if (name != "test") {
        dependsOn(installPlaywrightChromium)
        environment("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")
    }
}

val uiTest = tasks.register<Test>("uiTest") {
    description = "Browser tests (Playwright) against the app with a simulated phone."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("ui")
    }
    shouldRunAfter(tasks.test)
}

val emulatorTest = tasks.register<Test>("emulatorTest") {
    description = "End-to-end tests against a real Android emulator running in Docker."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("emulator")
    }
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
    }
    shouldRunAfter(tasks.test, uiTest)
}

tasks.check {
    dependsOn(uiTest)
}

tasks.withType<Test>().configureEach {
    testLogging {
        events("skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Bundles with Java included, so users only double-click (`./gradlew bootJar`, skip with -PskipBundles):
//   build/dist/PostUpdateAnalyzer-linux-x64.tar.gz   jpackage app-image (native launcher + Java runtime), built on Linux
//   build/dist/PostUpdateAnalyzer-windows-x64.zip    PostUpdateAnalyzer.exe (Launch4j) + app jar + Temurin JRE 25
// ---------------------------------------------------------------------------------------------------------------------

val appName = "PostUpdateAnalyzer"
val distDir = layout.buildDirectory.dir("dist")
val isLinuxHost = System.getProperty("os.name").lowercase().contains("linux")
val jdk25 = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(25) }
val bundleJavaOptions = listOf("-XX:TieredStopAtLevel=1", "-Xss1m")

// From `jdeps --print-module-deps` on the boot jar, plus modules libraries load reflectively
// (JNDI for Tomcat, java.sql for SQLite/Flyway, zipfs, extra charsets, JMX extensions).
val runtimeModules = listOf(
    "java.base", "java.compiler", "java.desktop", "java.instrument", "java.logging", "java.management",
    "java.naming", "java.net.http", "java.prefs", "java.scripting", "java.security.jgss", "java.sql",
    "java.sql.rowset", "java.xml", "jdk.charsets", "jdk.jfr", "jdk.management", "jdk.unsupported", "jdk.zipfs",
)

val jpackageLinux = tasks.register<Exec>("jpackageLinux") {
    description = "Builds the Linux app-image (native launcher + Java runtime) with jpackage."
    onlyIf("jpackage builds for the OS it runs on, so the Linux bundle needs a Linux host") { isLinuxHost }
    dependsOn(tasks.bootJar)
    val input = layout.buildDirectory.dir("jpackage/input")
    val output = layout.buildDirectory.dir("jpackage/linux")
    inputs.file(tasks.bootJar.flatMap { it.archiveFile })
    inputs.file("packaging/icons/app-icon.png")
    inputs.file("packaging/linux/README.txt")
    outputs.dir(output)
    doFirst {
        delete(input, output)
        copy {
            from(tasks.bootJar.flatMap { it.archiveFile })
            into(input)
        }
        executable = jdk25.get().metadata.installationPath.file("bin/jpackage").asFile.absolutePath
        args(
            "--type", "app-image",
            "--name", appName,
            "--app-version", project.version.toString().substringBefore('-'),
            "--description", "Finds apps a firmware update reinstalled or re-enabled and removes them again",
            "--vendor", "Post-Update Analyzer",
            "--icon", file("packaging/icons/app-icon.png").absolutePath,
            "--input", input.get().asFile.absolutePath,
            "--main-jar", "post-update-analyzer.jar",
            "--dest", output.get().asFile.absolutePath,
            "--add-modules", runtimeModules.joinToString(","),
            "--jlink-options", "--strip-debug --no-man-pages --no-header-files",
        )
        bundleJavaOptions.forEach { args("--java-options", it) }
    }
    doLast {
        val root = output.get().dir(appName).asFile
        // Double-clickable launcher right in the unpacked folder.
        Files.createSymbolicLink(root.resolve(appName).toPath(), Path.of("bin", appName))
        copy {
            from("packaging/linux/README.txt")
            into(root)
        }
    }
}

val packageLinux = tasks.register<Exec>("packageLinux") {
    description = "Packs the Linux app-image into a tar.gz (keeps executable bits and the top-level shortcut)."
    onlyIf { isLinuxHost }
    dependsOn(jpackageLinux)
    val image = layout.buildDirectory.dir("jpackage/linux")
    val archive = distDir.map { it.file("$appName-linux-x64.tar.gz") }
    // Without declared inputs Gradle would keep an old archive after the jar changed.
    inputs.dir(image)
    outputs.file(archive)
    doFirst {
        archive.get().asFile.parentFile.mkdirs()
        commandLine("tar", "-czf", archive.get().asFile.absolutePath, "-C", image.get().asFile.absolutePath, appName)
    }
}

// --- Windows: Temurin JRE + Launch4j, both cross-built from any OS -----------------------------------------------------

val temurinWindowsJre = mapOf(
    "url" to "https://github.com/adoptium/temurin25-binaries/releases/download/jdk-25.0.4.1%2B1/OpenJDK25U-jre_x64_windows_hotspot_25.0.4.1_1.zip",
    "sha256" to "4c95451cea98556def2c54f7782933f52a26d4a36bd85e1d59f0364464828b07",
    "file" to "OpenJDK25U-jre_x64_windows_hotspot_25.0.4.1_1.zip",
)
val downloadCache = gradle.gradleUserHomeDir.resolve("caches/post-update-analyzer")

val downloadWindowsJre = tasks.register<Download>("downloadWindowsJre") {
    description = "Downloads the Temurin Java 25 runtime for Windows (cached in the Gradle user home)."
    src(temurinWindowsJre.getValue("url"))
    dest(downloadCache.resolve(temurinWindowsJre.getValue("file")))
    overwrite(false)
    doLast {
        val actual = MessageDigest.getInstance("SHA-256")
            .digest(dest.readBytes())
            .joinToString("") { "%02x".format(it) }
        if (actual != temurinWindowsJre.getValue("sha256")) {
            dest.delete()
            throw GradleException("Checksum mismatch for ${dest.name}: $actual")
        }
    }
}

val launch4j = configurations.create("launch4j") { isTransitive = false }
val launch4jTools = configurations.create("launch4jTools") { isTransitive = false }
val launch4jLibs = configurations.create("launch4jLibs")

dependencies {
    launch4j("net.sf.launch4j:launch4j:3.50:core")
    launch4jTools("net.sf.launch4j:launch4j:3.50:workdir-linux64")
    launch4jLibs("com.thoughtworks.xstream:xstream:1.4.21")
    launch4jLibs("commons-beanutils:commons-beanutils:1.11.0")
    launch4jLibs("commons-logging:commons-logging:1.3.5")
}

val prepareLaunch4j = tasks.register<Sync>("prepareLaunch4j") {
    description = "Unpacks Launch4j with its Linux toolchain (windres, ld) to build the Windows .exe."
    onlyIf { isLinuxHost }
    from(launch4jTools.elements.map { files -> files.map { zipTree(it) } }) {
        exclude("META-INF/**")
        eachFile {
            path = path.substringAfter('/')
            if (path.startsWith("bin/")) permissions { unix("rwxr-xr-x") }
        }
        includeEmptyDirs = false
    }
    // Launch4j looks for bin/, head/ and w32api/ next to its own jar.
    from(launch4j) { rename { "launch4j.jar" } }
    into(layout.buildDirectory.dir("launch4j/tool"))
}

val windowsExe = tasks.register<JavaExec>("windowsExe") {
    description = "Creates PostUpdateAnalyzer.exe, which starts app/post-update-analyzer.jar with the bundled runtime/."
    onlyIf("the bundled Launch4j toolchain is for Linux hosts") { isLinuxHost }
    dependsOn(prepareLaunch4j)
    val tool = layout.buildDirectory.dir("launch4j/tool")
    val config = layout.buildDirectory.file("launch4j/config.xml")
    val exe = layout.buildDirectory.file("launch4j/$appName.exe")
    inputs.file("packaging/icons/app-icon.ico")
    inputs.file("packaging/windows/app.manifest")
    outputs.file(exe)
    classpath(tool.map { it.file("launch4j.jar") }, launch4jLibs)
    mainClass = "net.sf.launch4j.Main"
    javaLauncher = jdk25
    jvmArgs("--sun-misc-unsafe-memory-access=allow") // xstream inside Launch4j still uses sun.misc.Unsafe
    args(config.get().asFile.absolutePath)
    doFirst {
        val numericVersion = project.version.toString().substringBefore('-').split('.').let { (it + listOf("0", "0", "0", "0")).take(4) }.joinToString(".")
        config.get().asFile.writeText(
            """
            <launch4jConfig>
              <dontWrapJar>true</dontWrapJar>
              <headerType>gui</headerType>
              <jar>app\post-update-analyzer.jar</jar>
              <outfile>${exe.get().asFile.absolutePath}</outfile>
              <errTitle>Post-Update Analyzer</errTitle>
              <chdir>.</chdir>
              <priority>normal</priority>
              <stayAlive>false</stayAlive>
              <restartOnCrash>false</restartOnCrash>
              <icon>${file("packaging/icons/app-icon.ico").absolutePath}</icon>
              <manifest>${file("packaging/windows/app.manifest").absolutePath}</manifest>
              <jre>
                <path>runtime</path>
                <requiresJdk>false</requiresJdk>
                <requires64Bit>true</requires64Bit>
                ${bundleJavaOptions.joinToString("\n") { "<opt>$it</opt>" }}
              </jre>
              <versionInfo>
                <fileVersion>$numericVersion</fileVersion>
                <txtFileVersion>${project.version}</txtFileVersion>
                <fileDescription>Post-Update Analyzer</fileDescription>
                <copyright>Post-Update Analyzer</copyright>
                <productVersion>$numericVersion</productVersion>
                <txtProductVersion>${project.version}</txtProductVersion>
                <productName>Post-Update Analyzer</productName>
                <internalName>$appName</internalName>
                <originalFilename>$appName.exe</originalFilename>
              </versionInfo>
            </launch4jConfig>
            """.trimIndent(),
        )
    }
}

val packageWindows = tasks.register<Zip>("packageWindows") {
    description = "Builds the portable Windows bundle: exe + app jar + Java runtime."
    onlyIf { isLinuxHost }
    dependsOn(windowsExe, downloadWindowsJre)
    archiveFileName = "$appName-windows-x64.zip"
    destinationDirectory = distDir
    into(appName) {
        from(windowsExe)
        from("packaging/windows/README.txt")
        into("app") { from(tasks.bootJar) }
        into("runtime") {
            from(downloadWindowsJre.map { zipTree(it.dest) }) {
                // the archive has a single top folder (jdk-25...-jre); its content becomes runtime/
                eachFile {
                    relativePath = RelativePath(true, *relativePath.segments.filterNot { it.startsWith("jdk-") }.toTypedArray())
                }
                includeEmptyDirs = false
            }
        }
    }
    doLast {
        // The exe finds Java and the app only by these relative paths.
        val entries = ZipFile(archiveFile.get().asFile).use { zip -> zip.entries().asSequence().map { it.name }.toSet() }
        val required = listOf("$appName.exe", "app/post-update-analyzer.jar", "runtime/bin/javaw.exe", "runtime/bin/server/jvm.dll", "runtime/lib/modules")
        val missing = required.map { "$appName/$it" }.filterNot { it in entries }
        if (missing.isNotEmpty()) throw GradleException("Windows bundle is incomplete, missing: $missing")
    }
}

val packageApp = tasks.register("packageApp") {
    description = "Builds the double-clickable bundles for Linux and Windows into build/dist."
    group = "distribution"
    dependsOn(packageLinux, packageWindows)
    doLast {
        distDir.get().asFile.listFiles()?.sortedBy { it.name }?.forEach {
            logger.lifecycle("Bundle: ${it.absolutePath} (${it.length() / 1_000_000} MB)")
        }
    }
}
