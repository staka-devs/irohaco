plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

// Domain stays pure Kotlin (CONTRIBUTING §3.1): declaring or pulling in any of these groups fails the build.
val forbiddenGroups = listOf(
    "androidx",          // Android Framework, Room
    "com.android",
    "com.google.android",
    "com.google.ai.edge", // LiteRT-LM
    "com.squareup.okhttp3",
    "com.squareup.retrofit2",
    "io.ktor",
)
fun checkPure(group: String?, name: String) {
    if (group != null && forbiddenGroups.any { group == it || group.startsWith("$it.") }) {
        throw GradleException("core:domain must stay pure Kotlin: $group:$name is not allowed")
    }
}
configurations.configureEach {
    dependencies.configureEach { checkPure(group, name) }
    resolutionStrategy.eachDependency { checkPure(requested.group, requested.name) }
}
