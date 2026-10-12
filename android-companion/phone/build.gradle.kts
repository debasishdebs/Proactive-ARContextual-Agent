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

android {
    namespace = "com.rokid.context"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.rokid.context"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "AZURE_KG_ENDPOINT", "\"${envOrDefault("AZURE_KG_ENDPOINT", "")}\"")
        buildConfigField("String", "AZURE_KG_API_KEY", "\"${envOrDefault("AZURE_KG_54_API_KEY", "")}\"")
        buildConfigField("String", "AZURE_KG_DEPLOYMENT", "\"${envOrDefault("AZURE_KG_DEPLOYMENT", "gpt-5.4")}\"")
        buildConfigField("String", "OLLAMA_BASE_URL", "\"${envOrDefault("OLLAMA_BASE_URL", "http://10.0.2.2:11434")}\"")
        buildConfigField("String", "OLLAMA_MODEL", "\"${envOrDefault("OLLAMA_MODEL", "llama3.1:8b")}\"")
        buildConfigField("String", "LOCAL_RUNTIME_MODE", "\"${envOrDefault("LOCAL_RUNTIME_MODE", "native_first")}\"")
        buildConfigField("String", "LOCAL_NATIVE_MODEL_PATH", "\"${envOrDefault("LOCAL_NATIVE_MODEL_PATH", "")}\"")
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
    implementation(project(":services:ai"))
    implementation(project(":services:memory"))
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.navigation:navigation-compose:2.8.0")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.5")
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
}
