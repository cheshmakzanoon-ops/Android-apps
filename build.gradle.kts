// Root project: shared conventions only. Plugin versions live in gradle/libs.versions.toml and
// are applied per module, so a module that is not needed (for example, the Android module on a
// machine without Google Maven access) is never configured by an unrelated JVM-only task.
//
// Do not declare Kotlin or Android plugins here with `apply false`. On CI that made the Kotlin
// plugin resolve against the root classloader, and every module failed with
// "Could not generate a decorated class for type KotlinAndroidTarget" (BaseVariant not found).
tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
