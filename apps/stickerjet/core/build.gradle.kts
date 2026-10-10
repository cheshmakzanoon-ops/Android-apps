// Pure Kotlin/JVM core of StickerJet: domain rules, typed ports and the animated WebP container
// writer. It has no Android dependency, so it builds and tests on any JDK 17+ machine.
// ADR: docs/decisions.md (ADR-001) explains why this lives beside, not inside, the Android module.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    // Publishes src/testFixtures (in-memory fakes of the domain ports) to the test classpath of
    // other modules: testImplementation(testFixtures(project(":apps:stickerjet-core"))).
    `java-test-fixtures`
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
