plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.jackharvest.flipflex"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.github.jackharvest.flipflex"
        // minSdk and targetSdk are both 30 because the only device this app will
        // ever run on is a TCL 4058G on AOSP 11 (API 30). Targeting higher would
        // opt us into behaviour changes for platforms that do not exist here.
        minSdk = 30
        targetSdk = 30
        versionCode = 1
        versionName = "0.1.0"

        // The MT6739 is 32-bit only: ro.product.cpu.abi=armeabi-v7a with no
        // abilist64. Nothing we ship is native today, but pinning the filter
        // means a future native dependency fails at build time rather than
        // producing an APK that installs and then crashes on the phone.
        ndk { abiFilters += "armeabi-v7a" }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
