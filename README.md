# Michi Music Mobile

Android companion app for [Michi Music Player](https://github.com/pitydah/michi-music-player) (Linux/KDE).

Sync your music library wirelessly from your desktop to your Android device.

## License

GPL-3.0-or-later

## Tech Stack

| Layer | Library |
|-------|---------|
| Language | Kotlin 2.0 |
| UI | Jetpack Compose + Material 3 |
| HTTP | OkHttp 4 |
| Serialization | kotlinx.serialization |
| Async | Coroutines + Flow |
| Audio (future) | AndroidX Media3 / ExoPlayer |
| DI | Koin |

## Modules

- `:app` — Application entry, sync UI, theme, navigation
- `:sync-client` — Sync protocol client (UDP discovery, Ktor HTTP)
- `:data` — Room database for synced library cache
- `:player` — Media3 playback service (in preparation)
- `:remote` — Remote control client for KDE (future)
- `:core` — Shared domain models

## Sync Protocol

The app communicates with Michi Music Player over HTTP on port 53318:

- `POST /api/register` — Device handshake, obtains Bearer token
- `GET /api/library` — Full library listing (tracks, artists, albums)
- `GET /api/stream/{track_id}` — Audio file streaming with Range-Request
- `GET /api/cover/{cover_id}` — Album art
- `GET /api/sync/manifest?device_id=X` — Differential sync manifest
- `POST /api/sync/state` — Sync play counts and favorites

Future discovery: UDP multicast `224.0.0.167:53318`.

See [docs/MICHI_SYNC_PROTOCOL.md](docs/MICHI_SYNC_PROTOCOL.md) for the full specification.

## Build

```bash
export ANDROID_HOME=/path/to/android-sdk
./gradlew assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

Minimum SDK: 31 (Android 12)
Target SDK: 35

## Integration with Michi KDE

The `sync/` package in `michi-music-player` runs an HTTP server on port 53318 that serves
the music library and handles streaming. This Android app is designed as a companion that:

1. Discovers KDE on the local network (multicast or manual IP)
2. Registers as a paired device
3. Fetches library metadata and sync manifests
4. Downloads tracks via Range-Request streaming
5. Syncs playback state back to KDE

See [docs/KDE_INTEGRATION.md](docs/KDE_INTEGRATION.md).
