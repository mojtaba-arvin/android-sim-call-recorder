pluginManagement {
    repositories {
        // Resolve Kotlin's Gradle plugin marker and implementation from the
        // Maven Central mirror before falling back to the public plugin portal.
        // This avoids intermittent plugin-portal metadata failures on cold runners.
        maven {
            name = "KotlinPluginMavenCentralMirror"
            url = uri("https://maven-central.storage-download.googleapis.com/maven2/")
            content {
                includeGroup("org.jetbrains.kotlin.plugin.compose")
                includeGroupByRegex("org\\.jetbrains\\.kotlin(\\..*)?")
            }
        }
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
