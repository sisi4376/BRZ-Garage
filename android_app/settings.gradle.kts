pluginManagement {
    repositories {
        maven { url = uri(rootDir.resolve("../.toolchains/local-maven")) }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "com.android.application") {
                useModule("com.android.tools.build:gradle:${requested.version}")
            }
        }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri(rootDir.resolve("../.toolchains/local-maven")) }
        google()
        mavenCentral()
    }
}

rootProject.name = "BRZTripSync"
include(":app")
