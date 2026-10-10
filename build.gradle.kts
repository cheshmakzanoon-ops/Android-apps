// Root project: shared conventions only. Plugin versions live in gradle/libs.versions.toml and
// are applied per module, so a module that is not needed (for example, the Android module on a
// machine without Google Maven access) is never configured by an unrelated JVM-only task.
// Kotlin plugins are declared once here so every subproject uses the same plugin instance. Without
// this, Gradle warns that the Kotlin plugin is loaded in several subprojects.
plugins {
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
