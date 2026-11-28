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
        // Route Robolectric and Android Test Platform's JUnit artifacts through the
        // Maven Central mirror. Hosted emulator jobs have hit HTTP 429 responses from
        // repo.maven.apache.org while resolving junit-platform-engine/launcher.
        // Keep Maven Central as the fallback for all other groups.
        maven {
            name = "ReliabilityMavenCentralMirror"
            url = uri("https://maven-central.storage-download.googleapis.com/maven2/")
            content {
                includeGroup("org.robolectric")
                includeGroup("org.junit.platform")
                includeGroup("org.apiguardian")
                includeGroup("org.opentest4j")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "sim-call-recorder"
include(":app", ":callrecorder-sdk", ":sdk-consumer-smoke")
