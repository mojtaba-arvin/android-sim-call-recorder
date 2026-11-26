plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt.android)
}

android {
    namespace = "com.mojtaba.simcallrecorder.consumer"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.mojtaba.simcallrecorder.consumer"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // Deliberately consume the built Release AAR, not project(":callrecorder-sdk").
    implementation(files(rootProject.file("callrecorder-sdk/build/outputs/aar/callrecorder-sdk-release.aar")))
    implementation(libs.androidx.activity)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.kotlinx.coroutines.core)
}

// Ensure the exact AAR being tested exists before the consumer app is compiled.
tasks.configureEach {
    if (name == "preBuild") {
        dependsOn(":callrecorder-sdk:assembleRelease")
    }
}
