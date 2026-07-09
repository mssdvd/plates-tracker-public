plugins {
    id("com.android.application")
}

android {
    namespace = "com.mssdvd.platestracker"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "com.mssdvd.platestracker"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1-spike"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    // The ONNX models are already compressed; don't let aapt re-compress them (slows load).
    androidResources {
        noCompress += listOf("onnx")
    }

    // ORT/CameraX ship prebuilt .so files AGP can't strip (no symbols / no NDK strip here); it
    // packages them as-is and warns each build. Mark them keep-debug-symbols so it skips the
    // futile strip attempt — same packaged result, no warning.
    packaging {
        jniLibs {
            keepDebugSymbols += "**/*.so"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    val cameraxVersion = "1.5.1"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")

    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.27.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation("androidx.lifecycle:lifecycle-service:2.11.0")
    implementation("androidx.work:work-runtime-ktx:2.11.2")
    implementation("com.google.android.gms:play-services-location:21.4.0")
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.activity:activity-ktx:1.13.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")

    testImplementation("junit:junit:4.13.2")

    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:rules:1.7.0")
}
