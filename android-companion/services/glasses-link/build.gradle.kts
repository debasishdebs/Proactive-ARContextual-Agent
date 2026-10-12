plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.rokid.context.services.glasseslink"
    compileSdk = 36
    defaultConfig { minSdk = 29 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    sourceSets["main"].java.srcDirs("src/main/kotlin")
    sourceSets["test"].java.srcDirs("src/test/kotlin")
}

dependencies {
    api(project(":core:model"))
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // Rokid CXR-L through the Hi Rokid app. client-l 1.1.2 depends on a cxr-service-bridge SNAPSHOT; pin the release.
    implementation("com.rokid.cxr:client-l:1.1.2")
    implementation("com.rokid.cxr:cxr-service-bridge:1.5")
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
