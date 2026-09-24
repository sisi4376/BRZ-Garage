plugins {
    id("com.android.application")
}

val stableDebugKeystore = rootProject.file("signing/brz-debug.keystore")

android {
    namespace = "com.brz.gauge.trips"
    compileSdk = 37

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "com.brz.gauge.trips"
        minSdk = 26
        targetSdk = 37
        versionCode = 84
        versionName = "3.2.30"
    }

    signingConfigs {
        getByName("debug") {
            require(stableDebugKeystore.isFile) {
                "Missing signing/brz-debug.keystore; refusing to create an APK with a different debug certificate"
            }
            storeFile = stableDebugKeystore
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
