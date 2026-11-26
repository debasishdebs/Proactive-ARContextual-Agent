plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.rokid.context.services.visionlocal"
    compileSdk = 36
    ndkVersion = "28.2.13676358" // same NDK as :services:ai
    defaultConfig {
        minSdk = 29
        ndk { abiFilters += "arm64-v8a" } // on-device model: 64-bit ARM only
        consumerProguardFiles("consumer-rules.pro") // keeps the JNI class when R8 is switched on
        externalNativeBuild {
            cmake {
                // VLM_ARM_ARCH is the -march string handed to ggml (dotprod + i8mm, the S25 class). Override with -PvlmArch=armv8-a for a baseline build.
                val arch = (project.findProperty("vlmArch") as String?) ?: "armv8.2-a+dotprod+i8mm"
                arguments += listOf("-DANDROID_STL=c++_static", "-DVLM_ARM_ARCH=$arch")
                cFlags += "-O3"; cppFlags += "-O3" // -O3 in every variant (ggml at -O0 is many times slower)
            }
        }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    sourceSets["main"].java.srcDirs("src/main/kotlin")
    sourceSets["test"].java.srcDirs("src/test/kotlin")
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    api(project(":core:model"))
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
