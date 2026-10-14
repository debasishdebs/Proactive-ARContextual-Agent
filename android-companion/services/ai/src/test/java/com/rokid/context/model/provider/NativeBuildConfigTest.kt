package com.rokid.context.model.provider

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Final review I1: llama.cpp/ggml and the JNI are optimised in every variant, debug included (the APK used day to day). */
class NativeBuildConfigTest {
    private val gradle = File("build.gradle.kts").readText()

    @Test fun everyVariantCompilesNativeCodeOptimised() {
        val defaultConfig = gradle.substringAfter("defaultConfig {").substringBefore("buildTypes")
        // defaultConfig's cmake flags go into CMAKE_C/CXX_FLAGS, which also reach the llama.cpp subdirectory.
        assertTrue("cFlags -O2/-O3 in defaultConfig", Regex("""cFlags \+= "-O[23]"""").containsMatchIn(defaultConfig))
        assertTrue("cppFlags -O2/-O3 in defaultConfig", Regex("""cppFlags \+= "-O[23]"""").containsMatchIn(defaultConfig))
        assertTrue("no variant turns optimisation off", !gradle.contains("\"-O0\""))
    }
}
