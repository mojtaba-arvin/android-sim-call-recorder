plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt.android)
}

android {
    namespace = "com.mojtaba.simcallrecorder"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.mojtaba.simcallrecorder"
        minSdk = 26
        // Keep targetSdk 36 until Android 17 foreground-service and call-audio behavior is validated.
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        val shizukuCaptureEnabled = providers.gradleProperty("enableShizukuCallCapture")
            .map { it.toBoolean() }
            .getOrElse(true)
        buildConfigField("boolean", "ENABLE_SHIZUKU_CALL_CAPTURE", shizukuCaptureEnabled.toString())

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    buildFeatures {
        compose = true
        aidl = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = false
        // Robolectric's API 36+ FileDescriptor interceptor uses JDK SharedSecrets.
        // Keep the narrowly-scoped module export on the test JVM only.
        unitTests.all {
            // Robolectric loads large instrumented framework resource archives across SDKs.
            // The default Gradle test-worker heap is too small for the API 26–37 matrix.
            it.maxHeapSize = "2g"
            it.jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.media3.exoplayer)
    ksp(libs.androidx.room.compiler)

    // MIT-licensed API: user-authorized shell/root UserService, kept optional at runtime.
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
