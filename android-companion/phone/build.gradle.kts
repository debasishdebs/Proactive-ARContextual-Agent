plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

import java.io.File

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
val secretValue = Regex("(?i)(://[^/@\\s]+@|[?&](key|api[-_]?key|token|sig|code|password)=|(^|[^a-z0-9])(access[-_]?token|subscription[-_]?key|client[-_]?secret)=)")

android {
    namespace = "com.rokid.context"
    compileSdk = 36
    ndkVersion = "28.2.13676358" // lets AGP strip native libs (llama_jni, cxr, sqlcipher); same NDK as :services:ai

    defaultConfig {
        applicationId = "com.rokid.context"
        minSdk = 29
        targetSdk = 35
        versionCode = 2
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        fun publicField(name: String, value: String) {
            require(!secretName.containsMatchIn(name)) { "Secret $name must not be compiled into the APK" }
            require(!secretValue.containsMatchIn(value)) { "$name carries credentials (user:pass@ or a key parameter); it must not be compiled into the APK" }
            buildConfigField("String", name, "\"$value\"")
        }

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
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// Compose UI tests need ui-test-manifest's host activity, which is debug-only; release unit tests would have none.
androidComponents { beforeVariants(selector().withBuildType("release")) { it.enableUnitTest = false } }
