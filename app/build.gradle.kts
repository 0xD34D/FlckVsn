import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

fun getFormattedDate(): String {
    val dateFormat = SimpleDateFormat("yyyyMMddHHmm", Locale.getDefault())
    return dateFormat.format(Date())
}

android {
    namespace = "com.scheffsblend.flckvsn"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.scheffsblend.flckvsn"
        minSdk = 26
        targetSdk = 35
        val versionMajor = 0
        val versionMinor = 1
        versionName = "${versionMajor}.${versionMinor}_${getFormattedDate()}"
        base.archivesName.set("${namespace}-${versionName}")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    // The bundled .tflite models are fp16 weights; some are large (the
    // "large" variant is ~92MB) -- don't let AGP try to compress them in
    // the APK (wastes time/space; TFLite mmaps them from assets anyway).
    androidResources {
        noCompress += "tflite"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.activity:activity-compose:1.13.0")

    val composeBom = platform("androidx.compose:compose-bom:2024.10.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.camera:camera-core:1.6.2")
    implementation("androidx.camera:camera-camera2:1.6.2")
    implementation("androidx.camera:camera-lifecycle:1.6.2")
    implementation("androidx.camera:camera-view:1.6.2")

    // Primary TensorFlow Lite runtime & GPU delegate for hardware acceleration with CPU fallback
    implementation("org.tensorflow:tensorflow-lite:2.17.0")
    implementation("org.tensorflow:tensorflow-lite-gpu:2.17.0")
    implementation("org.tensorflow:tensorflow-lite-gpu-api:2.17.0")
}
