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
        targetSdk = 35
        versionCode = 5
        versionName = "0.2.3"
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
    implementation("androidx.room:room-ktx:$roomVersion")
    implementation("androidx.room:room-runtime:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
