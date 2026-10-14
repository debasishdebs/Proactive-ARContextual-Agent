plugins {
    id("com.android.application") version "8.13.2" apply false
    id("com.android.library") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.jvm") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21" apply false
    id("com.google.devtools.ksp") version "2.0.21-1.0.27" apply false
}

// Test-hang diagnostics (Task 20 fix round 1 ruling): every module's test tasks name each test as it starts and fail
// after 10 minutes, so a stall names the test instead of hanging the Gate. Test behaviour is otherwise unchanged.
subprojects {
    tasks.withType<Test>().configureEach {
        timeout.set(java.time.Duration.ofMinutes(10))
        // Gate stall 2026-10-14: the Robolectric worker stopped answering jstack (GC thrash on the 512 MB default heap).
        // A bigger heap, and an out-of-memory that exits with a dump instead of thrashing, so a stall fails fast and explained.
        maxHeapSize = "2g"
        jvmArgs("-XX:+HeapDumpOnOutOfMemoryError", "-XX:HeapDumpPath=${layout.buildDirectory.get().asFile}/test-heap", "-XX:+ExitOnOutOfMemoryError")
        testLogging {
            events("started", "failed", "skipped")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }
}
