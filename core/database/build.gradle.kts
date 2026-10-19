plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.androidx.room)
}

android {
    namespace = "dev.irohaco.core.database"
    compileSdk = 37
    defaultConfig {
        minSdk = 33
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

// The app creates its database from the reference DDL itself, so ship database/schema.sql as an asset.
abstract class CopyReferenceSchema : DefaultTask() {
    @get:InputFile abstract val schema: RegularFileProperty

    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        schema.get().asFile.copyTo(outputDir.get().file("schema.sql").asFile, overwrite = true)
    }
}

val copyReferenceSchema = tasks.register<CopyReferenceSchema>("copyReferenceSchema") {
    schema.set(rootProject.layout.projectDirectory.file("database/schema.sql"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(copyReferenceSchema, CopyReferenceSchema::outputDir)
    }
}

dependencies {
    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(kotlin("test"))
}
