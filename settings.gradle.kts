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
        // Isolate Robolectric artifacts from intermittent Maven Central HTTP 429 responses.
        // This is a Maven Central mirror scoped to one group; Maven Central remains the fallback.
        maven {
            name = "RobolectricMavenCentralMirror"
            url = uri("https://maven-central.storage-download.googleapis.com/maven2/")
            content {
                includeGroup("org.robolectric")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "sim-call-recorder"
include(":app", ":callrecorder-sdk")
