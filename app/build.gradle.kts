plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt.android)
}

private val releaseSigningEnvironment = listOf(
    "ANDROID_KEYSTORE_PATH",
    "ANDROID_KEYSTORE_PASSWORD",
    "ANDROID_KEY_ALIAS",
    "ANDROID_KEY_PASSWORD",
).associateWith { providers.environmentVariable(it).orNull?.takeIf(String::isNotBlank) }

private val releaseSigningConfigured = releaseSigningEnvironment.values.all { !it.isNullOrBlank() }

val verifyReleaseSigningInputs = tasks.register("verifyReleaseSigningInputs") {
    group = "verification"
    description = "Fails closed unless the production APK signing keystore and credentials are present."

    doLast {
        val missing = releaseSigningEnvironment.filterValues { it.isNullOrBlank() }.keys
        if (missing.isNotEmpty()) {
            throw GradleException(
                "APP_RELEASE_SIGNING_INPUTS_MISSING: configure all required environment variables: ${missing.joinToString()}",
            )
        }
        val keystorePath = releaseSigningEnvironment.getValue("ANDROID_KEYSTORE_PATH")!!
        if (!file(keystorePath).isFile) {
            throw GradleException("APP_RELEASE_KEYSTORE_NOT_FOUND: configured keystore file is not present.")
        }
    }
}

android {
    namespace = "com.mojtaba.simcallrecorder"
    compileSdk {
        version = release(37) {
            minorApiLevel = 2
        }
    }

    defaultConfig {
        applicationId = "com.mojtaba.simcallrecorder"
        minSdk = 24
        // Keep targetSdk 36 until Android 17 foreground-service and call-audio behavior is validated.
        targetSdk = 36
        versionCode = providers.gradleProperty("appVersionCode").map(String::toInt).getOrElse(2)
        versionName = providers.gradleProperty("appVersion").getOrElse("0.1.0-beta.1")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        create("production") {
            if (releaseSigningConfigured) {
                storeFile = file(releaseSigningEnvironment.getValue("ANDROID_KEYSTORE_PATH")!!)
                storePassword = releaseSigningEnvironment.getValue("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = releaseSigningEnvironment.getValue("ANDROID_KEY_ALIAS")
                keyPassword = releaseSigningEnvironment.getValue("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("release") {
            // Never fall back to the debug certificate or produce an unsigned production APK.
            signingConfig = if (releaseSigningConfigured) signingConfigs.getByName("production") else null
        }
    }

    buildFeatures { compose = true }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = false
        unitTests.all {
            it.maxHeapSize = "2g"
            it.jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
        }
    }
}

// APK packaging is blocked unless the production signing inputs and keystore file exist.
tasks.configureEach {
    if (
        name == "validateSigningRelease" ||
        name == "validateSigningReleaseBundle" ||
        name == "packageRelease" ||
        name == "bundleRelease" ||
        name == "assembleRelease"
    ) {
        dependsOn(verifyReleaseSigningInputs)
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    implementation(project(":callrecorder-sdk"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    // The app owns the Hilt application component; SDK bindings are contributed by the library.
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    implementation(libs.androidx.media3.exoplayer)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
}
