plugins { id("com.android.application") }

android {
    namespace = "dev.rprobedesk.fixture"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.rprobedesk.fixture"
        minSdk = 30
        targetSdk = 35
        versionCode = providers.gradleProperty("fixtureVersion").orNull?.toInt() ?: 1
        versionName = "1.$versionCode"
    }
}
