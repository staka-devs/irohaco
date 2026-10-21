plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.irohaco.infrastructure.media"
    compileSdk = 37
    defaultConfig {
        minSdk = 33
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(project(":core:contracts"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.core.ktx) // FileProvider, for the share cache's content:// URIs (MET-003)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(kotlin("test"))
}
