plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

/**
 * A15/A16 auxiliary fixture application.
 *
 * This module is a TEST-ONLY artifact: no shipped Michi APK depends on it and
 * its package name (`org.michimusic.metadata.fixture`) is never included in the
 * distributed application. It exists so instrumentation can prove MediaStore
 * behaviour against a genuinely different applicationId/UID: the fixture owns
 * the media item, the app under test does not.
 *
 * Keep it dependency-free (framework classes only) so the debug APK stays tiny
 * and can be staged into `:data` androidTest assets by `data/build.gradle.kts`.
 */
android {
    namespace = "org.michimusic.metadata.fixture"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.michimusic.metadata.fixture"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        getByName("debug") {
            isMinifyEnabled = false
        }
        getByName("release") {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}
