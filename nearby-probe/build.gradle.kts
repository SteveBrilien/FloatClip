plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.floatclip.probe"
    compileSdk = 35
    buildToolsVersion = "35.0.1"
    defaultConfig {
        applicationId = "com.floatclip.probe"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-p0"
    }
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file(".toolchains/nearby-probe/debug.keystore")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
