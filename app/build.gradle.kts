import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

val keystorePropsFile = rootProject.file("app/keystore.properties")
val keystoreProps = if (keystorePropsFile.exists()) {
    Properties().apply { load(FileInputStream(keystorePropsFile)) }
} else null

android {
    namespace = "org.michimusic.mobile"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.michimusic.mobile"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-alpha"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            storeFile = keystoreProps?.let { rootProject.file("app/${it["storeFile"]}") }
            storePassword = keystoreProps?.getProperty("storePassword") ?: ""
            keyAlias = keystoreProps?.getProperty("keyAlias") ?: ""
            keyPassword = keystoreProps?.getProperty("keyPassword") ?: ""
        }
    }

    flavorDimensions += "build"
    productFlavors {
        create("normal") {
            dimension = "build"
        }
        create("fdroid") {
            dimension = "build"
        }
        create("playstore") {
            dimension = "build"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
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

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":data"))
    implementation(project(":player"))
    implementation(project(":sync-client"))
    implementation(project(":remote"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.cardview)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)

    implementation(libs.koin.android)
    implementation(libs.koin.compose)

    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)

    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)

    implementation(libs.discrete.scrollview)
}
