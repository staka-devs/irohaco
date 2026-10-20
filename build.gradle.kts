plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.roborazzi) apply false
    alias(libs.plugins.androidx.room) apply false
    alias(libs.plugins.ksp) apply false
}

// These modules stay pure Kotlin (CONTRIBUTING §3.1): declaring or pulling in any of these groups fails the build.
val pureKotlinModules = setOf(":core:domain", ":core:contracts")
val forbiddenGroups = listOf(
    "androidx",          // Android Framework, Room
    "com.android",
    "com.google.android",
    "com.google.ai.edge", // LiteRT-LM
    "com.squareup.okhttp3",
    "com.squareup.retrofit2",
    "io.ktor",
)
subprojects {
    if (path !in pureKotlinModules) return@subprojects
    val module = path
    fun checkPure(group: String?, name: String) {
        if (group != null && forbiddenGroups.any { group == it || group.startsWith("$it.") }) {
            throw GradleException("$module must stay pure Kotlin: $group:$name is not allowed")
        }
    }
    configurations.configureEach {
        dependencies.configureEach { checkPure(group, name) }
        resolutionStrategy.eachDependency { checkPure(requested.group, requested.name) }
    }
}

// Robolectric sets up SDK 36+ apps through JDK internals that newer JDKs hide.
subprojects {
    tasks.withType<Test>().configureEach {
        jvmArgs("--add-opens=java.base/java.io=ALL-UNNAMED", "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
    }
}
