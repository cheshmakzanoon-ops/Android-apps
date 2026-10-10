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

rootProject.name = "android-apps"

// Only app modules that compile are listed. Other squads add their own
// `include(":apps:<slug>")` line through a reviewed root change (project outline §2).
include(":apps:timeflip")
