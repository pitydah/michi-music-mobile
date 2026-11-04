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
    compileSdk = 36

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
            if (keystoreProps != null && keystorePropsFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
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

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
    }
}

tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
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

    // QR pairing: CameraX for the viewfinder and ZXing core for decoding. Both are
    // FLOSS and ship without Google Play Services, so the F-Droid flavor keeps working.
    // CameraX exposes ListenableFuture in its public API (ProcessCameraProvider
    // .getInstance returns one), so the app module needs Guava on its own compile
    // classpath. Reuse the same coordinate as :player to avoid a second copy.
    implementation(libs.guava)
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    implementation(libs.zxing.core)

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
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.ktor.client.content.negotiation)
    testImplementation(libs.ktor.serialization.kotlinx.json)
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("org.robolectric:robolectric:4.12.2")
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.compose.ui.test.manifest)
    testImplementation(libs.work.testing)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.compose.ui.test.junit4)
    // Espresso 3.6.x calls the hidden InputManager.getInstance(), which no longer
    // exists on API 36, so every onIdle() failed on the API 36 emulator. Pin a
    // newer Espresso that uses the public input APIs instead.
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    debugImplementation(libs.compose.ui.test.manifest)
}

// The :player module compiles its AudioTrackProvider lambda against the catalog's
// media3 version. A transitive bump on the app runtime graph (camera-video 1.6.2
// pulls media3-muxer 1.9.0) ships a library whose abstract methods the compiled
// lambda does not implement, and playback dies with AbstractMethodError the moment
// the audio sink is built. Fail the build instead of shipping that mismatch.
val expectedMedia3Version = libs.versions.media3.get()
tasks.register("checkMedia3Alignment") {
    val runtimeClasspath = configurations.named("normalDebugRuntimeClasspath")
    val expected = expectedMedia3Version
    doLast {
        val resolved = runtimeClasspath.get().incoming.resolutionResult.allComponents
            .mapNotNull { it.moduleVersion }
            .filter { it.group == "androidx.media3" }
            .map { it.version }
            .distinct()
            .sorted()
        check(resolved == listOf(expected)) {
            "media3 drift: catalog=$expected, runtime=$resolved. Align the catalog with the transitive graph."
        }
    }
}
tasks.named("preBuild").configure { dependsOn("checkMedia3Alignment") }
