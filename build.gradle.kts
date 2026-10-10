// Root project: shared conventions only. Plugin versions live in gradle/libs.versions.toml and
// are applied per module, so a module that is not needed (for example, the Android module on a
// machine without Google Maven access) is never configured by an unrelated JVM-only task.
tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
