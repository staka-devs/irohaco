plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.irohaco.infrastructure.preferences"
    compileSdk = 37
    defaultConfig {
        minSdk = 33
    }
}

dependencies {
    implementation(project(":core:contracts"))
    implementation(libs.androidx.datastore.preferences)

    testImplementation(libs.junit)
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}
