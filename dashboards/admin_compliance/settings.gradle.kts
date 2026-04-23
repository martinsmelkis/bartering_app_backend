pluginManagement {
    repositories {
        mavenCentral()
        google()
        mavenLocal()
        gradlePluginPortal()
    }
    plugins {
        kotlin("jvm") version "2.2.0"
        kotlin("plugin.serialization") version "2.2.0"
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        mavenCentral()
        google()
        mavenLocal()
        gradlePluginPortal()
    }
}

rootProject.name = "admin_compliance"
