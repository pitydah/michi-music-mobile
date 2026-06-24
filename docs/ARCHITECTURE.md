# Michi Music Mobile — Architecture

## Overview

Michi Music Mobile is the Android companion app for [Michi Music Player](https://github.com/pitydah/michi-music-player) (Linux/KDE desktop).

```
┌──────────────────────────────────────────────────────────┐
│                    Michi Music Mobile                     │
├──────────────────────────────────────────────────────────┤
│  UI Layer: Jetpack Compose + Material 3                  │
│  ├── HomeScreen / LibraryScreen / NowPlayingScreen       │
│  ├── SyncScreen / SettingsScreen / AudioRouteScreen      │
│  └── RemoteScreen (control remoto de KDE)               │
├──────────────────────────────────────────────────────────┤
│  Player: AndroidX Media3 / ExoPlayer                     │
│  ├── MichiPlaybackService (MediaSessionService)          │
│  ├── PlayerController / PlayerViewModel                  │
│  └── AudioRoute detection (BT LDAC, USB DAC, speakers)  │
├──────────────────────────────────────────────────────────┤
│  Data Layer: Room + MediaStore                           │
│  ├── Room (SQLite FTS4) — cache de biblioteca KDE       │
│  └── MediaStore — archivos locales del dispositivo       │
├──────────────────────────────────────────────────────────┤
│  Sync Client: Ktor HTTP + UDP Multicast Discovery        │
│  ├── DiscoveryClient (UDP 224.0.0.167:53318)             │
│  ├── MichiSyncClient (REST API + streaming)              │
│  └── SyncTransferManager (descarga incremental)          │
├──────────────────────────────────────────────────────────┤
│  Remote Control: Ktor HTTP                               │
│  ├── RemoteControlClient (controla KDE por red)          │
│  └── RemoteViewModel                                     │
├──────────────────────────────────────────────────────────┤
│  Core: Modelos compartidos + Koin DI                     │
└──────────────────────────────────────────────────────────┘
```

## Key Decisions

| Decision | Rationale |
|----------|-----------|
| Kotlin + Jetpack Compose | UI declarativa moderna, Material 3, animations nativas |
| API 31+ | BluetoothCodecStatus accesible, Material You dinámico |
| Room + MediaStore | Room para library sync desde KDE; MediaStore para archivos locales |
| Ktor (no Retrofit) | Multiplataforma, liviano, F-Droid safe, Apache 2.0 |
| Koin (no Hilt) | Sin annotation processing, velocidad compilación, F-Droid safe |
| No Google Play Services | F-Droid compliance, sin dependencias privativas |

## Architecture Patterns

### Dependency Injection
- All dependencies via Koin modules
- ViewModels receive repositories via `koinInject()` or constructor inject

### PlayerService as Facade
- UI never touches Media3 ExoPlayer directly
- `PlayerController` wraps ExoPlayer + MediaSession
- `PlayerViewModel` exposes state as Compose State

### Repository Pattern
- `LibraryRepository` wraps Room (KDE tracks) + MediaStore (local files)
- `SyncRepository` wraps Ktor client + download manager

### Single Activity
- One `MainActivity` with Compose navigation
- Bottom navigation with 5 tabs
- Additional screens (AudioRoute, Remote) via route navigation

## Project Modules

| Module | Responsibility |
|--------|---------------|
| `:app` | UI, navigation, DI wiring |
| `:core` | Shared models (Track, Album, Sync DTOs) |
| `:data` | Room database, MediaStore reader, repositories |
| `:player` | Media3 ExoPlayer service, playback state |
| `:sync-client` | Ktor HTTP client, UDP discovery, transfer manager |
| `:remote` | Remote control client for KDE |
