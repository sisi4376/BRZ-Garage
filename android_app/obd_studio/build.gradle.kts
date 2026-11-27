plugins { id("com.android.application") }
android {
    namespace = "com.brz.obdstudio"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.brz.obdstudio"
        minSdk = 26
        targetSdk = 37
        versionCode = 13
        versionName = "1.0.0-beta.13"
    }
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("signing/brz-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
