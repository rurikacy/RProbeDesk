pluginManagement {
    buildscript {
        repositories { google(); mavenCentral() }
        dependencies {
            // Read Kotlin 2.4 metadata while the Android app remains on AGP 8.
            classpath("com.android.tools:r8:9.1.57")
        }
    }
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        exclusiveContent {
            forRepository { maven("https://jitpack.io") }
            filter { includeGroupByRegex("com\\.github\\.MuntashirAkon(\\..*)?") }
        }
    }
}
rootProject.name = "RProbeDesk"
include(":app", ":catalog")
include(":qa:fixture")
