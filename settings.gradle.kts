pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Haven"

include(":app")

include(":core:ui")
include(":core:ssh")
include(":core:security")
include(":core:data")

include(":feature:connections")
include(":feature:terminal")
include(":feature:sftp")
include(":feature:keys")
include(":core:reticulum")

include(":feature:settings")
