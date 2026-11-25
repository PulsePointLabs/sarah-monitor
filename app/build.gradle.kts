plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.pulsepointlabs.sarahmonitor"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.pulsepointlabs.sarahmonitor"
        minSdk = 26
        // 64-bit ARM only (every current Samsung): keeps the offline speech libraries from quadrupling the APK.
        ndk { abiFilters += listOf("arm64-v8a") }
        targetSdk = 35
        versionCode = 53
        versionName = "0.30.0"
    }

    // One signing key for every build (this machine, CI, anyone's PC), kept in the repo, so each
    // release installs over the last one. It's a sideload key for a private repo, not a Play Store key.
    signingConfigs {
        create("monitor") {
            storeFile = rootProject.file("signing/sarah-monitor.jks")
            storePassword = "sarahmonitor"
            keyAlias = "sarahmonitor"
            keyPassword = "sarahmonitor"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("monitor")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("monitor")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
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
    val lifecycleVersion = "2.9.1"
    val roomVersion = "2.7.2"

    implementation(platform("androidx.compose:compose-bom:2025.06.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.health.connect:connect-client:1.1.0-rc03")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:$lifecycleVersion")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:$lifecycleVersion")
    implementation("androidx.savedstate:savedstate-ktx:1.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Offline speech for hands-free Sarah: hears "Sarah" on the phone; only what follows goes to OpenAI.
    implementation("com.alphacephei:vosk-android:0.3.75")
    implementation("com.anthropic:anthropic-java:3.3.0")
    implementation("com.github.mik3y:usb-serial-for-android:3.8.1")
    implementation("androidx.room:room-ktx:$roomVersion")
    implementation("androidx.room:room-runtime:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    testImplementation("junit:junit:4.13.2")
    // Screenshot harness (app/src/test/.../shot): renders real screens off-device. Only runs with -Pshots.
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("org.json:json:20240303")
}

// Screenshots: ./gradlew :app:testDebugUnitTest -Pshots --tests "*ShotTest*"  → PNGs in app/build/shots.
// Normal test runs skip them. ROBOLECTRIC_REPO can point Robolectric at a Maven mirror if needed.
android.testOptions.unitTests.isIncludeAndroidResources = true
android.testOptions.unitTests.all {
    it.maxHeapSize = "3g"
    System.getenv("ROBOLECTRIC_REPO")?.let { url -> it.systemProperty("robolectric.dependency.repo.url", url) }
    it.systemProperty("shots.dir", layout.buildDirectory.dir("shots").get().asFile.absolutePath)
    if (!project.hasProperty("shots")) it.exclude("**/shot/**")
    // Real recordings check: -Precordings=<folder of exported CSVs> → reports in app/build/recording-check.
    project.findProperty("recordings")?.let { dir -> it.systemProperty("recordings.dir", dir.toString()) }
    it.systemProperty("recording.report.dir", layout.buildDirectory.dir("recording-check").get().asFile.absolutePath)
}
