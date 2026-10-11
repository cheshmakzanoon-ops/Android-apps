import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Release signing is injected from the environment by the protected release operator.
// No keystore or password ever lives in this repository. Without these variables the release
// bundle is produced unsigned, which is useful for CI validation but cannot be uploaded to Play.
val releaseStoreFile: String? = System.getenv("STICKERJET_UPLOAD_STORE_FILE")

android {
    namespace = "com.agency.stickerjet"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.agency.stickerjet"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = System.getenv("STICKERJET_UPLOAD_STORE_PASSWORD")
                keyAlias = System.getenv("STICKERJET_UPLOAD_KEY_ALIAS")
                keyPassword = System.getenv("STICKERJET_UPLOAD_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (releaseStoreFile != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
        checkDependencies = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    sourceSets {
        // Shared source clips. Only the instrumented journey reads them, so they never reach the APK.
        getByName("androidTest") {
            assets.srcDir("assets/source")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":apps:stickerjet-core"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.savedstate)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.mlkit.segmentation.selfie)
    // The data layer decodes project documents with Json. Core keeps this implementation-only, so the
    // app declares it too, or its compile classpath has no kotlinx.serialization.json.
    implementation(libs.kotlinx.serialization.json)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit4)
    // In-memory fakes of the domain ports (InMemoryProjectRepository, FakeFrameSource, ...).
    testImplementation(testFixtures(project(":apps:stickerjet-core")))

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    // Fakes of the domain ports, used by the import-to-share robot test.
    androidTestImplementation(testFixtures(project(":apps:stickerjet-core")))
}
