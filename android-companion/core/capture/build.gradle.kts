plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin { jvmToolchain(17) }

// Pure Kotlin/JVM: the glance, motion, keyframe-gate and camera-policy decision code, no Android.
dependencies {
    api(project(":core:model")) // MotionState, CameraWake, ContextCapturePolicy; brings kotlinx-coroutines (flows) and serialization
    testImplementation(kotlin("test-junit"))
    testImplementation("junit:junit:4.13.2")
}
