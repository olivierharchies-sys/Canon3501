plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "be.canonhub"
    compileSdk = 35

    defaultConfig {
        applicationId = "be.canonhub"
        minSdk = 29
        targetSdk = 35
        versionCode = 53
        versionName = "2.4.0"
        ndk { abiFilters += "arm64-v8a" }
    }

    // Pilote Gutenprint (C) : compilé seulement si la CI a préparé app/src/main/cpp/gp.
    if (file("src/main/cpp/gp/lib/libgutenprint.a").exists()) {
        externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt") } }
        // Même NDK que celui qui a compilé Gutenprint ; le nom du dossier est son numéro de version.
        System.getenv("ANDROID_NDK_LATEST_HOME")?.let { ndkPath = it; ndkVersion = file(it).name }
    }

    signingConfigs {
        getByName("debug") {
            // Clé fixe : sans elle, chaque build CI a une clé différente et la mise à jour échoue.
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
    // OpenCV et Gutenprint peuvent embarquer chacun la bibliothèque C++ partagée.
    packaging {
        jniLibs { pickFirsts += "**/libc++_shared.so" }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.print:print:1.0.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    // Détection des documents sur la vitre (natif, arm64 seulement via abiFilters).
    implementation("org.opencv:opencv:4.10.0")
}
