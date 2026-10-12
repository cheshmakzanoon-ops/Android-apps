// Pure JVM build for PhotoDuel's domain rules, remote data client and pure engine math.
//
// The sources are NOT copied: the sourceSets below point at the packages inside the Android module
// (src/main/kotlin/com/agency/photoduel/...). That keeps one copy of each rule. Only packages that
// do not import android.*, androidx.*, coil or Android codecs may be listed here. Adding a package
// that needs Android is a build error by design, which is the point of this module.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

val appRoot = file("../src")

sourceSets {
    main {
        kotlin.srcDirs(
            appRoot.resolve("main/kotlin/com/agency/photoduel/domain"),
            appRoot.resolve("main/kotlin/com/agency/photoduel/data/remote"),
            appRoot.resolve("main/kotlin/com/agency/photoduel/engine/pure"),
            appRoot.resolve("main/kotlin/com/agency/photoduel/ui/state"),
        )
    }
    test {
        kotlin.srcDirs(
            appRoot.resolve("test/kotlin/com/agency/photoduel/domain"),
            appRoot.resolve("test/kotlin/com/agency/photoduel/data/remote"),
            appRoot.resolve("test/kotlin/com/agency/photoduel/engine/pure"),
            appRoot.resolve("test/kotlin/com/agency/photoduel/ui/state"),
        )
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        allWarningsAsErrors.set(false)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}

tasks.test {
    testLogging {
        events("failed", "skipped")
        showStandardStreams = false
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
