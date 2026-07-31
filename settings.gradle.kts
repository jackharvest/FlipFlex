pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "FlipFlex"
include(":app")

// A resource overlay, not an app: no code, no activity, one string-array. It
// is what puts FlipFlex in the phone's Menu, which cannot be done from the
// app's own manifest -- see overlay/src/main/res/values/arrays.xml.
include(":overlay")
