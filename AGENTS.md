# AGENTS.md — Michi Music Mobile

## Project Identity

Michi Music Mobile — Android companion app for [Michi Music Player](https://github.com/pitydah/michi-music-player) (Linux/KDE).

| Field | Value |
|-------|-------|
| License | GPL-3.0 |
| Repository | https://github.com/pitydah/michi-music-mobile |
| Language | Kotlin |
| UI | Jetpack Compose + Material 3 |
| Min SDK | 31 (Android 12) |
| Target SDK | 35 |
| Audio | AndroidX Media3 / ExoPlayer |
| DI | Koin |
| Sync | Ktor HTTP + UDP Multicast |
| Cache | Room (SQLite) |

## Setup (aarch64 / ARM64 Linux)

This project builds on aarch64 Linux using QEMU binfmt for x86_64 toolchain emulation:

```bash
# Required: x86_64 libraries for AAPT2 emulation
sudo apt-get install qemu-user
sudo mkdir -p /lib64
sudo ln -sf /path/to/amd64/libc6/usr/lib64/ld-linux-x86-64.so.2 /lib64/ld-linux-x86-64.so.2
sudo ln -sf /path/to/amd64/libc6/usr/lib/x86_64-linux-gnu /lib/x86_64-linux-gnu
```

On x86_64 Linux, no special setup is needed.

## Build

```bash
export ANDROID_HOME=/path/to/android-sdk
./gradlew assembleDebug
```

## Modules

| Module | Responsibility |
|--------|---------------|
| `:app` | UI, navigation, DI wiring |
| `:core` | Shared models (Track, Album, Sync DTOs) |
| `:data` | Room database, MediaStore reader, repositories |
| `:player` | Media3 ExoPlayer service, playback state |
| `:sync-client` | Ktor HTTP client, UDP discovery, transfer manager |
| `:remote` | Remote control client for KDE |

## Architecture

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for full details.
