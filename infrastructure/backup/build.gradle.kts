plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.irohaco.infrastructure.backup"
    compileSdk = 37
    defaultConfig {
        minSdk = 33
    }
}

dependencies {
    implementation(project(":core:contracts"))
    implementation(libs.bouncycastle.bcprov) // Argon2id; AES-GCM is the platform's (ADR-0010)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric) // org.json is the platform's; Robolectric gives the tests a real one
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}
