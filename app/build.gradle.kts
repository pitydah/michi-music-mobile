import java.io.FileInputStream
import java.util.Properties
import org.michimusic.build.resolveMichiVersionCode

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

val versionCodePropsFile = rootProject.file("gradle/version-code.properties")
val versionCodeProps = Properties().apply {
    FileInputStream(versionCodePropsFile).use(::load)
}

val gitCommitCount: String? = rootProject.rootDir.let {
    try {
        val process = ProcessBuilder("git", "rev-list", "--count", "HEAD")
            .directory(it)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText().trim()
        output.takeIf { process.waitFor() == 0 }
    } catch (_: Exception) {
        null
    }
}

val resolvedVersionCode = resolveMichiVersionCode(
    explicitVersionCode = System.getenv("MICHI_VERSION_CODE"),
    githubRunNumber = System.getenv("GITHUB_RUN_NUMBER"),
    gitCommitCount = gitCommitCount,
    lastPublishedVersionCode = versionCodeProps.getProperty("lastPublishedVersionCode"),
)

// Default-on product behavior. The explicit false value exists only so physical
// QA can build a same-SHA Dynamic Mesh baseline for a controlled A/B comparison.
val dynamicMeshEnabled = providers.gradleProperty("michi.dynamicMeshEnabled")
    .orElse("true")
    .map { raw ->
        raw.toBooleanStrictOrNull()
            ?: error("michi.dynamicMeshEnabled must be exactly true or false")
    }

android {
    namespace = "org.michimusic.mobile"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.michimusic.mobile"
        minSdk = 31
        targetSdk = 35
        versionCode = resolvedVersionCode
        versionName = "0.1.0-alpha"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("boolean", "AERO_DYNAMIC_MESH_ENABLED", dynamicMeshEnabled.get().toString())
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
        buildConfig = true
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

// UI7 Editorial: publication gate is part of every app build/flavor, not a manual step.
// It reads only checked-in metadata and approved local artwork; no network/scan.
val verifyUi7EditorialAssets = tasks.register<Exec>("verifyUi7EditorialAssets") {
    group = "verification"
    description = "Reject unapproved, inconsistent or corrupted UI7 Editorial WebP assets"
    workingDir = rootProject.rootDir
    commandLine("python3", rootProject.file("tools/validate_ui7_editorial_assets.py").absolutePath)
    inputs.file(rootProject.file("tools/validate_ui7_editorial_assets.py"))
    inputs.file(file("src/main/java/org/michimusic/mobile/ui/screens/GenreEditorialArtworkPolicy.kt"))
    inputs.dir(file("src/main/assets/ui7/editorial/production"))
}
tasks.named("preBuild").configure { dependsOn(verifyUi7EditorialAssets) }

// Keep the independently published complete-poster contract in the same build gate.
// Poster image/text/frame semantics intentionally differ from derived V1 artwork.
val verifyUi7EditorialPosters = tasks.register<Exec>("verifyUi7EditorialPosters") {
    group = "verification"
    description = "Validate exact source proportions and integrity of completed Editorial posters"
    workingDir = rootProject.rootDir
    commandLine("python3", rootProject.file("tools/validate_ui7_editorial_posters.py").absolutePath)
    inputs.file(rootProject.file("tools/validate_ui7_editorial_posters.py"))
    inputs.file(rootProject.file("tools/validate_ui7_editorial_assets.py"))
    inputs.file(file("src/main/java/org/michimusic/mobile/ui/screens/GenreEditorialArtworkPolicy.kt"))
    inputs.dir(file("src/main/assets/ui7/editorial/posters"))
}
tasks.named("preBuild").configure { dependsOn(verifyUi7EditorialPosters) }

// UI7 Mosaico destacado: the 312 approved local crops are byte-verified on every
// build, and the manifest must remain the deterministic derivative of the pack.
val verifyUi7MosaicAssets = tasks.register<Exec>("verifyUi7MosaicAssets") {
    group = "verification"
    description = "Reject modified, missing or undocumented UI7 Mosaico destacado WebP assets"
    workingDir = rootProject.rootDir
    commandLine("python3", rootProject.file("tools/validate_ui7_mosaic_assets.py").absolutePath)
    inputs.file(rootProject.file("tools/validate_ui7_mosaic_assets.py"))
    inputs.file(rootProject.file("tools/validate_ui7_editorial_assets.py"))
    inputs.file(file("src/main/java/org/michimusic/mobile/ui/screens/GenreMosaicArtworkPolicy.kt"))
    inputs.dir(file("src/main/assets/ui7/mosaic"))
}
tasks.named("preBuild").configure { dependsOn(verifyUi7MosaicAssets) }
