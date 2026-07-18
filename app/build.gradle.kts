import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

val keystorePropsFile = rootProject.file("app/keystore.properties")
val keystoreProps = if (keystorePropsFile.exists()) {
    Properties().apply { load(FileInputStream(keystorePropsFile)) }
} else null

val gitCommitCount: String = rootProject.rootDir.let {
    try {
        val process = ProcessBuilder("git", "rev-list", "--count", "HEAD")
            .directory(it)
            .start()
        process.inputStream.bufferedReader().readText().trim()
    } catch (_: Exception) {
        "1"
    }
}

android {
    namespace = "org.michimusic.mobile"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.michimusic.mobile"
        minSdk = 31
        targetSdk = 35
        versionCode = gitCommitCount.toIntOrNull() ?: 1
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
            versionName = "0.1.0-alpha-fdroid"
        }
        create("playstore") {
            dimension = "build"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
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

    androidComponents {
        onVariants { variant ->
            if (variant.buildType == "release" || variant.flavorName == "fdroid") {
                variant.reproducibleBuild = true
            }
        }
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":data"))
    implementation(project(":player"))
    implementation(project(":michi-link-client"))

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
    implementation(libs.coil.core)
    implementation(libs.coil.network.okhttp)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)

    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)

    implementation(libs.discrete.scrollview)
    implementation(libs.work.manager)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    implementation(libs.androidx.paging.compose)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.room.testing)
    testImplementation(libs.mockk)
    testImplementation("androidx.test:core:1.6.1")
}
