pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Irohaco"

include(":app")
include(":core:domain")
include(":core:contracts")
include(":core:application")
include(":core:database")
include(":core:designsystem")
include(":feature:timeline")
include(":infrastructure:android-media")
include(":infrastructure:preferences")
include(":infrastructure:local-ai")
include(":feature:settings")
