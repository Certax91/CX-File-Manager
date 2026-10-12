plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "de.ordnungshelfer"
    compileSdk = 35

    defaultConfig {
        applicationId = "de.ordnungshelfer"
        minSdk = 26
        targetSdk = 35
        versionCode = 16
        versionName = "2.2.2"
    }

    // Fester Schlüssel, damit neue Versionen über die alte installiert werden können
    signingConfigs {
        getByName("debug") {
            storeFile = file("ordnungshelfer.keystore")
            storePassword = "ordnungshelfer"
            keyAlias = "ordnungshelfer"
            keyPassword = "ordnungshelfer"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    // App-Sperre mit Fingerabdruck / Displaysperre
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    // Dokumentenscanner (Kantenerkennung, Zuschneiden, PDF) über Google Play-Dienste
    implementation("com.google.android.gms:play-services-mlkit-document-scanner:16.0.0")
    // Texterkennung (auf dem Gerät) für gescannte Dokumente
    implementation("com.google.android.gms:play-services-mlkit-text-recognition:19.0.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
