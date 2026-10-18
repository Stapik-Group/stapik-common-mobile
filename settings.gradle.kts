pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Stapik Common Mobile"
include(":core:sync")
include(":core:document")
include(":core:crypto")
include(":core:cloud")
include(":core:settings")
include(":core:app")
include(":core:presentation")
include(":core:i18n")
include(":core:theme")
include(":ui:theme")
include(":sample-app")
include(":core:model")
