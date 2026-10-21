@file:OptIn(ExperimentalRoborazziApi::class)

import com.github.takahirom.roborazzi.ExperimentalRoborazziApi

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "dev.irohaco.feature.settings"
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

// DESIGN.md §8: screen previews become screenshot tests.
roborazzi {
    outputDir.set(file("src/test/screenshots"))
    generateComposePreviewRobolectricTests {
        enable = true
        packages = listOf("dev.irohaco.feature.settings")
        includePrivatePreviews = true
    }
}

dependencies {
    implementation(project(":core:application"))
    implementation(project(":core:designsystem"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.adaptive.layout)
    implementation(libs.androidx.compose.material3.adaptive.navigation)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.junit)
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.test.espresso.core)
    testImplementation(libs.roborazzi.compose.preview.scanner.support)
    testImplementation(libs.composable.preview.scanner)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
