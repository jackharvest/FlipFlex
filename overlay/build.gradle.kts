plugins {
    id("com.android.application")
}

/**
 * A runtime resource overlay that puts FlipFlex in the phone's Menu.
 *
 * It ships no code and no activity. Its entire content is one string-array that
 * replaces `com.android.launcher3`'s `allapp_list` -- see
 * src/main/res/values/arrays.xml for why that is the only way onto that screen.
 *
 * Built as `release` rather than `debug`, signed with the debug key. The key
 * does not matter for an overlay in a system partition, but the build type
 * does: AGP marks debug builds `android:testOnly`, and PackageManager refuses
 * to scan a testOnly package out of /system -- which would look exactly like
 * the overlay being rejected for an interesting reason.
 */
android {
    namespace = "com.github.jackharvest.flipflex.menu"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.github.jackharvest.flipflex.menu"
        minSdk = 30
        targetSdk = 30
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    // lintVitalRelease fails the build on ExpiredTargetSdkVersion, which is a
    // Play Store rule about apps people download. This is a resource overlay
    // for one handset that will never see the Play Store, and targetSdk 30 is
    // pinned deliberately -- an overlay must be built against the platform it
    // overlays, not against a newer one.
    lint {
        checkReleaseBuilds = false
    }
}
