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

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(kotlin("test"))
}
