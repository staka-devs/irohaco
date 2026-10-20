plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Built on JDK 21, but emits Java 17 bytecode for the Android modules that will consume it.
java {
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}

dependencies {
    testImplementation(kotlin("test"))
}
