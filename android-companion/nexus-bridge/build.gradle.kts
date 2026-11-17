plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// The optional Nexus bridge APK (v2.0, opt-in, OFF by default): a headless Rokid Nexus plugin that only draws what PCA's phone app hands it through NexusPort.
// It owns the Nexus SDK; PCA's phone app never links it. See README.md for how the SDK resolves and how to remove the bridge.

// Pinned upstream SDK tag (github.com/Anezium/Rokid-Nexus, Apache-2.0). Resolved through JitPack, restricted to this one group in settings.gradle.kts.
val nexusSdk = "sdk-v0.27.0"

android {
    namespace = "io.github.pca.nexusbridge"
    compileSdk = 36
    defaultConfig {
        // Distinct from the PCA phone app's `com.rokid.context` on purpose: a separate APK, installable and removable on its own.
        applicationId = "io.github.pca.nexusbridge"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "2.0.0-dev"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions {
        jvmTarget = "17"
        // The SDK AAR is built with a newer Kotlin than this project's 2.0.21; the metadata is read, never re-emitted.
        freeCompilerArgs += "-Xskip-metadata-version-check"
    }
    sourceSets["main"].java.srcDirs("src/main/kotlin")
    sourceSets["test"].java.srcDirs("src/test/kotlin")
    buildTypes { release { isMinifyEnabled = false } }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    implementation(project(":nexus-port"))
    implementation("com.github.Anezium.Rokid-Nexus:bus-client:$nexusSdk")
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}
