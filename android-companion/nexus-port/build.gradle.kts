plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

// The seam between PCA and the optional Nexus bridge APK: the NexusPort AIDL, the PCA-owned types and the ONE choke point (NexusHudSink).
// No Nexus SDK in here (the bridge owns it), no Room, no Android framework in the logic: it runs as plain JVM tests.
android {
    namespace = "io.github.pca.nexusport"
    compileSdk = 36
    defaultConfig { minSdk = 29 }
    buildFeatures { aidl = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    sourceSets["main"].java.srcDirs("src/main/kotlin")
    sourceSets["test"].java.srcDirs("src/test/kotlin")
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
