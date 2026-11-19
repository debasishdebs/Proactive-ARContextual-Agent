plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

import java.io.File
import java.util.Properties

fun loadDotEnv(file: File): Map<String, String> {
    if (!file.exists()) return emptyMap()
    return file.readLines()
        .asSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains("=") }
        .associate {
            val idx = it.indexOf('=')
            val key = it.substring(0, idx).trim()
            val value = it.substring(idx + 1).trim()
            key to value
        }
}

val env = loadDotEnv(rootDir.parentFile.resolve(".env"))

fun envOrDefault(key: String, default: String): String = env[key].orEmpty().ifBlank { default }

// No API key or other secret may be compiled into the APK; keys live only in the on-device KeyVault.
val secretName = Regex("(?i)(KEY|SECRET|TOKEN|PASSWORD|PASSWD|CREDENTIAL)")

// The street-map tile key is the ONE named exception (MAP_TILES_KEY; the test NoSecretsInBuildConfigTest names it too): a map-tile client key is embedded in the APK by design and is NOT secret (restrict it by app/referrer in the provider's dashboard).
// Read from android-companion/local.properties (untracked) or the environment; empty by default, and an empty key means a release build shows the grid only. NEVER commit a value.
val mapTilesKey: String = run {
    val lp = Properties().also { p -> rootDir.resolve("local.properties").takeIf { it.isFile }?.inputStream()?.use { p.load(it) } }
    (lp.getProperty("PCA_MAP_TILES_KEY") ?: System.getenv("PCA_MAP_TILES_KEY") ?: "").trim()
}
val secretValue = Regex("(?i)(://[^/@\\s]+@|[?&](key|api[-_]?key|token|sig|code|password)=|(^|[^a-z0-9])(access[-_]?token|subscription[-_]?key|client[-_]?secret)=)")

android {
    namespace = "com.rokid.context"
    compileSdk = 36
    ndkVersion = "28.2.13676358" // lets AGP strip native libs (llama_jni, cxr, sqlcipher); same NDK as :services:ai

    defaultConfig {
        applicationId = "com.rokid.context"
        minSdk = 29
        targetSdk = 35
        versionCode = 4
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        fun publicField(name: String, value: String) {
            require(!secretName.containsMatchIn(name)) { "Secret $name must not be compiled into the APK" }
            require(!secretValue.containsMatchIn(value)) { "$name carries credentials (user:pass@ or a key parameter); it must not be compiled into the APK" }
            buildConfigField("String", name, "\"$value\"")
        }

        require(Regex("[A-Za-z0-9_-]{0,80}").matches(mapTilesKey)) { "PCA_MAP_TILES_KEY must be letters, digits, - or _ only" }
        buildConfigField("String", "MAP_TILES_KEY", "\"$mapTilesKey\"")

        publicField("AZURE_KG_ENDPOINT", envOrDefault("AZURE_KG_ENDPOINT", ""))
        publicField("AZURE_KG_DEPLOYMENT", envOrDefault("AZURE_KG_DEPLOYMENT", "gpt-5.4"))
        publicField("OLLAMA_BASE_URL", envOrDefault("OLLAMA_BASE_URL", "http://10.0.2.2:11434"))
        publicField("OLLAMA_MODEL", envOrDefault("OLLAMA_MODEL", "llama3.1:8b"))
        publicField("LOCAL_RUNTIME_MODE", envOrDefault("LOCAL_RUNTIME_MODE", "native_first"))
        publicField("LOCAL_NATIVE_MODEL_PATH", envOrDefault("LOCAL_NATIVE_MODEL_PATH", ""))
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    testOptions { unitTests.isIncludeAndroidResources = true }
    sourceSets.getByName("test").java.srcDir("../services/orchestration/src/pulledfixtures/java")

    buildFeatures {
        buildConfig = true
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":services:glasses-link"))
    implementation(project(":services:ai"))
    implementation(project(":services:memory"))
    implementation(project(":services:orchestration"))
    implementation(project(":services:connectors"))
    implementation(project(":services:voice"))
    implementation(project(":core:ui"))
    implementation(project(":nexus-port")) // v2.0: the seam to the optional Nexus bridge APK (AIDL + the NexusHudSink choke point); no Nexus SDK here
    implementation(project(":core:hud")) // clampHudScale, Hud.MIN_SCALE/MAX_SCALE for the glasses settings (Task 16)
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.5")
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.navigation:navigation-compose:2.8.0")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.5")
    implementation("androidx.lifecycle:lifecycle-process:2.8.5")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    // Wave B context sources: activity-transition + (Task 4) fused location speed; Android Auto CarConnection (Task 5).
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("com.google.android.gms:play-services-wearable:19.0.0") // Data Layer: the PCA watch app's /pca/motion messages (same applicationId and signing key)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")
    implementation("androidx.car.app:app:1.4.0")

    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation(platform("androidx.compose:compose-bom:2024.09.00"))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("androidx.work:work-testing:2.9.1")
    // Headless JS tests for the canvas bundle (ES modules); 23.1 is the last GraalJS line that runs on JDK 17.
    testImplementation("org.graalvm.polyglot:polyglot:23.1.13")
    testImplementation("org.graalvm.polyglot:js-community:23.1.13") { artifact { type = "pom" } }
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // Device-only: the real AndroidKeyStore handshake test (run on the phone in Task 14; compile-verified in CI-less builds).
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:core:1.6.1")
}

// Compose UI tests need ui-test-manifest's host activity, which is debug-only; release unit tests would have none.
androidComponents { beforeVariants(selector().withBuildType("release")) { it.enableUnitTest = false } }
