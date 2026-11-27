plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// A15/A16: the other-UID MediaStore consent instrumentation test installs an
// auxiliary fixture application (`:metadata-fixture`) inside the isolated
// emulator. The fixture debug APK is staged as an ANDROIDTEST asset, so it can
// never reach the distributed application, a release variant or a peer node.
val metadataFixtureAssetDir = layout.buildDirectory.dir("metadata-fixture-androidTest-assets")

android {
    namespace = "org.michimusic.data"
    compileSdk = 36

    defaultConfig {
        minSdk = 31
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    sourceSets {
        getByName("test").assets.srcDir(files("$projectDir/schemas"))
        getByName("androidTest").assets.srcDir(metadataFixtureAssetDir)
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
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

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// Builds the auxiliary fixture application and copies ONLY its debug APK into
// the androidTest assets consumed above. No product variant depends on it.
val stageMetadataFixtureApk = tasks.register<Copy>("stageMetadataFixtureApk") {
    dependsOn(":metadata-fixture:assembleDebug")
    from(
        project(":metadata-fixture").layout.buildDirectory.file(
            "outputs/apk/debug/metadata-fixture-debug.apk",
        ),
    )
    into(metadataFixtureAssetDir)
    rename { "metadata-fixture-debug.apk" }
}

tasks.matching { task ->
    task.name.startsWith("merge") && task.name.endsWith("AndroidTestAssets")
}.configureEach {
    dependsOn(stageMetadataFixtureApk)
}

dependencies {
    implementation(project(":core"))
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    implementation(libs.androidx.room.paging)
    ksp(libs.room.compiler)

    implementation(libs.koin.android)

    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.room.testing)
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("org.robolectric:robolectric:4.12.2")
    testImplementation(libs.mockk)

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    implementation(libs.jaudiotagger)
}
