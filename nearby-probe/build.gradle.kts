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
        versionCode = 4
        versionName = "0.3.0-preview"
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

dependencies { implementation(project(":share-core")) }
