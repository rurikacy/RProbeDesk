pluginManagement {
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
