@file:OptIn(ExperimentalRoborazziApi::class)

import com.github.takahirom.roborazzi.ExperimentalRoborazziApi

import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "dev.irohaco"
    compileSdk = 37
    defaultConfig {
        applicationId = "dev.irohaco"
        minSdk = 33
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // ADR-0011: Google Maps needs the builder's own key from local.properties; without one it cannot be chosen.
        val mapsKey = rootProject.file("local.properties").takeIf { it.exists() }
            ?.let { file -> Properties().apply { file.inputStream().use { load(it) } }.getProperty("MAPS_API_KEY") }.orEmpty()
        manifestPlaceholders["MAPS_API_KEY"] = mapsKey
        buildConfigField("boolean", "GOOGLE_MAPS", (mapsKey.isNotEmpty()).toString())
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all { it.systemProperties["robolectric.pixelCopyRenderMode"] = "hardware" }
        }
        // ARCHITECTURE §13.0 L2: the minimum and the latest API on emulators (Gradle Managed Devices).
        // "Medium Phone" is the generic phone profile; the ci group runs as ciGroupDebugAndroidTest.
        managedDevices {
            localDevices {
                create("api33") {
                    device = "Medium Phone"
                    apiLevel = 33
                    systemImageSource = "aosp-atd"
                }
                create("api37") {
                    device = "Medium Phone"
                    apiLevel = 37
                    systemImageSource = "google"
                }
            }
            groups {
                create("ci") {
                    targetDevices.add(localDevices["api33"])
                    targetDevices.add(localDevices["api37"])
                }
            }
        }
    }
}

// DESIGN.md §8: main screens get screenshot tests from their previews, like core:designsystem.
roborazzi {
    outputDir.set(file("src/test/screenshots"))
    generateComposePreviewRobolectricTests {
        enable = true
        packages = listOf("dev.irohaco.ui")
        includePrivatePreviews = true
    }
}

dependencies {
    implementation(project(":core:application"))
    implementation(project(":core:database"))
    implementation(project(":core:designsystem"))
    implementation(project(":infrastructure:android-media"))
    implementation(project(":feature:timeline"))
    implementation(project(":feature:settings"))
    implementation(project(":infrastructure:preferences"))
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.coil.compose)
    implementation(libs.coil.video)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.navigation.suite)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.activity.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.test.espresso.core)
    testImplementation(libs.roborazzi.compose.preview.scanner.support)
    testImplementation(libs.composable.preview.scanner)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
