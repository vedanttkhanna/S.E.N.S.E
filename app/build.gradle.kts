plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.aegisedge.os"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.aegisedge.os"
        // minSdk 26: Camera2 full capability + MediaMuxer + hardware-backed keystore
        // on the $150 device tier (Snapdragon 4-series / MediaTek Helio).
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-alpha"

        // Only ship ABIs the target hardware actually uses; keeps the APK small
        // so the INT4 model files dominate the install footprint instead.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures { compose = true }

    // .task / .tflite model bundles must not be compressed inside the APK —
    // LiteRT and MediaPipe memory-map them directly from the asset FD.
    androidResources {
        noCompress += listOf("tflite", "task", "bin")
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        // LiteRT GPU delegate ships duplicate license files across artifacts.
        resources.pickFirsts += "**/libc++_shared.so"
    }
}

dependencies {
    // ---------- UI: Jetpack Compose ----------
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // ---------- Core / Coroutines ----------
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // ---------- Edge AI: Google AI Edge / LiteRT (formerly TensorFlow Lite) ----------
    implementation("com.google.ai.edge.litert:litert:1.1.2")
    implementation("com.google.ai.edge.litert:litert-gpu:1.1.2")
    implementation("com.google.ai.edge.litert:litert-support:1.1.2")

    // ---------- MediaPipe Tasks ----------
    // Layer 1 perception gate: object detection (Nano-YOLO class models) + audio classification.
    implementation("com.google.mediapipe:tasks-vision:0.10.21")
    implementation("com.google.mediapipe:tasks-audio:0.10.21")
    // LLM Inference API: runs PaliGemma / Gemma 3 / ShieldGemma .task bundles on mobile GPU.
    implementation("com.google.mediapipe:tasks-genai:0.10.24")

    // ---------- Camera2 is a framework API (android.hardware.camera2) — no dependency needed.
    // androidx.concurrent gives us ListenableFuture interop for Camera2 callbacks.
    implementation("androidx.concurrent:concurrent-futures-ktx:1.2.0")

    // ---------- Location (GPS telemetry for dashcam/forensic manifests) ----------
    implementation("com.google.android.gms:play-services-location:21.3.0")

    // ---------- Test ----------
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
