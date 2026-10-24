plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.floatclip.app"
    compileSdk = 35
    buildToolsVersion = "35.0.1"

    defaultConfig {
        applicationId = "com.floatclip.probe"
        minSdk = 28
        // Deliberately locked to Android 11 behavior for the fixed OriginOS target device.
        targetSdk = 30
        versionCode = 31
        versionName = "0.7.9"
    }

    // Keep the installed Share update identity; namespace only locates reused classes.
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file(".toolchains/nearby-probe/debug.keystore")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    sourceSets.getByName("main") {
        java.srcDir("../nearby-probe/src/main/java")
        res.srcDir("../nearby-probe/src/main/res")
    }
    testOptions { unitTests.isIncludeAndroidResources = true }

    lint {
        disable += "ExpiredTargetSdkVersion"
    }
}

dependencies {
    implementation(project(":share-core"))
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
}
