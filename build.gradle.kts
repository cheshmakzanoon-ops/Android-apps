// Root project: shared conventions only. Kotlin and AGP plugins are applied directly by each module
// with their own alias. Declaring them here with `apply false` breaks Android configuration, which
// StickerJet's CI showed (KotlinAndroidTarget could not be decorated; see the StickerJet history).
tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
