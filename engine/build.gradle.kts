plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.aram.voiceengine"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
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
    compileOnly(files(onnxAar))
}
