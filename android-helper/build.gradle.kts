// Tiny Java program that runs ON THE PHONE through `app_process` (like scrcpy does).
// It reads app labels, icons and versions via hidden PackageManager APIs and prints them as JSON lines.
// It is compiled against an old public android.jar stub and converted to DEX with D8.

plugins {
    java
}

repositories {
    mavenCentral()
    google()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

val d8 = configurations.create("d8")

dependencies {
    compileOnly("com.google.android:android:4.1.1.4")
    d8("com.android.tools:r8:9.5.23")
}

tasks.withType<JavaCompile>().configureEach {
    // Android runtimes understand Java 8 bytecode after desugaring by D8.
    options.release = 8
    options.compilerArgs.addAll(listOf("-Xlint:-options"))
}

val androidStubJar = configurations.compileClasspath.map { cp ->
    cp.files.single { it.name.startsWith("android-") }
}

val dexJar = tasks.register<JavaExec>("dexJar") {
    description = "Converts the helper classes to an Android DEX jar runnable with app_process."
    val classesDir = tasks.compileJava.flatMap { it.destinationDirectory }
    val output = layout.buildDirectory.file("dex/pua-helper.jar")
    inputs.dir(classesDir)
    inputs.files(androidStubJar)
    outputs.file(output)

    classpath = d8
    mainClass = "com.android.tools.r8.D8"
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(25) }
    argumentProviders.add(CommandLineArgumentProvider {
        val classFiles = classesDir.get().asFileTree.matching { include("**/*.class") }.files.map { it.absolutePath }.sorted()
        listOf(
            "--release",
            "--min-api", "21",
            "--lib", androidStubJar.get().absolutePath,
            "--output", output.get().asFile.absolutePath,
        ) + classFiles
    })
    doFirst { output.get().asFile.parentFile.mkdirs() }
}

val helperDex = configurations.create("helperDex") {
    isCanBeConsumed = true
    isCanBeResolved = false
}

artifacts {
    add(helperDex.name, dexJar.map { it.outputs.files.singleFile }) {
        builtBy(dexJar)
    }
}
