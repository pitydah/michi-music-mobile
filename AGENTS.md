# AGENTS.md — Michi Music Mobile

## Project Identity

**Michi Music Mobile** — Portable node of the [Michi Music Ecosystem](https://github.com/pitydah).

| Field | Value |
|---|---|
| License | GPL-3.0 |
| Repository | https://github.com/pitydah/michi-music-mobile |
| Ecosystem Protocol | [Michi Link](https://github.com/pitydah/michi-link) |
| Language | Kotlin 2.1+ |
| UI | Jetpack Compose + Material 3 |
| Min SDK | 31 (Android 12) |
| Target SDK | 36 (Android 16) |
| Audio Engine | AndroidX Media3 (ExoPlayer + PCM ReplayGain + HAL Audio Effects) |
| Dependency Injection | Koin |
| Network & Remote | OkHttp + Server-Sent Events (SSE) + UDP Broadcast Discovery |
| Persistence & Cache | Room 2.7+ (SQLite) + EncryptedSharedPreferences |
| FLOSS Compliance | 100% FLOSS, pure AOSP compatibility, zero Google Play Services |

---

## Ecosystem Architecture & Source of Truth

Michi Music Mobile is the **portable node** of the Michi ecosystem. Its 5 core responsibilities are:
1. **PLAY**: Real local playback via AndroidX Media3 / ExoPlayer.
2. **ACCESS**: Remote library access and streaming from Michi Micro Server / Big Server.
3. **CONTROL**: Remote playback control of Desktop Player, Servers, and physical Michi Stream receivers.
4. **CONTINUE**: Seamless handoff between devices.
5. **CARRY**: Resilient offline library synchronization via Michi Sync.

### Hierarchy of Truth

When resolving contract or protocol differences:
1. **Priority 1**: Executable code, unit tests, and build configurations in the target repository.
2. **Priority 2**: [pitydah/michi-link](https://github.com/pitydah/michi-link) — the canonical interoperability contract of the ecosystem (schemas, OpenAPI, and wire formats).
3. **Priority 3**: Current architecture documentation of Michi Music Mobile.
4. **Priority 4**: Peer ecosystem repositories ([michi-music-player](https://github.com/pitydah/michi-music-player), [michi-micro-server](https://github.com/pitydah/michi-micro-server), [michi-music-stream](https://github.com/pitydah/michi-music-stream)).
5. **Priority 5**: Historical reference repositories ([michi-legacy](https://github.com/pitydah/michi-legacy)).

> **CRITICAL RULE**: Do not invent parallel APIs or arbitrary payloads. The `pitydah/michi-link` contract supersedes historical ad-hoc protocols.

---

## Active Gradle Modules

| Module | Responsibility |
|---|---|
| `:app` | Jetpack Compose UI, Navigation (`MichiNavHost`), Koin DI Modules, `SyncWorker` (WorkManager) |
| `:core` | Shared domain models (`Track`, `Album`, `Artist`, `Playlist`, `DiscoveredPeer`, enums) |
| `:data` | Room database (`MichiDatabase`), `MediaQueryDispatcher` (MediaStore), `SyncedTrackRepository` |
| `:player` | `MichiPlaybackService` (`MediaLibraryService`), `ExoPlayer`, `ReplayGainAudioProcessor`, `MichiAudioEffects`, `UsbDacManager` |
| `:michi-link-client` | `LinkClient` (HTTP REST), `EventClient` (SSE), `LinkDiscovery` (UDP 21120), `TokenStore` (AES Encrypted) |

---

## Build & Test

```bash
# Set Android SDK and JDK 21
export ANDROID_HOME=/path/to/android-sdk
export JAVA_HOME=/path/to/jdk-21

# Run unit tests across all modules
./gradlew test

# Run lint quality gate
./gradlew lint

# Build all APK variants (Normal & F-Droid)
./gradlew assembleNormalDebug assembleFdroidDebug assembleNormalRelease assembleFdroidRelease
```

---

## Design System: Digital Ethereal

- **Base Theme**: Pure Obsidian (`#090B11`), smoked glass surfaces (`0x1FFFFFFF`), 14–24dp rounded corners.
- **Accents**: Emotional Pink (`PrimaryPinkContainer`), Cyan audio feedback (`TertiaryCyan`), Secondary Purple (`SecondaryPurple`).
- **CoverFlow**: 3D interactive album carousel in Library with native Canvas/graphicsLayer rendering.
- **Accessibility**: All interactive targets $\ge 48\text{ dp}$, Spanish semantic descriptions, and truthful affordances (zero placebo controls).
