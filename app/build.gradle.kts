import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("multiplatform")
    id("com.android.application")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

val catalogResources = tasks.register<Copy>("stageCatalog") {
    dependsOn(":catalog:assembleRelease")
    from(project(":catalog").layout.buildDirectory.file("outputs/apk/release/catalog-release-unsigned.apk"))
    rename { "rprobe-catalog.jar" }
    into(layout.buildDirectory.dir("generated/catalogResources"))
}

tasks.named("preBuild") { dependsOn(catalogResources) }

kotlin {
    jvmToolchain(17)
    androidTarget()
    jvm("desktop")
    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
        }
        androidMain {
            kotlin.srcDir("src/shared/kotlin")
            dependencies {
                implementation("androidx.activity:activity-compose:1.10.1")
                implementation("com.github.MuntashirAkon:libadb-android:3.1.1")
                implementation("org.conscrypt:conscrypt-android:2.5.3")
            }
        }
        named("desktopMain") {
            resources.srcDir(catalogResources)
            kotlin.srcDir("src/shared/kotlin")
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation("org.lwjgl:lwjgl-nfd:3.3.6")
                runtimeOnly("org.lwjgl:lwjgl:3.3.6:natives-windows")
                runtimeOnly("org.lwjgl:lwjgl-nfd:3.3.6:natives-windows")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
            }
        }
        named("desktopTest") {
            dependencies {
                implementation(kotlin("test-junit"))
                implementation(compose.desktop.uiTestJUnit4)
            }
        }
        androidInstrumentedTest.dependencies {
            implementation("androidx.test:runner:1.6.2")
            implementation("androidx.test.ext:junit:1.2.2")
        }
    }
}

compose.resources {
    packageOfResClass = "dev.rprobedesk.resources"
}

tasks.named<Test>("desktopTest") {
    val livePort = providers.environmentVariable("RPROBE_QA_PORT")
    val pairFile = providers.environmentVariable("RPROBE_QA_PAIR_FILE")
    inputs.property("testDevicePort", livePort.orElse(""))
    val liveRun = livePort.isPresent || pairFile.isPresent || providers.environmentVariable("RPROBE_QA_SWITCH_A").isPresent
    outputs.upToDateWhen { !liveRun }
    outputs.doNotCacheIf("Live device results belong to this run") { liveRun }
}

val stageTestFixture by tasks.registering(Copy::class) {
    dependsOn(":qa:fixture:assembleDebug")
    from(project(":qa:fixture").layout.buildDirectory.file("outputs/apk/debug/fixture-debug.apk"))
    into(layout.buildDirectory.dir("generated/qaAssets"))
}
tasks.matching { it.name in setOf("mergeDebugAndroidTestAssets", "generateDebugAndroidTestLintModel", "lintAnalyzeDebugAndroidTest") }
    .configureEach { dependsOn(stageTestFixture) }

android {
    namespace = "dev.rprobedesk"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.rprobedesk"
        minSdk = 30
        targetSdk = 35
        versionCode = 7
        versionName = "1.2.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    sourceSets["main"].manifest.srcFile("src/androidMain/AndroidManifest.xml")
    sourceSets["main"].resources.srcDir(catalogResources)
    sourceSets["main"].assets.srcDir("src/androidMain/assets")
    sourceSets["main"].res.srcDir("src/androidMain/res")
    sourceSets["androidTest"].assets.srcDir(layout.buildDirectory.dir("generated/qaAssets"))
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    if (System.getenv("RPROBE_KEYSTORE") != null) {
        val releaseKey = signingConfigs.create("distribution") {
            storeFile = file(System.getenv("RPROBE_KEYSTORE"))
            storePassword = System.getenv("RPROBE_STORE_PASSWORD")
            keyAlias = "rprobedesk"
            keyPassword = storePassword
        }
        buildTypes.getByName("release").signingConfig = releaseKey
    }
}

compose.desktop.application {
    mainClass = "dev.rprobedesk.DesktopMainKt"
    nativeDistributions {
        targetFormats(TargetFormat.Msi)
        packageName = "RProbeDesk"
        packageVersion = "1.2.2"
        vendor = "RProbeDesk"
        modules("jdk.unsupported","java.desktop", "jdk.crypto.ec", "java.management", "jdk.accessibility")
        windows {
            iconFile.set(project.file("src/desktopMain/resources/rprobedesk.ico"))
            menu = true
            shortcut = true
            perUserInstall = true
            upgradeUuid = "8ef5299a-7f60-4aae-9acb-bd7a1b9d1cc0"
        }
    }
}
