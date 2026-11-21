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
        // usb-serial-for-android (Arduino EMG over USB)
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "SarahMonitor"
include(":app")
