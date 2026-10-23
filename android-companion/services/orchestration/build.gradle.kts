plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.rokid.context.services.orchestration"
    compileSdk = 36
    defaultConfig { minSdk = 29 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isIncludeAndroidResources = true }
    // PulledTraces (a valid pulled-trace writer) is shared with the phone import-route tests.
    sourceSets.getByName("test").java.srcDir("src/pulledfixtures/java")
}

dependencies {
    api(project(":services:ai"))
    api(project(":core:capture"))
    api(project(":services:memory"))
    api(project(":services:connectors"))
    api("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.android.gms:play-services-mlkit-text-recognition:19.0.1") // unbundled Latin model, delivered by Play services
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation("androidx.work:work-testing:2.9.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("com.squareup.okhttp3:okhttp-tls:4.12.0")
}

// WP1: the shared helper-protocol fixtures are read by the unit tests; make them an input so a changed fixture re-runs the tests.
tasks.withType<Test>().configureEach { inputs.dir(rootProject.projectDir.resolve("../tools/canvas-helper/protocol")).withPropertyName("helperProtocolFixtures") }
