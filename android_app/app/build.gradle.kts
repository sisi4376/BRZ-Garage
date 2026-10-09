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
        versionCode = 145
        versionName = "4.3.5"
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

// Bundle the shared renderer and licensed models, excluding downloads and research files.
val vehicleAssets = layout.buildDirectory.dir("generated/vehicleAssets")
val syncVehicleAssets by tasks.registering(Sync::class) {
    from(rootProject.file("../preview/vehicle-3d")) {
        include("app.html", "real-models.js", "vehicle-camera.mjs", "vendor/three.module.js", "vendor/GLTFLoader.js",
            "vendor/BufferGeometryUtils.js", "vendor/THREE-LICENSE.txt",
            "models/*/vehicle.glb", "models/*/source.json", "models/*/original/license.txt")
        into("vehicle-3d")
    }
    into(vehicleAssets)
}
android.sourceSets.getByName("main").assets.srcDir(vehicleAssets.get().asFile)
tasks.named("preBuild").configure { dependsOn(syncVehicleAssets) }
