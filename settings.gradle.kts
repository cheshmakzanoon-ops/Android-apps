pluginManagement {
    repositories {
        // Portal first: Kotlin plugins resolve there without touching Google Maven.
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        google()
    }
}

rootProject.name = "android-apps"

// Only StickerJet is bootstrapped. The other nine squads get their modules when their own
// G1 gate is accepted (see project-outline-android-apps.md §2 and §11).
//
// Android modules need the Android Gradle Plugin and AndroidX, which resolve from Google Maven.
// A machine without that access can still build and test the pure JVM core with
// `./gradlew -Pstickerjet.skipAndroid=true :apps:stickerjet-core:test`. CI never sets the flag.
if (providers.gradleProperty("stickerjet.skipAndroid").getOrElse("false") != "true") {
    include(":apps:stickerjet")
}
// The core is a sibling project whose directory lives under apps/stickerjet/. Mapping it to its own
// path keeps Gradle from creating an implicit :apps:stickerjet parent that would evaluate the
// Android build script on machines where that script cannot be configured.
include(":apps:stickerjet-core")
project(":apps:stickerjet-core").projectDir = file("apps/stickerjet/core")
