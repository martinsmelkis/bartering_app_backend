pluginManagement {
    repositories {
        mavenCentral()
        google()
        mavenLocal()
        gradlePluginPortal()
        maven { url = uri("https://kotlin.bintray.com/ktor") }
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        google()
        mavenLocal()
        gradlePluginPortal()
        maven { url = uri("https://kotlin.bintray.com/ktor") }
    }
}

rootProject.name = "BarterAppBackend"
 