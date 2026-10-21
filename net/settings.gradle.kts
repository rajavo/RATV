// RA Net is its own Gradle build (built with ./gradlew -p net), so it never changes or republishes RATV.
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

rootProject.name = "RANet"
include(":app")
// A tiny app used only by the emulator test: it keeps calling a test server and logs the result.
include(":probe")
