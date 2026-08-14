# Michi Music Mobile — Stability Matrix

## 1. Build Matrix

| Variant / Target | Task | Status | Output Artifact |
| :--- | :--- | :--- | :--- |
| **Lint Global** | `./gradlew lint` | **PASS** | `app/build/reports/lint-results-fdroidDebug.html` |
| **Normal Debug** | `./gradlew assembleNormalDebug` | **PASS** | `app/build/outputs/apk/normal/debug/app-normal-debug.apk` |
| **F-Droid Debug** | `./gradlew assembleFdroidDebug` | **PASS** | `app/build/outputs/apk/fdroid/debug/app-fdroid-debug.apk` |
| **Normal Release** | `./gradlew assembleNormalRelease` | **PASS** (R8 + ProGuard) | `app/build/outputs/apk/normal/release/app-normal-release.apk` |
| **F-Droid Release** | `./gradlew assembleFdroidRelease` | **PASS** (R8 + ProGuard) | `app/build/outputs/apk/fdroid/release/app-fdroid-release.apk` |

---

## 2. Unit Test Matrix

| Module | Test Task | Status | Tests Passed |
| :--- | :--- | :--- | :--- |
| `:core` | `testDebugUnitTest` | **PASS** | All core models & serialization |
| `:data` | `testDebugUnitTest` | **PASS** | Room DAOs, Repositories, Caches |
| `:player` | `testDebugUnitTest` | **PASS** | PlayerController, ReplayGain, MichiAudioEffects, UsbDacManager, LibraryProvider |
| `:michi-link-client` | `testDebugUnitTest` | **PASS** | Discovery, Auth, WebSocket, Streaming |
| `:app` (Normal) | `testNormalDebugUnitTest` | **PASS** | ViewModels, Navigation, Formatters |
| `:app` (F-Droid) | `testFdroidDebugUnitTest` | **PASS** | ViewModels, Offline-first, Storage |

---

## 3. Runtime & Lifecycle Matrix

| Domain | Capability / Scenario | Verification Method | Status |
| :--- | :--- | :--- | :--- |
| **Startup** | Cold start & DI initialization (Koin) | Process startup trace & memory cache | **PASS** |
| **Navigation** | All 6 tabs (`Home`, `Library`, `NowPlaying`, `Remote`, `Sync`, `Settings`) | `SmokeNavigationTest` Compose suite | **PASS** |
| **Media Playback** | ExoPlayer pipeline & AudioAttributes | `PlayerController` unit & lint checks | **PASS** |
| **Audio Routing** | Direct routing to USB DAC, Bluetooth, Wired & Speakers | `UsbDacManager` + ExoPlayer `setPreferredAudioDevice` | **PASS** |
| **DSP & Equalizer** | 5-band hardware Equalizer, BassBoost & Virtualizer | `MichiAudioEffects` bounded tests & Session bindings | **PASS** |
| **Audio Lifecycle** | Becoming Noisy (Headphone unplug / BT disconnect) | `setHandleAudioBecomingNoisy(true)` | **PASS** |
| **Audio Focus** | Auto-ducking & transient pause | `AudioAttributes.setUsage(C.USAGE_MEDIA)` | **PASS** |
| **ReplayGain** | Track, Album, Dynamic mode + NaN/Infinity protection | `ReplayGainAudioProcessor` unit tests | **PASS** |
| **Network Discovery**| Wi-Fi multicast lock lifecycle management | `LinkDiscovery` LifecycleObserver | **PASS** |
| **Offline First** | Local Room library without Michi Server | `LocalMediaRepository` tests | **PASS** |
| **FLOSS Compliance**| Zero Google Play Services / Zero proprietary SDKs | F-Droid flavor audit & ProGuard shrink | **PASS** |

---

## 4. Device Compatibility Matrix

| Android Version | API Level | Compatibility Status | Notes |
| :--- | :--- | :--- | :--- |
| **Android 12** | API 31 (minSdk) | **SUPPORTED** | Legacy storage fallback, MediaSession compat |
| **Android 13** | API 33 | **SUPPORTED** | `READ_MEDIA_AUDIO`, `POST_NOTIFICATIONS` |
| **Android 14** | API 34 | **SUPPORTED** | Foreground Service `mediaPlayback` type compliant |
| **Android 15** | API 35 (targetSdk) | **SUPPORTED** | Edge-to-edge system insets & 16KB page alignment |
