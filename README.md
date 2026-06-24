# Michi Music Mobile

Android companion app for [Michi Music Player](https://github.com/pitydah/michi-music-player) — an audiophile music player for Linux/KDE.

## Features

- **Sync with Michi KDE** — Discover and sync your music library over LAN
- **High-quality playback** — Media3/ExoPlayer with FLAC, ALAC, WAV, MP3, Opus, AAC
- **Bluetooth Hi-Res** — LDAC/aptX HD/aptX Adaptive/LHDC support via Android system (informational)
- **USB-C DAC** — External DAC detection and recommendation
- **Audio Route** — See exactly where your audio is going and at what quality
- **Remote Control** — Control Michi Music Player from your phone (coming soon)
- **Offline-first** — Downloads synced from KDE for listening without network
- **100% FOSS** — No Google Play Services, no proprietary dependencies

## Architecture

```
sync-client ──→ HTTP ──→ Michi Music Player (KDE)
     │                         │
     │ UDP Discovery           │ PlayerService
     │                         │
     ▼                         ▼
  Room DB ◄── Library Sync    Status ◄── Remote Control
     │
     ▼
  Media3/ExoPlayer ──→ AudioRoute (BT / USB / Speaker)
```

## Tech Stack

| Component | Technology |
|-----------|-----------|
| Language | Kotlin |
| UI | Jetpack Compose + Material 3 |
| Player | AndroidX Media3 / ExoPlayer |
| Sync | Ktor HTTP + UDP Multicast |
| Cache | Room (SQLite FTS4) |
| DI | Koin |
| Images | Coil |
| License | GPL-3.0 |

## Build

```bash
git clone https://github.com/pitydah/michi-music-mobile.git
cd michi-music-mobile
./gradlew assembleDebug
```

Requires Android SDK 34, JDK 17.

## Download

Available on F-Droid (coming soon).

## Related

- [Michi Music Player](https://github.com/pitydah/michi-music-player) — Desktop player for Linux/KDE
- [Michi Sync Protocol](docs/MICHI_SYNC.md)

## License

GNU General Public License v3.0 — see [LICENSE](LICENSE).
