plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.aram.voiceengine.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.aram.voiceengine.app"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

val onnxAar = file("${System.getenv("TMPDIR")}/onnxruntime-android.aar")
check(onnxAar.isFile) { "ONNX Runtime AAR not found: $onnxAar" }

dependencies {
    implementation(project(":engine"))
    implementation(files(onnxAar))
}
