// Root project: shared conventions only. Plugin versions live in gradle/libs.versions.toml and are
// applied per module, so an unrelated JVM-only task never configures the Android module.
plugins {
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
