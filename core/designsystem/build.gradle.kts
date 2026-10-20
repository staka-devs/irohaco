@file:OptIn(ExperimentalRoborazziApi::class)

import com.github.takahirom.roborazzi.ExperimentalRoborazziApi

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "dev.irohaco.core.designsystem"
    compileSdk = 37
    defaultConfig {
        minSdk = 33
    }
    buildFeatures {
        compose = true
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all { it.systemProperties["robolectric.pixelCopyRenderMode"] = "hardware" }
        }
    }
}

// DESIGN.md §8: every @IrohacoPreviews preview becomes a screenshot test (verifyRoborazziDebug in CI).
roborazzi {
    // Reference images are committed; build/ is ignored.
    outputDir.set(file("src/test/screenshots"))
    generateComposePreviewRobolectricTests {
        enable = true
        packages = listOf("dev.irohaco.core.designsystem")
        includePrivatePreviews = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.coil.compose)
    implementation(libs.material.kolor)

    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.roborazzi.compose.preview.scanner.support)
    testImplementation(libs.composable.preview.scanner)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
