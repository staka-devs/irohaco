plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.irohaco.infrastructure.ai"
    compileSdk = 37
    defaultConfig {
        minSdk = 33
    }
}

dependencies {
    implementation(project(":core:contracts"))
    implementation(libs.litertlm.android)
    implementation(libs.mlkit.text.recognition.japanese)
    testImplementation(libs.junit)
    testImplementation(kotlin("test"))
}
