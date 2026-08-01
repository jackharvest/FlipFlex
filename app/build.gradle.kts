import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing, if this machine has the key.
//
// The keystore and its passwords live in ~/.flipflex, never in the repo -- not
// even gitignored inside it, because the one mistake that cannot be undone here
// is publishing the key, and a file that is not in the tree cannot be `git add
// -f`ed by accident. A clone without it still builds: `assembleRelease` just
// produces an unsigned APK, which is fine for anyone building for themselves,
// and `installRelease` will tell them so.
//
// Why it matters more than usual: Android identifies an app by its signature,
// so an APK signed with a different key will not install over one signed with
// this one -- the phone says INSTALL_FAILED_UPDATE_INCOMPATIBLE and the only way
// out is uninstalling, which takes the downloads and the Plex login with it.
// Lose this keystore and every existing install is orphaned for ever. Back it
// up somewhere that is not this laptop.
val keystoreProperties = Properties().apply {
    val f = File(System.getProperty("user.home"), ".flipflex/keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
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
        // versionCode is what the platform compares when deciding whether an
        // APK is an upgrade; versionName is only ever shown to a human, on
        // Settings -> Help. Bump both together, and never reuse a versionCode:
        // the phone refuses to install an APK whose code is lower than the one
        // already there, and silently keeps the old one if they are equal.
        //
        // 3 with the name still at 1.0.0 is the one deliberate exception. The
        // published 1.0.0 asset was code 2, and it was replaced in place rather
        // than given a new number -- the changes in it are the tip-jar row and a
        // launcher force-stop, which is not a version anyone needs to be able to
        // name. The code still had to move, or a phone on the old build would
        // silently keep it. Do not read this as licence to do it again: the next
        // change that is worth shipping gets 4 and a new versionName.
        versionCode = 3
        versionName = "1.0.0"

        // The MT6739 is 32-bit only: ro.product.cpu.abi=armeabi-v7a with no
        // abilist64. Nothing we ship is native today, but pinning the filter
        // means a future native dependency fails at build time rather than
        // producing an APK that installs and then crashes on the phone.
        ndk { abiFilters += "armeabi-v7a" }
    }

    signingConfigs {
        if (keystoreProperties.isNotEmpty()) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    lint {
        // `ExpiredTargetSdkVersion` fails the release build outright, and it is
        // the one lint rule here that is not about the code at all: it enforces
        // Google Play's policy that new uploads target a recent API level. This
        // app is not on Play and cannot be -- it is sideloaded onto one phone
        // model whose only Android is 11. Raising targetSdk to satisfy the rule
        // would opt us into behaviour changes for platform versions this device
        // will never run, which is the opposite of what we want. See the note on
        // targetSdk in defaultConfig.
        disable += "ExpiredTargetSdkVersion"
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
