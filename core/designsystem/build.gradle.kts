plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.irohaco.core.designsystem"
    compileSdk = 37
    defaultConfig {
        minSdk = 33
    }
}
