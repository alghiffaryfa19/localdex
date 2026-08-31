plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.localdex"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.localdex"
        // Android 11 - Required for Wireless Debugging
        minSdk = 30
        // Same reasoning as anyapk: sideloaded app, staying at 30 keeps the pairing
        // and DeX foreground services off the API 34 typed-FGS rules.
        targetSdk = 30
        versionCode = 1
        versionName = "0.1.0"

        resourceConfigurations += listOf("en")
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

    lint {
        disable += "ExpiredTargetSdkVersion"
    }

    buildFeatures {
        aidl = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")

    // Shizuku API for rootless elevated shell privilege
    val shizukuVersion = "13.1.5"
    implementation("dev.rikka.shizuku:api:$shizukuVersion")
    implementation("dev.rikka.shizuku:provider:$shizukuVersion")

    // LibADB Android — app-to-own-device ADB over wireless debugging
    implementation("com.github.MuntashirAkon:libadb-android:3.1.0")

    // Custom Conscrypt. Required for pairing: PairingConnectionCtx derives the SPAKE2
    // secret from the TLS exporter, and only this Conscrypt exposes it to apps.
    implementation("org.conscrypt:conscrypt-android:2.5.3")

    // For ADB key/certificate generation
    implementation("com.github.MuntashirAkon:sun-security-android:1.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
