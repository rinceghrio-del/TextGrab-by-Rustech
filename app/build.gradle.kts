plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.rustech.textgrab"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.rustech.textgrab"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }
    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    // OCR (bundled model, offline) - para sa mga screen na walang readable text (images, games, etc.)
    implementation("com.google.mlkit:text-recognition:16.0.1")
}
