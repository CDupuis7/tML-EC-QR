plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
}

android {
    namespace = "com.example.tml_ec_qr_scan"
    compileSdk = 35
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.3"
    }

    defaultConfig {
        applicationId = "com.example.tml_ec_qr_scan"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += listOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/license.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/notice.txt",
                "META-INF/ASL2.0"
            )
            pickFirst("lib/arm64-v8a/libtensorflowlite_jni.so")
            pickFirst("lib/armeabi-v7a/libtensorflowlite_jni.so")
            pickFirst("lib/x86/libtensorflowlite_jni.so")
            pickFirst("lib/x86_64/libtensorflowlite_jni.so")
        }
    }
    sourceSets {

    }
    configurations.all {
        resolutionStrategy {
            force("org.boofcv:boofcv-android:0.42")
            force("org.boofcv:boofcv-core:0.42")
            force("org.boofcv:boofcv-recognition:0.42")
        }
    }
}

dependencies {
    // Core dependencies
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation("androidx.compose.runtime:runtime:1.6.1")
    implementation("androidx.compose.runtime:runtime-livedata:1.6.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("androidx.compose.ui:ui-unit:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation(libs.firebase.crashlytics.buildtools)

    // Audio processing
    // implementation("com.github.linxz-coder:android-audio-wave:v1.0.0")
    implementation("androidx.media:media:1.7.0")

    // Permissions handling
    implementation("com.google.accompanist:accompanist-permissions:0.30.1")

    // MPAndroidChart for visualizations
    implementation("com.github.PhilJay:MPAndroidChart:v3.1.0")
    implementation(libs.androidx.benchmark.common)
    implementation(libs.play.services.dtdi)

    // Testing dependencies
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    // Network dependencies
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.gson)
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")

    // Barcode scanning
    implementation("com.google.mlkit:barcode-scanning:17.2.0")

    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("me.dm7.barcodescanner:zbar:1.9.8") {
        exclude(group = "com.android.support", module = "support-compat")
        exclude(group = "com.android.support", module = "support-v4")
    }
    implementation("com.google.zxing:core:3.5.2")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("com.opencsv:opencsv:5.9")
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.9.23")
    implementation("org.boofcv:boofcv-android:0.42") {
        exclude(group = "org.georegression", module = "georegression")
    }
    implementation("org.boofcv:boofcv-core:0.42")
    implementation("org.boofcv:boofcv-recognition:0.42")
    implementation("org.georegression:georegression:0.24")

    // CameraX dependencies
    val camerax_version = "1.3.0"
    implementation("androidx.camera:camera-core:$camerax_version")
    implementation("androidx.camera:camera-camera2:$camerax_version")
    implementation("androidx.camera:camera-lifecycle:$camerax_version")
    implementation("androidx.camera:camera-view:$camerax_version")

    // CameraX View class
    implementation("androidx.camera:camera-extensions:$camerax_version")

    // TensorFlow Lite
    implementation("org.tensorflow:tensorflow-lite:2.12.0")
    implementation("org.tensorflow:tensorflow-lite-support:0.4.3")
    implementation("org.tensorflow:tensorflow-lite-metadata:0.4.3")
    
    // Exclude conflicting dependencies
    configurations.all {
        exclude(group = "com.google.ai.edge.litert", module = "litert-api")
    }
}