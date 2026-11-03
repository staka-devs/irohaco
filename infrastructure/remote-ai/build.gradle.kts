plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.irohaco.infrastructure.remote"
    compileSdk = 37
    defaultConfig {
        minSdk = 33
    }
}

dependencies {
    implementation(project(":core:contracts"))
    implementation(project(":core:application")) // remoteFailure sorts the error responses

    testImplementation(libs.junit)
    testImplementation(libs.robolectric) // org.json is the platform's; Robolectric gives the tests a real one
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}
