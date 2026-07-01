# Michi Music Mobile

[![License](https://img.shields.io/badge/License-GPL--3.0-blue.svg)](LICENSE)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.4.0-purple.svg)](https://kotlinlang.org)
[![Android](https://img.shields.io/badge/Android-12%2B-brightgreen.svg)](https://developer.android.com)
[![API](https://img.shields.io/badge/Media3-1.10.1-orange.svg)](https://developer.android.com/guide/topics/media/media3)

Android companion app for [Michi Music Player](https://github.com/pitydah/michi-music-mobile) (Linux/KDE).

Sync your music library wirelessly from your desktop and control playback remotely.

## Features

- **Local playback** — MediaStore reader with ReplayGain (ID3v2/FLAC), ExoPlayer via Media3
- **Michi Sync** — UDP discovery (multicast 224.0.0.167:53318), HTTP registration/pairing, track streaming with Range-Request, differential sync via manifest
- **Remote control** — Poll KDE player status on port 8124, play/pause/next/prev/volume from phone
- **CoverFlow** — DiscreteScrollView carousel matching KDE `coverflow.py` visual constants (60° rotation, 1.05 center scale, 0.45 min scale)
- **Android Auto ready** — `MediaLibraryService` with browsable tree (albums, songs, artists, playlists)
- **Glassmorphism UI** — Dark theme (`#090B11`), 14dp radius, accent pink/purple/blue, translucent cards

## Tech Stack

| Layer | Library |
|-------|---------|
| Language | Kotlin 2.4.0 |
| UI | Jetpack Compose + Material 3 |
| Audio | AndroidX Media3 1.10.1 / ExoPlayer |
| HTTP | OkHttp 4.12 + Ktor Client 3.5 |
| Serialization | kotlinx.serialization 1.11 |
| Async | Coroutines 1.10 + Flow |
| DI | Koin 4.2 |
| Cache | Room 2.8 (SQLite) |
| Carousel | `yarolegovich/DiscreteScrollView` 1.5.1 |
| Images | Coil 3.5 |

## Modules

- `:app` — Application entry, UI screens, navigation, DI wiring
- `:core` — Shared domain models (Track, Album, Playlist, Sync DTOs including pairing)
- `:data` — Room database (9 entities), MediaStore reader, repositories
- `:player` — Media3 `MediaLibraryService`, custom `RenderersFactory`, ReplayGain `AudioProcessor`
- `:sync-client` — Sync protocol (UDP discovery, Ktor HTTP client, transfer manager, cover cache)
- `:remote` — KDE remote control HTTP client (OkHttp)

## Screens

| Screen | Description |
|--------|-------------|
| Home | Quick play, shuffle, all tracks list, search bar |
| Library | CoverFlow carousel + album track list |
| Now Playing | Album art, seek bar, playback controls, volume |
| Playlist | All tracks indexed list with active-track highlight |
| Remote | KDE remote control with status polling |
| Sync | Discovery, pairing, registration, download progress |
| Synced Tracks | List of downloaded synced tracks |
| Search | Search local + synced library |
| Settings | ReplayGain mode, pre-amp, remote URL/token, auto-sync |

## Build

### Prerequisites

- Android SDK (compileSdk 36, build-tools 36+)
- JDK 17+
- On **aarch64/ARM64**: install `qemu-user` and `libc6:amd64` for AAPT2 emulation

### Debug

```bash
export ANDROID_HOME=/path/to/android-sdk
./gradlew assembleNormalDebug
```

APK: `app/build/outputs/apk/normal/debug/app-normal-debug.apk`

### Release

```bash
./gradlew assembleNormalRelease
```

Requires `app/keystore.properties` with signing config.

Minimum SDK: 31 (Android 12)
Target SDK: 36

## Port Reference

| Service | Port |
|---------|------|
| Michi Sync | 53318 (UDP discovery + HTTP) |
| Remote API | 8124 (HTTP) |

Do not mix tokens between Sync and Remote — they are separate auth domains.

## Permissions

- `READ_MEDIA_AUDIO` (Android 13+) — MediaStore access
- `INTERNET`, `ACCESS_NETWORK_STATE` — HTTP sync + remote control
- `ACCESS_WIFI_STATE`, `CHANGE_WIFI_MULTICAST_STATE` — UDP peer discovery
- `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK` — Media playback notification
- `POST_NOTIFICATIONS` — Android 13+ notification permission

## Testing

See [docs/TESTING.md](docs/TESTING.md) for manual testing checklist.

## License

GPL-3.0-or-later — see [LICENSE](LICENSE) and [NOTICE](NOTICE).

Third-party components listed in [docs/THIRD_PARTY.md](docs/THIRD_PARTY.md).
