val nativeRoot = rootProject.file("native")
val nativeSources = fileTree(nativeRoot) { include("Cargo.*", "core/Cargo.toml", "core/src/**") }
val desktopResources = layout.buildDirectory.dir("generated/desktopResources")
val androidLibraries = layout.buildDirectory.dir("generated/coreJniLibs")
val cargoTarget = layout.buildDirectory.dir("cargo")

fun cargoBuild(task: String, target: String?, library: String, destination: Provider<Directory>,
               ndkDriver: String? = null) = tasks.register<Exec>(task) {
    inputs.files(nativeSources)
    inputs.property("rustTarget", target ?: "host")
    if (target == null) {
        inputs.property("releaseStrip", "symbols")
        environment("CARGO_PROFILE_RELEASE_STRIP", "symbols")
    }
    inputs.property("rustCompiler", providers.exec { commandLine("rustc", "-vV") }.standardOutput.asText)
    if (ndkDriver != null) {
        val ndk = providers.environmentVariable("ANDROID_NDK_HOME")
        inputs.property("ndkPath", ndk)
        inputs.file(ndk.map { file("$it/source.properties") })
    }
    outputs.file(destination.map { it.file(library) })
    workingDir(nativeRoot)
    environment("CARGO_TARGET_DIR", cargoTarget.get().asFile)
    val args = mutableListOf("cargo", "build", "--locked", "--release")
    if (target != null) args.addAll(listOf("--target", target))
    commandLine(args)
    doFirst {
        if (ndkDriver != null && target != null) {
            val ndk = providers.environmentVariable("ANDROID_NDK_HOME").orNull
                ?: error("Set ANDROID_NDK_HOME to the Android NDK r27c (or compatible) directory")
            val host = System.getProperty("os.name")
            val tag = when {
                host.startsWith("Windows") -> "windows-x86_64"
                host.startsWith("Mac") -> "darwin-x86_64"
                else -> "linux-x86_64"
            }
            val suffix = if (host.startsWith("Windows")) ".cmd" else ""
            val driver = file("$ndk/toolchains/llvm/prebuilt/$tag/bin/${ndkDriver}30-clang$suffix")
            require(driver.isFile) { "NDK compiler missing: $driver" }
            environment("CARGO_TARGET_${target.uppercase().replace('-', '_')}_LINKER", driver)
            // Required for Android devices using 16 KiB pages.
            environment("CARGO_TARGET_${target.uppercase().replace('-', '_')}_RUSTFLAGS",
                "-C link-arg=-Wl,-z,max-page-size=16384")
        }
    }
    doLast {
        val out = cargoTarget.get().asFile.resolve(target ?: "").resolve("release/$library")
        val dir = destination.get().asFile
        dir.mkdirs()
        out.copyTo(dir.resolve(library), overwrite = true)
    }
}

val desktopCore = cargoBuild("buildWindowsCore", null, "rprobe_core.dll",
    desktopResources.map { it.dir("windows-x64/native") })
val abiBuilds = listOf(
    Triple("arm64-v8a", "aarch64-linux-android", "aarch64-linux-android"),
    Triple("armeabi-v7a", "armv7-linux-androideabi", "armv7a-linux-androideabi"),
    Triple("x86", "i686-linux-android", "i686-linux-android"),
    Triple("x86_64", "x86_64-linux-android", "x86_64-linux-android")
).filter { providers.gradleProperty("rprobeAbi").orNull.let { selected -> selected == null || selected == it.first } }
.map { (abi, target, driver) ->
    cargoBuild("buildAndroidCore" + abi.replace("-", "").replace("_", ""), target, "librprobe_core.so",
        androidLibraries.map { it.dir(abi) }, driver)
}
tasks.matching { it.name.matches(Regex("merge.*JniLibFolders")) }.configureEach { dependsOn(abiBuilds) }

val windowsTools = tasks.register<Exec>("stageWindowsTools") {
    val script = project.file("packaging/windows/stage-tools.ps1")
    val dest = desktopResources.get().asFile.resolve("windows-x64/tools")
    val cache = layout.buildDirectory.get().asFile.resolve("windowsToolDownloads")
    inputs.file(script)
    inputs.dir(project.file("packaging/windows/licenses"))
    outputs.dir(dest)
    commandLine("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", script, cache, dest)
}
tasks.matching { it.name == "prepareAppResources" }.configureEach { dependsOn(desktopCore, windowsTools) }
tasks.named<Test>("desktopTest") {
    dependsOn(desktopCore, windowsTools)
    systemProperty("compose.application.resources.dir", desktopResources.get().asFile.resolve("windows-x64"))
}
