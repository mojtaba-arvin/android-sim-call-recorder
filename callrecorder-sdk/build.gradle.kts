plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt.android)
}

android {
    namespace = "com.mojtaba.simcallrecorder.sdk"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
        val shizukuCaptureEnabled = providers.gradleProperty("enableShizukuCallCapture")
            .map { it.toBoolean() }
            .getOrElse(true)
        buildConfigField("boolean", "ENABLE_SHIZUKU_CALL_CAPTURE", shizukuCaptureEnabled.toString())
    }

    buildFeatures {
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
        unitTests.all {
            it.maxHeapSize = "2g"
            it.jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// Room exports one schema JSON per database version. Debug and release KSP tasks share this
// directory, so concurrent execution can truncate/overwrite the same file. Serialize only these
// two schema-producing tasks; all unrelated Gradle work remains parallel.
tasks.matching { it.name == "kspReleaseKotlin" }.configureEach {
    mustRunAfter("kspDebugKotlin")
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.datastore.preferences)
    ksp(libs.androidx.room.compiler)

    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
}
