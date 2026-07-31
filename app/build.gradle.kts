plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.github.jackharvest.flipflex"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.github.jackharvest.flipflex"
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

    // Media3's core is pure Java over MediaCodec, so it needs no .so and stays
    // compatible with this 32-bit-only SoC. The HLS module is not optional:
    // Plex's transcoder speaks /video/:/transcode/universal/start.m3u8, and
    // without it ExoPlayer cannot parse a playlist at all.
    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.3.1")
    implementation("androidx.media3:media3-ui:1.3.1")

    // No HTTP library. Android's HttpURLConnection is OkHttp underneath, and
    // org.json ships in the platform -- on a handset with a 128 MB heap growth
    // limit, a dependency we do not need is one we should not carry.
}
