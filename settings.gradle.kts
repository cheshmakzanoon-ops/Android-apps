// Google Maven is registered only when the Android module is part of the build (-Pphotoduel.skipAndroid
// is not "true"). A skipped JVM-only build then never contacts Google hosts, which some egress policies
// deny with 403 and which Gradle treats as a hard failure rather than a missing artifact.
pluginManagement {
    repositories {
        // Portal first: Kotlin and most build plugins resolve there.
        gradlePluginPortal()
        mavenCentral()
        if (providers.gradleProperty("photoduel.skipAndroid").getOrElse("false") != "true") google()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        if (providers.gradleProperty("photoduel.skipAndroid").getOrElse("false") != "true") google()
    }
}

rootProject.name = "android-apps"

// Only PhotoDuel is bootstrapped on this branch. Other squads add their own modules through the
// steward-approved root change described in project-outline-android-apps.md section 3.1.
//
// The Android module needs the Android Gradle Plugin and AndroidX from Google Maven. A machine
// without that access runs the pure JVM core instead:
//     ./gradlew -Pphotoduel.skipAndroid=true :apps:photoduel-core:test
// CI never sets the flag.
if (providers.gradleProperty("photoduel.skipAndroid").getOrElse("false") != "true") {
    include(":apps:photoduel")
}

// The core is a sibling project whose sources are the pure packages of the app module. Its path is
// deliberately NOT :apps:photoduel:core, which would create an implicit parent :apps:photoduel that
// evaluates the Android build script on machines where that script cannot be configured.
include(":apps:photoduel-core")
project(":apps:photoduel-core").projectDir = file("apps/photoduel/core")
